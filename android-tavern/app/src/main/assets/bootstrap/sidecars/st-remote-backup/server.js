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
const IMPORT_BIND_HOST = '127.0.0.1';
const IMPORT_DEFAULT_PORT = 8788;
const IMPORT_BODY_LIMIT = process.env.ST_IMPORT_BODY_LIMIT || '5mb';

function defaultDataDir() {
  return process.env.DATA_DIR ||
    (SILLYDROID_EMBEDDED && process.env.TAVERN_DATA_ROOT
      ? path.join(process.env.TAVERN_DATA_ROOT, 'data')
      : process.env.TAVERN_DATA_ROOT) ||
    (SILLYDROID_EMBEDDED ? path.join(APP_DIR, 'data') : '/root/sillytavern/data');
}

function parsePort(value, fallback) {
  const parsed = parseInt(value, 10);
  return Number.isInteger(parsed) && parsed > 0 && parsed < 65536 ? parsed : fallback;
}

function generateImportToken() {
  return crypto.randomBytes(32)
    .toString('base64')
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/g, '');
}

function ensureImportToken(cfg) {
  const token = String(cfg.importToken || '').trim();
  if (token) {
    return { ...cfg, importToken: token };
  }

  const withToken = { ...cfg, importToken: generateImportToken() };
  try {
    fs.mkdirSync(path.dirname(CONFIG_FILE), { recursive: true });
    fs.writeFileSync(CONFIG_FILE, JSON.stringify(withToken, null, 2), 'utf8');
  } catch (error) {
    console.error('[config] Failed to persist import token:', error.message);
  }
  return withToken;
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
    importPort: parsePort(process.env.ST_IMPORT_PORT || process.env.IMPORT_PORT || IMPORT_DEFAULT_PORT, IMPORT_DEFAULT_PORT),
    importBindHost: IMPORT_BIND_HOST,
    importToken: process.env.ST_IMPORT_TOKEN || process.env.IMPORT_TOKEN || '',
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
      return ensureImportToken(normalizeEmbeddedConfig({ ...defaults, ...data }));
    }
  } catch (e) {
    console.error('[config] Failed to load config.json:', e.message);
  }

  return ensureImportToken(normalizeEmbeddedConfig(defaults));
}

async function saveConfig(cfg) {
  await ensureDir(path.dirname(CONFIG_FILE));
  await fsp.writeFile(CONFIG_FILE, JSON.stringify(cfg, null, 2), 'utf8');
}

function getConfig() {
  const cfg = loadConfig();
  return {
    ...cfg,
    port: parsePort(cfg.port, 8787),
    importPort: parsePort(cfg.importPort, IMPORT_DEFAULT_PORT),
    importBindHost: IMPORT_BIND_HOST,
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
    const deleted = await deleteLocalBackup(backupDir, file.name, 'auto-deleted local backup');
    if (!deleted) {
      console.error(`[backup] failed to auto-delete local backup: ${file.name}`);
    }
  }
}

async function deleteLocalBackup(backupDir, name, reason = 'deleted local backup') {
  const safeName = path.basename(name);
  if (!safeName || !isBackupFileName(safeName)) return false;

  const filePath = path.join(backupDir, safeName);
  try {
    if (!await fileExists(filePath)) return false;
    await fsp.unlink(filePath);
    console.log(`[backup] ${reason}: ${safeName}`);
    return true;
  } catch (error) {
    console.error(`[backup] failed to delete local backup ${safeName}: ${error.message}`);
    return false;
  }
}

async function removeUploadedLocalDuplicates(backupDir, localItems, remoteItems) {
  if (!remoteItems.length || !localItems.length) return localItems;

  const remoteByName = new Map(remoteItems.map((item) => [item.name, item]));
  const kept = [];
  for (const localItem of localItems) {
    const remoteItem = remoteByName.get(localItem.name);
    if (!remoteItem) {
      kept.push(localItem);
      continue;
    }

    const remoteTime = new Date(remoteItem.mtime).getTime();
    const localTime = new Date(localItem.mtime).getTime();
    if (Number.isFinite(remoteTime) && Number.isFinite(localTime) && remoteTime >= localTime) {
      const deleted = await deleteLocalBackup(backupDir, localItem.name, 'removed local duplicate after R2 upload');
      if (!deleted) {
        kept.push(localItem);
      }
      continue;
    }

    kept.push(localItem);
  }

  return kept;
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

function isLoopbackAddress(value) {
  const address = String(value || '').trim();
  return address === '127.0.0.1' ||
    address === '::1' ||
    address === '::ffff:127.0.0.1' ||
    address.startsWith('127.');
}

function isLoopbackRequest(req) {
  return isLoopbackAddress(req.socket?.remoteAddress) || isLoopbackAddress(req.ip);
}

function timingSafeEqualString(left, right) {
  const leftBuffer = Buffer.from(String(left || ''), 'utf8');
  const rightBuffer = Buffer.from(String(right || ''), 'utf8');
  if (leftBuffer.length !== rightBuffer.length) {
    return false;
  }
  return crypto.timingSafeEqual(leftBuffer, rightBuffer);
}

function importAuthGuard(req, res, next) {
  const cfg = getConfig();
  const token = String(cfg.importToken || '').trim();
  const authorization = String(req.get('authorization') || '');
  const match = authorization.match(/^Bearer\s+(.+)$/i);
  const provided = match ? match[1].trim() : '';

  if (!token || !provided || !timingSafeEqualString(provided, token)) {
    return res.status(401).json({ ok: false, error: 'invalid import token' });
  }

  return next();
}

function importLoopbackGuard(req, res, next) {
  if (!isLoopbackRequest(req)) {
    return res.status(403).json({ ok: false, error: 'import API is only available from 127.0.0.1' });
  }
  return next();
}

function createUuid() {
  if (typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }

  const bytes = crypto.randomBytes(16);
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = bytes.toString('hex');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

function timestampForFile(date = new Date()) {
  return date.toISOString().replace(/[^\d]/g, '').slice(0, 14);
}

function httpError(status, message) {
  const error = new Error(message);
  error.status = status;
  return error;
}

function validateImportFilename(value) {
  const filename = String(value || '').trim();
  if (!filename) {
    throw httpError(400, 'filename is required');
  }
  if (filename !== path.basename(filename) || filename.includes('/') || filename.includes('\\') || filename.includes('..')) {
    throw httpError(400, 'filename must be a plain file name');
  }
  if (!/^[A-Za-z0-9._-]+\.js$/i.test(filename)) {
    throw httpError(400, 'filename must contain only A-Z, 0-9, dot, underscore or dash, and end with .js');
  }
  if (filename.length > 160) {
    throw httpError(400, 'filename is too long');
  }
  return filename;
}

function scriptNameFromFilename(filename) {
  return filename.replace(/\.js$/i, '');
}

function normalizeScriptDisplayName(value) {
  const displayName = String(value || '').trim();
  if (!displayName) return '';
  if (displayName.length > 120) {
    throw httpError(400, 'displayName is too long');
  }
  if (/[\u0000-\u001f\u007f]/.test(displayName)) {
    throw httpError(400, 'displayName contains control characters');
  }
  return displayName;
}

function defaultScriptButton() {
  return { enabled: true, buttons: [] };
}

function defaultScriptExportWith() {
  return { data: true, button: true };
}

function isScriptTreeScript(value) {
  return value && typeof value === 'object' && (value.type === undefined || value.type === 'script');
}

function findScriptMatches(scriptTrees, predicate) {
  const matches = [];
  const visit = (items) => {
    if (!Array.isArray(items)) return;
    items.forEach((item, index) => {
      if (!item || typeof item !== 'object') return;
      if (item.type === 'folder' && Array.isArray(item.scripts)) {
        visit(item.scripts);
        return;
      }
      if (isScriptTreeScript(item) && predicate(item)) {
        matches.push({ array: items, index, script: item });
      }
    });
  };
  visit(scriptTrees);
  return matches;
}

function removeMatchingScripts(scriptTrees, predicate, keepId) {
  let removed = 0;
  const filterItems = (items) => {
    if (!Array.isArray(items)) return;
    for (let index = items.length - 1; index >= 0; index -= 1) {
      const item = items[index];
      if (!item || typeof item !== 'object') continue;
      if (item.type === 'folder' && Array.isArray(item.scripts)) {
        filterItems(item.scripts);
        continue;
      }
      if (isScriptTreeScript(item) && item.id !== keepId && predicate(item)) {
        items.splice(index, 1);
        removed += 1;
      }
    }
  };
  filterItems(scriptTrees);
  return removed;
}

function ensureObject(parent, key) {
  if (!parent[key] || typeof parent[key] !== 'object' || Array.isArray(parent[key])) {
    parent[key] = {};
  }
  return parent[key];
}

async function readSettingsJson(settingsFile) {
  if (!await fileExists(settingsFile)) {
    return {};
  }

  const raw = await fsp.readFile(settingsFile, 'utf8');
  try {
    return raw.trim() ? JSON.parse(raw) : {};
  } catch (error) {
    throw httpError(409, `settings.json is invalid: ${error.message}`);
  }
}

async function writeJsonAtomic(file, data) {
  await ensureDir(path.dirname(file));
  const temp = path.join(path.dirname(file), `.tmp-${path.basename(file)}-${process.pid}-${Date.now()}`);
  try {
    await fsp.writeFile(temp, JSON.stringify(data, null, 2), 'utf8');
    await fsp.rename(temp, file);
  } catch (error) {
    await fsp.rm(temp, { force: true }).catch(() => { });
    throw error;
  }
}

async function backupExistingImportData(cfg, settingsFile, filename, existingScript) {
  const backupDir = path.join(cfg.dataDir, 'backups', 'stdroid-js-import');
  const stamp = timestampForFile();
  await ensureDir(backupDir);

  let settingsBackup = '';
  if (await fileExists(settingsFile)) {
    settingsBackup = path.join(backupDir, `settings.${stamp}.json`);
    await fsp.copyFile(settingsFile, settingsBackup);
  }

  let scriptBackup = '';
  if (existingScript) {
    scriptBackup = path.join(backupDir, `${filename}.${stamp}.script.json`);
    await fsp.writeFile(scriptBackup, JSON.stringify(existingScript, null, 2), 'utf8');
  }

  return {
    settingsBackup,
    scriptBackup
  };
}

function parseEnableParam(value) {
  if (value === undefined) return null;
  const normalized = String(value).trim().toLowerCase();
  if (['1', 'true', 'yes', 'on'].includes(normalized)) return true;
  if (['0', 'false', 'no', 'off'].includes(normalized)) return false;
  throw httpError(400, 'enable must be true or false');
}

function buildImportedScript({ filename, scriptName, displayName, content, existingScript, enable }) {
  const now = new Date().toISOString();
  const existingData = existingScript && existingScript.data && typeof existingScript.data === 'object' && !Array.isArray(existingScript.data)
    ? existingScript.data
    : {};
  return {
    type: 'script',
    enabled: enable === null ? (existingScript ? existingScript.enabled === true : false) : enable,
    name: existingScript?.name || displayName || scriptName,
    id: existingScript?.id || createUuid(),
    content,
    info: `Imported by Stdroid from ${filename} at ${now}`,
    button: existingScript?.button && typeof existingScript.button === 'object' && !Array.isArray(existingScript.button)
      ? existingScript.button
      : defaultScriptButton(),
    data: {
      ...existingData,
      stdroidImportKey: filename,
      stdroidImportedAt: now,
      stdroidDisplayName: existingData.stdroidDisplayName || displayName || undefined,
      stdroidImportSource: 'loopback-api'
    },
    export_with: existingScript?.export_with && typeof existingScript.export_with === 'object' && !Array.isArray(existingScript.export_with)
      ? existingScript.export_with
      : defaultScriptExportWith()
  };
}

let importWriteLock = Promise.resolve();

async function withImportWriteLock(task) {
  const previous = importWriteLock;
  let release;
  importWriteLock = new Promise((resolve) => {
    release = resolve;
  });
  await previous.catch(() => { });
  try {
    return await task();
  } finally {
    release();
  }
}

async function importGlobalJsSlashRunnerScript({ cfg, filename, displayName, content, enable }) {
  const settingsFile = path.join(cfg.dataDir, 'default-user', 'settings.json');
  const scriptName = scriptNameFromFilename(filename);
  const settings = await readSettingsJson(settingsFile);
  const extensionSettings = ensureObject(settings, 'extension_settings');
  const tavernHelper = ensureObject(extensionSettings, 'tavern_helper');
  const scriptSettings = ensureObject(tavernHelper, 'script');
  if (!Array.isArray(scriptSettings.scripts)) {
    scriptSettings.scripts = [];
  }

  const byImportKey = findScriptMatches(
    scriptSettings.scripts,
    (script) => script.data && (script.data.stdroidImportKey === filename || script.data.sillydroidImportKey === filename)
  );
  const byName = byImportKey.length ? [] : findScriptMatches(
    scriptSettings.scripts,
    (script) => script.name === scriptName || (displayName && script.name === displayName)
  );
  const match = byImportKey[0] || byName[0] || null;
  const existingScript = match?.script || null;
  const importedScript = buildImportedScript({
    filename,
    scriptName,
    displayName,
    content,
    existingScript,
    enable
  });

  const backups = await backupExistingImportData(cfg, settingsFile, filename, existingScript);
  if (match) {
    match.array[match.index] = importedScript;
  } else {
    scriptSettings.scripts.push(importedScript);
  }

  const deduped = removeMatchingScripts(
    scriptSettings.scripts,
    (script) => script.data && (script.data.stdroidImportKey === filename || script.data.sillydroidImportKey === filename),
    importedScript.id
  );

  if (enable === true) {
    const enabled = ensureObject(scriptSettings, 'enabled');
    enabled.global = true;
  }

  await writeJsonAtomic(settingsFile, settings);

  return {
    action: match ? 'replaced' : 'created',
    deduped,
    script: importedScript,
    settingsFile,
    backup: backups.scriptBackup || '',
    settingsBackup: backups.settingsBackup || ''
  };
}

function createImportApp() {
  const importApp = express();
  importApp.use(importLoopbackGuard);

  importApp.get('/health', (req, res) => {
    const cfg = getConfig();
    res.json({
      ok: true,
      importPort: cfg.importPort,
      importBindHost: cfg.importBindHost
    });
  });

  importApp.put(
    '/api/import/js-slash-runner/global/:filename',
    importAuthGuard,
    express.raw({ type: '*/*', limit: IMPORT_BODY_LIMIT }),
    async (req, res) => {
      try {
        const cfg = getConfig();
        const filename = validateImportFilename(req.params.filename);
        const enable = parseEnableParam(req.query.enable);
        const displayName = normalizeScriptDisplayName(req.query.displayName || req.get('x-script-display-name'));
        const body = Buffer.isBuffer(req.body) ? req.body : Buffer.from(req.body || '');
        const content = body.toString('utf8');
        if (!content.trim()) {
          return res.status(400).json({ ok: false, error: 'script body is empty' });
        }

        const result = await withImportWriteLock(() => importGlobalJsSlashRunnerScript({
          cfg,
          filename,
          displayName,
          content,
          enable
        }));

        console.log(`[import] js-slash-runner global ${result.action}: ${filename}, name=${result.script.name}, enabled=${result.script.enabled}, deduped=${result.deduped}`);
        res.json({
          ok: true,
          target: 'global',
          filename,
          displayName: result.script.name,
          name: result.script.name,
          id: result.script.id,
          enabled: result.script.enabled,
          action: result.action,
          deduped: result.deduped,
          path: `${result.settingsFile}#extension_settings.tavern_helper.script.scripts`,
          backup: result.backup || undefined,
          settingsBackup: result.settingsBackup || undefined,
          reloadRequired: true
        });
      } catch (error) {
        const status = error.status || 500;
        console.error('[import] error:', error && error.stack || error);
        res.status(status).json({ ok: false, error: error.message || 'import failed' });
      }
    }
  );

  importApp.use((err, req, res, next) => {
    if (!err) return next();
    const status = err.status || 500;
    return res.status(status).json({ ok: false, error: err.message || 'request failed' });
  });

  return importApp;
}

const startupConfig = getConfig();
const PORT = startupConfig.port;
const BIND_HOST = startupConfig.bindHost || '127.0.0.1';
const JS_IMPORT_PORT = startupConfig.importPort;
const JS_IMPORT_BIND_HOST = startupConfig.importBindHost || IMPORT_BIND_HOST;

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
        const deleted = await deleteLocalBackup(cfg.backupDir, name, 'deleted local backup after R2 upload');
        if (!deleted) {
          warning = `R2 upload succeeded, but local cleanup failed for ${name}`;
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
    let localItems = await listLocalBackups(cfg.backupDir);
    let remoteItems = [];
    let warning = '';

    if (hasR2Config(cfg)) {
      try {
        remoteItems = await listR2Backups(cfg);
        localItems = await removeUploadedLocalDuplicates(cfg.backupDir, localItems, remoteItems);
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
        importPort: cfg.importPort,
        importBindHost: cfg.importBindHost,
        importToken: isLoopbackRequest(req) ? cfg.importToken : '',
        hasImportToken: !!cfg.importToken,
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
      importPort,
      importToken,
      resetImportToken,
      r2AccountId,
      r2Bucket,
      r2AccessKeyId,
      r2SecretAccessKey,
      r2Prefix,
      clearR2Config
    } = req.body || {};

    const currentCfg = getConfig();
    const clearR2 = clearR2Config === true || clearR2Config === 'true';
    const newCfg = normalizeEmbeddedConfig({
      ...currentCfg,
      port: typeof port === 'number' ? port : (parseInt(port, 10) || currentCfg.port),
      bindHost: bindHost || currentCfg.bindHost || '127.0.0.1',
      importPort: parsePort(importPort, currentCfg.importPort || IMPORT_DEFAULT_PORT),
      importBindHost: IMPORT_BIND_HOST,
      importToken: resetImportToken === true || resetImportToken === 'true'
        ? generateImportToken()
        : (importToken !== undefined && String(importToken).trim() !== ''
          ? String(importToken).trim()
          : currentCfg.importToken),
      dataDir: dataDir || currentCfg.dataDir,
      backupDir: backupDir || currentCfg.backupDir,
      user: user !== undefined ? user : currentCfg.user,
      pass: pass !== undefined && pass !== '' ? pass : currentCfg.pass,
      r2AccountId: clearR2 ? '' : (r2AccountId !== undefined ? String(r2AccountId).trim() : currentCfg.r2AccountId),
      r2Bucket: clearR2 ? '' : (r2Bucket !== undefined ? String(r2Bucket).trim() : currentCfg.r2Bucket),
      r2AccessKeyId: clearR2 ? '' : (r2AccessKeyId !== undefined ? String(r2AccessKeyId).trim() : currentCfg.r2AccessKeyId),
      r2SecretAccessKey: clearR2 ? '' : (r2SecretAccessKey !== undefined && r2SecretAccessKey !== ''
        ? String(r2SecretAccessKey).trim()
        : currentCfg.r2SecretAccessKey),
      r2Prefix: clearR2 ? '' : (r2Prefix !== undefined ? normalizeR2Prefix(String(r2Prefix)) : currentCfg.r2Prefix),
      r2Region: 'auto'
    });

    await saveConfig(newCfg);
    console.log(`[config] saved: port=${newCfg.port}, importPort=${newCfg.importPort}, dataDir=${newCfg.dataDir}, bucket=${newCfg.r2Bucket}, prefix=${newCfg.r2Prefix}`);
    res.json({
      ok: true,
      config: {
        importPort: newCfg.importPort,
        importBindHost: newCfg.importBindHost,
        importToken: isLoopbackRequest(req) ? newCfg.importToken : '',
        hasImportToken: !!newCfg.importToken,
        r2AccountId: newCfg.r2AccountId,
        r2Bucket: newCfg.r2Bucket,
        r2AccessKeyId: newCfg.r2AccessKeyId,
        r2Prefix: newCfg.r2Prefix,
        hasR2SecretAccessKey: !!newCfg.r2SecretAccessKey,
        r2Configured: hasR2Config(newCfg)
      }
    });
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

createImportApp().listen(JS_IMPORT_PORT, JS_IMPORT_BIND_HOST, () => {
  console.log(`[st-js-import] listening on ${JS_IMPORT_BIND_HOST}:${JS_IMPORT_PORT}, DATA_DIR=${startupConfig.dataDir}`);
});
