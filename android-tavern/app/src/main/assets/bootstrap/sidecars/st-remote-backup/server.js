const express = require('express');
const fs = require('fs');
const fsp = require('fs').promises;
const path = require('path');
const tar = require('tar');
const basicAuth = require('basic-auth');
const crypto = require('crypto');
const { spawn } = require('child_process');
const { Readable } = require('stream');
const { pipeline } = require('stream/promises');

const APP_DIR = process.env.APP_DIR || path.resolve(__dirname);
const CONFIG_FILE = path.join(APP_DIR, 'config.json');
const DISPLAY_TIMEZONE = 'Asia/Shanghai';
const EMPTY_SHA256 = crypto.createHash('sha256').update('').digest('hex');
const SILLYDROID_EMBEDDED = process.env.SILLYDROID_EMBEDDED === '1';

function defaultDataDir() {
  return process.env.DATA_DIR ||
    (SILLYDROID_EMBEDDED && process.env.TAVERN_DATA_ROOT
      ? path.join(process.env.TAVERN_DATA_ROOT, 'data')
      : process.env.TAVERN_DATA_ROOT) ||
    (SILLYDROID_EMBEDDED ? path.join(APP_DIR, 'data') : '/root/sillytavern/data');
}

function normalizeEmbeddedConfig(cfg) {
  if (!SILLYDROID_EMBEDDED) return cfg;

  const tavernRoot = process.env.TAVERN_DATA_ROOT || '';
  const tavernDataDir = process.env.DATA_DIR ||
    (tavernRoot ? path.join(tavernRoot, 'data') : '');

  if (!tavernRoot || !tavernDataDir || !cfg.dataDir) return cfg;

  if (path.resolve(cfg.dataDir) === path.resolve(tavernRoot)) {
    return { ...cfg, dataDir: tavernDataDir };
  }

  return cfg;
}

function loadConfig() {
  const defaults = {
    port: parseInt(process.env.PORT || '8787', 10),
    bindHost: process.env.BIND_HOST || process.env.HOST || '127.0.0.1',
    dataDir: defaultDataDir(),
    backupDir: process.env.BACKUP_DIR || path.join(APP_DIR, 'backups'),
    user: process.env.BASIC_USER || '',
    pass: process.env.BASIC_PASS || '',
    r2AccountId: process.env.R2_ACCOUNT_ID || '',
    r2Bucket: process.env.R2_BUCKET || '',
    r2AccessKeyId: process.env.R2_ACCESS_KEY_ID || '',
    r2SecretAccessKey: process.env.R2_SECRET_ACCESS_KEY || '',
    r2Prefix: process.env.R2_PREFIX || '',
    r2Region: process.env.R2_REGION || 'auto'
  };

  try {
    if (fs.existsSync(CONFIG_FILE)) {
      const data = JSON.parse(fs.readFileSync(CONFIG_FILE, 'utf8'));
      return normalizeEmbeddedConfig({ ...defaults, ...data });
    }
  } catch (e) {
    console.error('[config] Failed to load config.json:', e.message);
  }

  return normalizeEmbeddedConfig(defaults);
}

async function saveConfig(cfg) {
  await ensureDir(path.dirname(CONFIG_FILE));
  await fsp.writeFile(CONFIG_FILE, JSON.stringify(cfg, null, 2), 'utf8');
}

function getConfig() {
  const cfg = loadConfig();
  return {
    ...cfg,
    r2Prefix: normalizeR2Prefix(cfg.r2Prefix),
    r2Region: cfg.r2Region || 'auto'
  };
}

async function ensureDir(dir) {
  await fsp.mkdir(dir, { recursive: true }).catch(() => { });
}

async function fileExists(file) {
  try {
    await fsp.access(file);
    return true;
  } catch {
    return false;
  }
}

async function replaceFile(from, to) {
  await fsp.rm(to, { force: true }).catch(() => { });
  await fsp.rename(from, to);
}

function isBackupFileName(name) {
  return /\.tar\.gz$/i.test(name);
}

function formatDateParts(date = new Date(), timeZone = DISPLAY_TIMEZONE) {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit'
  }).formatToParts(date);
  const values = Object.fromEntries(parts.map((part) => [part.type, part.value]));
  return {
    year: values.year,
    month: values.month,
    day: values.day
  };
}

function getDailyBackupName(date = new Date()) {
  const { year, month, day } = formatDateParts(date);
  return `st-data-${year}-${month}-${day}.tar.gz`;
}

function normalizeR2Prefix(prefix) {
  return (prefix || '').trim().replace(/^\/+|\/+$/g, '');
}

function getR2Endpoint(cfg) {
  if (!cfg.r2AccountId) return '';
  return `https://${cfg.r2AccountId}.r2.cloudflarestorage.com`;
}

function hasR2Config(cfg) {
  return !!(cfg.r2AccountId && cfg.r2Bucket && cfg.r2AccessKeyId && cfg.r2SecretAccessKey);
}

function getBackupObjectKey(name, cfg) {
  const prefix = normalizeR2Prefix(cfg.r2Prefix);
  return prefix ? `${prefix}/${name}` : name;
}

function encodeRfc3986(value) {
  return encodeURIComponent(value).replace(/[!*'()]/g, (ch) => `%${ch.charCodeAt(0).toString(16).toUpperCase()}`);
}

function encodeS3Path(key) {
  if (!key) return '';
  return key.split('/').map(encodeRfc3986).join('/');
}

function buildCanonicalQuery(query = {}) {
  return Object.entries(query)
    .filter(([, value]) => value !== undefined && value !== null && value !== '')
    .map(([key, value]) => [encodeRfc3986(String(key)), encodeRfc3986(String(value))])
    .sort(([aKey, aValue], [bKey, bValue]) => {
      if (aKey === bKey) return aValue.localeCompare(bValue);
      return aKey.localeCompare(bKey);
    })
    .map(([key, value]) => `${key}=${value}`)
    .join('&');
}

function buildAmzDate(date = new Date()) {
  return date.toISOString().replace(/[:-]|\.\d{3}/g, '');
}

function hmac(key, value, encoding) {
  return crypto.createHmac('sha256', key).update(value, 'utf8').digest(encoding);
}

function sha256Hex(value) {
  return crypto.createHash('sha256').update(value).digest('hex');
}

async function sha256File(filePath) {
  return new Promise((resolve, reject) => {
    const hash = crypto.createHash('sha256');
    const stream = fs.createReadStream(filePath);
    stream.on('data', (chunk) => hash.update(chunk));
    stream.on('error', reject);
    stream.on('end', () => resolve(hash.digest('hex')));
  });
}

function getSigningKey(secret, dateStamp, region, service) {
  const kDate = hmac(`AWS4${secret}`, dateStamp);
  const kRegion = hmac(kDate, region);
  const kService = hmac(kRegion, service);
  return hmac(kService, 'aws4_request');
}

async function signedR2Fetch(cfg, method, objectKey = '', options = {}) {
  if (!hasR2Config(cfg)) {
    throw new Error('R2 config is incomplete');
  }

  const endpoint = new URL(getR2Endpoint(cfg));
  const canonicalUri = objectKey
    ? `/${encodeRfc3986(cfg.r2Bucket)}/${encodeS3Path(objectKey)}`
    : `/${encodeRfc3986(cfg.r2Bucket)}`;
  const canonicalQuery = buildCanonicalQuery(options.query);
  const requestUrl = `${endpoint.origin}${canonicalUri}${canonicalQuery ? `?${canonicalQuery}` : ''}`;

  const now = new Date();
  const amzDate = buildAmzDate(now);
  const dateStamp = amzDate.slice(0, 8);
  const payloadHash = options.payloadHash || EMPTY_SHA256;

  const headers = {};
  const inputHeaders = options.headers || {};
  for (const [key, value] of Object.entries(inputHeaders)) {
    if (value !== undefined && value !== null && value !== '') {
      headers[key.toLowerCase()] = String(value);
    }
  }

  headers.host = endpoint.host;
  headers['x-amz-content-sha256'] = payloadHash;
  headers['x-amz-date'] = amzDate;

  const signedHeaderKeys = Object.keys(headers).sort();
  const canonicalHeaders = signedHeaderKeys
    .map((key) => `${key}:${headers[key].trim().replace(/\s+/g, ' ')}`)
    .join('\n') + '\n';
  const signedHeaders = signedHeaderKeys.join(';');

  const canonicalRequest = [
    method.toUpperCase(),
    canonicalUri,
    canonicalQuery,
    canonicalHeaders,
    signedHeaders,
    payloadHash
  ].join('\n');

  const credentialScope = `${dateStamp}/${cfg.r2Region}/s3/aws4_request`;
  const stringToSign = [
    'AWS4-HMAC-SHA256',
    amzDate,
    credentialScope,
    sha256Hex(canonicalRequest)
  ].join('\n');

  const signingKey = getSigningKey(cfg.r2SecretAccessKey, dateStamp, cfg.r2Region, 's3');
  const signature = hmac(signingKey, stringToSign, 'hex');

  headers.authorization =
    `AWS4-HMAC-SHA256 Credential=${cfg.r2AccessKeyId}/${credentialScope}, SignedHeaders=${signedHeaders}, Signature=${signature}`;

  return fetch(requestUrl, {
    method,
    headers,
    body: options.body,
    duplex: options.duplex
  });
}

function decodeXmlEntities(value) {
  return value
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&apos;/g, "'");
}

function readXmlTag(xml, tag) {
  const match = xml.match(new RegExp(`<${tag}>([\\s\\S]*?)</${tag}>`));
  return match ? decodeXmlEntities(match[1]) : '';
}

function parseListBucketResult(xml) {
  const items = [];
  const blocks = xml.match(/<Contents>[\s\S]*?<\/Contents>/g) || [];
  for (const block of blocks) {
    items.push({
      key: readXmlTag(block, 'Key'),
      lastModified: readXmlTag(block, 'LastModified'),
      size: parseInt(readXmlTag(block, 'Size') || '0', 10)
    });
  }

  return {
    items,
    isTruncated: readXmlTag(xml, 'IsTruncated') === 'true',
    nextContinuationToken: readXmlTag(xml, 'NextContinuationToken')
  };
}

async function readR2Error(action, response) {
  const body = await response.text().catch(() => '');
  const msg = body.replace(/\s+/g, ' ').trim().slice(0, 300);
  return new Error(`[r2] ${action} failed (${response.status}): ${msg || response.statusText}`);
}

async function listR2Backups(cfg) {
  if (!hasR2Config(cfg)) return [];

  const prefix = normalizeR2Prefix(cfg.r2Prefix);
  const queryPrefix = prefix ? `${prefix}/` : undefined;
  const items = [];
  let continuationToken = '';

  do {
    const response = await signedR2Fetch(cfg, 'GET', '', {
      query: {
        'list-type': '2',
        prefix: queryPrefix,
        'continuation-token': continuationToken || undefined
      }
    });

    if (!response.ok) {
      throw await readR2Error('list', response);
    }

    const xml = await response.text();
    const parsed = parseListBucketResult(xml);

    for (const item of parsed.items) {
      const name = path.posix.basename(item.key);
      if (!isBackupFileName(name)) continue;
      items.push({
        name,
        size: item.size,
        mtime: item.lastModified,
        local: false,
        remote: true
      });
    }

    continuationToken = parsed.isTruncated ? parsed.nextContinuationToken : '';
  } while (continuationToken);

  return items;
}

async function uploadBackupToR2(cfg, filePath, name) {
  const objectKey = getBackupObjectKey(name, cfg);
  const stat = await fsp.stat(filePath);
  const payloadHash = await sha256File(filePath);
  const response = await signedR2Fetch(cfg, 'PUT', objectKey, {
    headers: {
      'content-length': String(stat.size),
      'content-type': 'application/gzip'
    },
    body: fs.createReadStream(filePath),
    payloadHash,
    duplex: 'half'
  });

  if (!response.ok) {
    throw await readR2Error('upload', response);
  }
}

async function sleep(ms) {
  await new Promise((resolve) => setTimeout(resolve, ms));
}

async function uploadBackupToR2WithRetry(cfg, filePath, name, retries = 3) {
  let lastError;

  for (let attempt = 1; attempt <= retries; attempt += 1) {
    try {
      await uploadBackupToR2(cfg, filePath, name);
      if (attempt > 1) {
        console.log(`[backup] R2 upload succeeded on retry ${attempt}/${retries}: ${name}`);
      }
      return { attempts: attempt };
    } catch (error) {
      lastError = error;
      console.error(`[backup] R2 upload attempt ${attempt}/${retries} failed: ${error.message}`);
      if (attempt < retries) {
        await sleep(attempt * 1500);
      }
    }
  }

  throw lastError;
}

async function getR2BackupResponse(cfg, name) {
  const response = await signedR2Fetch(cfg, 'GET', getBackupObjectKey(name, cfg));
  if (response.status === 404) return null;
  if (!response.ok) throw await readR2Error('download', response);
  return response;
}

async function deleteR2Backup(cfg, name) {
  const response = await signedR2Fetch(cfg, 'DELETE', getBackupObjectKey(name, cfg), {
    payloadHash: EMPTY_SHA256
  });
  if (!response.ok && response.status !== 404) {
    throw await readR2Error('delete', response);
  }
}

async function downloadR2BackupToFile(cfg, name, filePath) {
  const response = await getR2BackupResponse(cfg, name);
  if (!response) return false;
  if (!response.body) throw new Error('R2 download returned no body');
  await pipeline(Readable.fromWeb(response.body), fs.createWriteStream(filePath));
  return true;
}

async function streamR2BackupToResponse(cfg, name, res) {
  const response = await getR2BackupResponse(cfg, name);
  if (!response) return false;
  if (!response.body) throw new Error('R2 download returned no body');

  const contentLength = response.headers.get('content-length');
  const contentType = response.headers.get('content-type') || 'application/gzip';
  res.setHeader('Content-Type', contentType);
  if (contentLength) res.setHeader('Content-Length', contentLength);
  res.setHeader('Content-Disposition', `attachment; filename="${path.basename(name).replace(/"/g, '')}"`);
  await pipeline(Readable.fromWeb(response.body), res);
  return true;
}

async function listLocalBackups(backupDir) {
  await ensureDir(backupDir);
  const files = await fsp.readdir(backupDir, { withFileTypes: true });
  const items = [];

  for (const file of files) {
    if (!file.isFile() || !isBackupFileName(file.name)) continue;
    const filePath = path.join(backupDir, file.name);
    const stat = await fsp.stat(filePath);
    items.push({
      name: file.name,
      size: stat.size,
      mtime: stat.mtime,
      local: true,
      remote: false
    });
  }

  return items;
}

async function pruneLocalBackups(backupDir, keepName = '') {
  await ensureDir(backupDir);
  const files = await fsp.readdir(backupDir, { withFileTypes: true });

  for (const file of files) {
    if (!file.isFile() || !isBackupFileName(file.name) || file.name === keepName) continue;
    const filePath = path.join(backupDir, file.name);
    await fsp.unlink(filePath).catch(() => { });
    console.log(`[backup] auto-deleted local backup: ${file.name}`);
  }
}

function mergeBackupLists(localItems, remoteItems) {
  const map = new Map();

  for (const item of [...localItems, ...remoteItems]) {
    const existing = map.get(item.name) || {
      name: item.name,
      size: item.size,
      mtime: item.mtime,
      local: false,
      remote: false
    };

    existing.size = existing.size || item.size;
    if (!existing.mtime || new Date(item.mtime) > new Date(existing.mtime)) {
      existing.mtime = item.mtime;
    }
    existing.local = existing.local || !!item.local;
    existing.remote = existing.remote || !!item.remote;

    if (item.local) existing.size = item.size;
    if (!existing.local && item.remote) existing.size = item.size;

    map.set(item.name, existing);
  }

  return Array.from(map.values()).sort((a, b) => new Date(b.mtime) - new Date(a.mtime));
}

const LOG_BUF = [];
const LOG_MAX = 2000;
const originalLog = console.log;
const originalError = console.error;

function pushLog(level, message) {
  const ts = new Date().toLocaleString('zh-CN', { timeZone: DISPLAY_TIMEZONE, hour12: false });
  const line = `${ts} [${level}] ${message}`;
  LOG_BUF.push(line);
  if (LOG_BUF.length > LOG_MAX) LOG_BUF.splice(0, LOG_BUF.length - LOG_MAX);
  (level === 'error' ? originalError : originalLog)(line);
}

console.log = (...args) => pushLog('info', args.join(' '));
console.error = (...args) => pushLog('error', args.join(' '));

const EXCLUDE_SEGMENTS_ALWAYS = new Set(['.git', 'node_modules']);
const EXCLUDE_SEGMENTS_CACHE = new Set(['_cache', '_uploads', '_storage', '_webpack', '.cache', '.parcel-cache', '.vite', 'coverage']);
const EXCLUDE_PREFIXES = ['default-user/backups'];
const EXCLUDE_SUFFIXES = ['.zip', '.tar', '.tar.gz'];

function shouldInclude(relPath) {
  const p = relPath.replace(/^\.\/?/, '');
  if (EXCLUDE_PREFIXES.some((prefix) => p === prefix || p.startsWith(prefix + '/'))) return false;
  const parts = p.split('/');
  if (parts.some((segment) => EXCLUDE_SEGMENTS_ALWAYS.has(segment))) return false;
  const isThirdParty = parts[0] === 'third-party';
  if (!isThirdParty && parts.some((segment) => EXCLUDE_SEGMENTS_CACHE.has(segment))) return false;
  if (EXCLUDE_SUFFIXES.some((suffix) => p.endsWith(suffix))) return false;
  return true;
}

const ST_DATA_TOP_LEVEL_MARKERS = new Set([
  'default-user',
  'characters',
  'chats',
  'groups',
  'group chats',
  'User Avatars',
  'worlds',
  'backgrounds',
  'themes',
  'settings.json',
  'secrets.json'
]);

function normalizeArchiveEntryPath(entryPath) {
  return String(entryPath || '')
    .replace(/\\/g, '/')
    .replace(/^\.\/+/, '')
    .replace(/\/+$/, '');
}

function isStDataEntry(entryPath) {
  const normalized = normalizeArchiveEntryPath(entryPath);
  if (!normalized) return false;
  const top = normalized.split('/')[0];
  return ST_DATA_TOP_LEVEL_MARKERS.has(top);
}

function isAggregateSillyDroidEntry(entryPath) {
  const normalized = normalizeArchiveEntryPath(entryPath);
  return normalized === 'data' ||
    normalized.startsWith('data/') ||
    normalized === 'config' ||
    normalized.startsWith('config/') ||
    normalized === 'plugins' ||
    normalized.startsWith('plugins/') ||
    normalized === 'extensions' ||
    normalized.startsWith('extensions/');
}

async function listTarEntries(archivePath) {
  const entries = [];
  await tar.t({
    file: archivePath,
    onentry: (entry) => {
      const normalized = normalizeArchiveEntryPath(entry.path);
      if (normalized) entries.push(normalized);
    }
  });
  return entries;
}

async function resolveRestoreTargetDir(archivePath, cfg) {
  if (!SILLYDROID_EMBEDDED) return cfg.dataDir;

  const tavernRoot = process.env.TAVERN_DATA_ROOT || '';
  const tavernDataDir = process.env.DATA_DIR ||
    (tavernRoot ? path.join(tavernRoot, 'data') : cfg.dataDir);
  if (!tavernRoot) return cfg.dataDir;

  const entries = await listTarEntries(archivePath);
  const hasTopLevelStData = entries.some(isStDataEntry);
  const hasAggregateLayout = entries.some(isAggregateSillyDroidEntry) &&
    entries.some((entry) => entry.startsWith('data/') || entry === 'data');

  if (hasAggregateLayout && !hasTopLevelStData) {
    console.log(`[restore] detected SillyDroid aggregate backup; cwd=${tavernRoot}`);
    return tavernRoot;
  }

  console.log(`[restore] detected SillyTavern data backup; cwd=${tavernDataDir}`);
  return tavernDataDir;
}

function authGuard(req, res, next) {
  const cfg = getConfig();
  if (!cfg.user && !cfg.pass) return next();

  const creds = basicAuth(req);
  if (creds && creds.name === cfg.user && creds.pass === cfg.pass) return next();

  return res.status(401).send('Unauthorized');
}

const startupConfig = getConfig();
const PORT = startupConfig.port;
const BIND_HOST = startupConfig.bindHost || '127.0.0.1';

const app = express();
app.use(express.json({ limit: '1mb' }));

const PUBLIC_DIR = path.join(__dirname, 'public');
app.use('/', express.static(PUBLIC_DIR));
app.get('/favicon.ico', (req, res) => res.status(204).end());
app.use(authGuard);

app.get('/health', async (req, res) => {
  const cfg = getConfig();
  res.json({
    ok: true,
    dataDir: cfg.dataDir,
    backupDir: cfg.backupDir,
    r2Configured: hasR2Config(cfg)
  });
});

app.get('/logs', (req, res) => {
  const limit = Math.min(parseInt(req.query.limit || '500', 10), LOG_MAX);
  res.json({ lines: LOG_BUF.slice(-limit) });
});

app.delete('/logs', (req, res) => {
  LOG_BUF.length = 0;
  res.json({ ok: true });
});

app.post('/upload', async (req, res) => {
  const cfg = getConfig();
  const name = (req.query.name || '').toString();
  if (!name) return res.status(400).json({ ok: false, error: 'name required' });

  const safeName = path.basename(name);
  const out = path.join(cfg.backupDir, safeName);

  try {
    await ensureDir(cfg.backupDir);
    const writeStream = fs.createWriteStream(out);
    req.pipe(writeStream);

    req.on('error', (err) => {
      console.error('[upload] request error:', err.message);
      writeStream.close();
    });

    writeStream.on('finish', () => {
      console.log(`[upload] received: ${safeName}`);
      res.json({ ok: true });
    });

    writeStream.on('error', (err) => {
      console.error(`[upload] write error: ${safeName}`, err.message);
      res.status(500).json({ ok: false, error: err.message });
    });
  } catch (e) {
    console.error('[upload] error:', e.message);
    res.status(500).json({ ok: false, error: e.message });
  }
});

app.post('/backup', async (req, res) => {
  const cfg = getConfig();
  const name = getDailyBackupName();
  const out = path.join(cfg.backupDir, name);
  const tempOut = `${out}.partial`;
  const t0 = Date.now();

  try {
    await ensureDir(cfg.backupDir);
    await fsp.rm(tempOut, { force: true }).catch(() => { });

    await tar.c({
      gzip: true,
      gzipOptions: { level: 1 },
      file: tempOut,
      cwd: cfg.dataDir,
      filter: (entryPath) => shouldInclude(entryPath)
    }, ['.']);

    await replaceFile(tempOut, out);

    const stat = await fsp.stat(out);
    console.log(`[backup] done name=${name} size=${(stat.size / 1048576).toFixed(2)}MB time=${Date.now() - t0}ms`);

    let keepLocalName = name;
    let warning = '';
    if (hasR2Config(cfg)) {
      try {
        console.log(`[backup] uploading to R2 bucket=${cfg.r2Bucket}`);
        const result = await uploadBackupToR2WithRetry(cfg, out, name, 3);
        console.log(`[backup] uploaded to R2: ${name}`);
        if (result.attempts > 1) {
          console.log(`[backup] R2 upload finished after ${result.attempts} attempts: ${name}`);
        }
        keepLocalName = '';
      } catch (err) {
        warning = err.message;
        console.error(`[backup] R2 upload failed: ${warning}`);
      }
    }

    await pruneLocalBackups(cfg.backupDir, keepLocalName);

    res.json({ ok: true, file: name, warning: warning || undefined });
  } catch (e) {
    await fsp.rm(tempOut, { force: true }).catch(() => { });
    console.error('[backup] error:', e && e.stack || e);
    res.status(500).json({ ok: false, error: e.message });
  }
});

app.post('/upload-r2', async (req, res) => {
  const cfg = getConfig();
  const name = (req.query.name || req.body?.name || '').toString();
  const safeName = path.basename(name);

  try {
    if (!safeName) return res.status(400).json({ ok: false, error: 'name required' });
    if (!hasR2Config(cfg)) return res.status(400).json({ ok: false, error: 'R2 not configured' });

    const localFile = path.join(cfg.backupDir, safeName);
    if (!await fileExists(localFile)) {
      return res.status(404).json({ ok: false, error: 'local backup not found' });
    }

    console.log(`[upload-r2] start name=${safeName}`);
    const result = await uploadBackupToR2WithRetry(cfg, localFile, safeName, 3);
    await pruneLocalBackups(cfg.backupDir, '');
    console.log(`[upload-r2] success name=${safeName} attempts=${result.attempts}`);
    res.json({ ok: true, attempts: result.attempts });
  } catch (e) {
    console.error('[upload-r2] error:', e && e.stack || e);
    res.status(500).json({ ok: false, error: e.message });
  }
});

app.get('/list', async (req, res) => {
  const cfg = getConfig();

  try {
    const localItems = await listLocalBackups(cfg.backupDir);
    let remoteItems = [];
    let warning = '';

    if (hasR2Config(cfg)) {
      try {
        remoteItems = await listR2Backups(cfg);
      } catch (err) {
        warning = err.message;
        console.error(`[list] R2 list failed: ${warning}`);
      }
    }

    const items = mergeBackupLists(localItems, remoteItems);
    console.log(`[list] ok count=${items.length}`);
    res.json({ ok: true, items, warning: warning || undefined });
  } catch (e) {
    console.error('[list] error:', e && e.stack || e);
    res.status(500).json({ ok: false, error: e.message });
  }
});

app.get('/download', async (req, res) => {
  const cfg = getConfig();
  const name = (req.query.name || '').toString();
  if (!name) return res.status(400).send('name required');

  const safeName = path.basename(name);
  const file = path.join(cfg.backupDir, safeName);

  try {
    if (await fileExists(file)) {
      console.log(`[download] local start name=${safeName}`);
      return res.download(file, safeName, (err) => {
        if (err) console.error(`[download] local error name=${safeName}`, err.message);
      });
    }

    if (hasR2Config(cfg)) {
      console.log(`[download] R2 start name=${safeName}`);
      const streamed = await streamR2BackupToResponse(cfg, safeName, res);
      if (streamed) return;
    }

    console.error(`[download] not found name=${safeName}`);
    res.status(404).send('File not found');
  } catch (e) {
    console.error(`[download] error name=${safeName}`, e.message);
    if (!res.headersSent) res.status(500).send('Download failed');
  }
});

app.post('/restore', async (req, res) => {
  const cfg = getConfig();
  const name = (req.query.name || req.body?.name || '').toString();
  const safeName = path.basename(name);
  const localFile = path.join(cfg.backupDir, safeName);
  const tempFile = path.join(cfg.backupDir, `.restore-${Date.now()}-${safeName}`);
  const t0 = Date.now();
  let usedTempFile = false;

  try {
    if (!safeName) return res.status(400).json({ ok: false, error: 'name required' });

    let sourceFile = localFile;
    if (!await fileExists(localFile)) {
      if (!hasR2Config(cfg)) {
        return res.status(404).json({ ok: false, error: 'backup not found' });
      }

      await ensureDir(cfg.backupDir);
      const downloaded = await downloadR2BackupToFile(cfg, safeName, tempFile);
      if (!downloaded) {
        return res.status(404).json({ ok: false, error: 'backup not found' });
      }
      sourceFile = tempFile;
      usedTempFile = true;
      console.log(`[restore] pulled from R2: ${safeName}`);
    }

    const restoreTargetDir = await resolveRestoreTargetDir(sourceFile, cfg);
    await ensureDir(restoreTargetDir);
    await tar.x({ file: sourceFile, cwd: restoreTargetDir });
    console.log(`[restore] done name=${safeName} cwd=${restoreTargetDir} time=${Date.now() - t0}ms`);
    res.json({ ok: true, restoreTargetDir });
  } catch (e) {
    console.error('[restore] error:', e && e.stack || e);
    res.status(500).json({ ok: false, error: e.message });
  } finally {
    if (usedTempFile) {
      await fsp.rm(tempFile, { force: true }).catch(() => { });
    }
  }
});

app.delete('/delete', async (req, res) => {
  const cfg = getConfig();

  try {
    const name = (req.query.name || '').toString();
    if (!name) return res.status(400).json({ ok: false, error: 'name required' });

    const safeName = path.basename(name);
    const localFile = path.join(cfg.backupDir, safeName);
    let deletedLocal = false;
    let deletedRemote = false;

    if (await fileExists(localFile)) {
      await fsp.unlink(localFile);
      deletedLocal = true;
    }

    if (hasR2Config(cfg)) {
      await deleteR2Backup(cfg, safeName);
      deletedRemote = true;
    }

    if (!deletedLocal && !deletedRemote) {
      return res.status(404).json({ ok: false, error: 'backup not found' });
    }

    console.log(`[delete] done name=${safeName} local=${deletedLocal} remote=${deletedRemote}`);
    res.json({ ok: true, local: deletedLocal, remote: deletedRemote });
  } catch (e) {
    console.error('[delete] error:', e && e.stack || e);
    res.status(500).json({ ok: false, error: e.message });
  }
});

app.get('/config', (req, res) => {
  try {
    const cfg = getConfig();
    res.json({
      ok: true,
      config: {
        port: cfg.port,
        bindHost: cfg.bindHost,
        dataDir: cfg.dataDir,
        backupDir: cfg.backupDir,
        user: cfg.user,
        hasPassword: !!cfg.pass,
        r2AccountId: cfg.r2AccountId,
        r2Bucket: cfg.r2Bucket,
        r2AccessKeyId: cfg.r2AccessKeyId,
        r2Prefix: cfg.r2Prefix,
        hasR2SecretAccessKey: !!cfg.r2SecretAccessKey,
        r2Configured: hasR2Config(cfg)
      }
    });
  } catch (e) {
    console.error('[config] get error:', e && e.stack || e);
    res.status(500).json({ ok: false, error: e.message });
  }
});

app.post('/config', async (req, res) => {
  try {
    const {
      port,
      bindHost,
      dataDir,
      backupDir,
      user,
      pass,
      r2AccountId,
      r2Bucket,
      r2AccessKeyId,
      r2SecretAccessKey,
      r2Prefix
    } = req.body || {};

    const currentCfg = getConfig();
    const newCfg = normalizeEmbeddedConfig({
      ...currentCfg,
      port: typeof port === 'number' ? port : (parseInt(port, 10) || currentCfg.port),
      bindHost: bindHost || currentCfg.bindHost || '127.0.0.1',
      dataDir: dataDir || currentCfg.dataDir,
      backupDir: backupDir || currentCfg.backupDir,
      user: user !== undefined ? user : currentCfg.user,
      pass: pass !== undefined && pass !== '' ? pass : currentCfg.pass,
      r2AccountId: r2AccountId !== undefined ? String(r2AccountId).trim() : currentCfg.r2AccountId,
      r2Bucket: r2Bucket !== undefined ? String(r2Bucket).trim() : currentCfg.r2Bucket,
      r2AccessKeyId: r2AccessKeyId !== undefined ? String(r2AccessKeyId).trim() : currentCfg.r2AccessKeyId,
      r2SecretAccessKey: r2SecretAccessKey !== undefined && r2SecretAccessKey !== ''
        ? String(r2SecretAccessKey).trim()
        : currentCfg.r2SecretAccessKey,
      r2Prefix: r2Prefix !== undefined ? normalizeR2Prefix(String(r2Prefix)) : currentCfg.r2Prefix,
      r2Region: 'auto'
    });

    await saveConfig(newCfg);
    console.log(`[config] saved: port=${newCfg.port}, dataDir=${newCfg.dataDir}, bucket=${newCfg.r2Bucket}, prefix=${newCfg.r2Prefix}`);
    res.json({ ok: true });
  } catch (e) {
    console.error('[config] save error:', e && e.stack || e);
    res.status(500).json({ ok: false, error: e.message });
  }
});

app.post('/restart', (req, res) => {
  if (SILLYDROID_EMBEDDED || process.env.ST_REMOTE_BACKUP_DISABLE_PM2_RESTART === '1') {
    console.log('[restart] disabled in SillyDroid embedded runtime');
    return res.status(409).json({
      ok: false,
      error: 'Restart is disabled in the SillyDroid embedded runtime. Restart SillyDroid to apply port changes.'
    });
  }

  console.log('[restart] Restarting service via PM2...');
  res.json({ ok: true, message: 'Service is restarting...' });

  setTimeout(() => {
    const child = spawn('pm2', ['restart', 'st-backup', '--update-env'], {
      detached: true,
      stdio: 'ignore'
    });
    child.unref();
  }, 500);
});

app.listen(PORT, BIND_HOST, () => {
  console.log(`[st-remote-backup] listening on ${BIND_HOST}:${PORT}, DATA_DIR=${startupConfig.dataDir}, BACKUP_DIR=${startupConfig.backupDir}`);
});
