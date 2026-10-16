const express = require('express');
const fs = require('fs');
const fsp = require('fs').promises;
const path = require('path');
const tar = require('tar');
const basicAuth = require('basic-auth');
const crypto = require('crypto');
const { spawn } = require('child_process');
const { Readable, Transform } = require('stream');
const { pipeline } = require('stream/promises');
const {
  EMPTY_SHA256,
  normalizeR2Prefix,
  hasR2Config,
  signedR2Fetch,
  readR2Error
} = require('./r2-client');

const APP_DIR = process.env.APP_DIR || path.resolve(__dirname);
const CONFIG_FILE = path.join(APP_DIR, 'config.json');
const CONFIG_BACKUP_FILE = `${CONFIG_FILE}.bak`;
const DISPLAY_TIMEZONE = 'Asia/Shanghai';
const SILLYDROID_EMBEDDED = process.env.SILLYDROID_EMBEDDED === '1';
const IMPORT_BIND_HOST = '127.0.0.1';
const IMPORT_DEFAULT_PORT = 8788;
const IMPORT_BODY_LIMIT = process.env.ST_IMPORT_BODY_LIMIT || '5mb';
const UPLOAD_MAX_BYTES = parseInt(process.env.ST_UPLOAD_MAX_BYTES || '', 10) || 4 * 1024 * 1024 * 1024;
const RESTORE_SNAPSHOT_PREFIX = 'pre-restore-';
const RESTORE_SNAPSHOT_KEEP = 2;
const RESTORE_STAGING_MARKER = '.st-restore-staging-';
const JOB_HISTORY_MAX = 20;

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
    writeConfigFileSync(withToken);
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

  const data = readConfigFileWithRecovery();
  if (data) {
    return ensureImportToken(normalizeEmbeddedConfig({ ...defaults, ...data }));
  }

  return ensureImportToken(normalizeEmbeddedConfig(defaults));
}

function readJsonObjectFileSync(file) {
  const parsed = JSON.parse(fs.readFileSync(file, 'utf8'));
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new Error('config root must be a JSON object');
  }
  return parsed;
}

// config.json 里有 R2 密钥和导入 token：解析失败时绝不能直接回退默认值再写回，
// 否则一次断电截断就会永久丢配置。坏文件改名留证，再尝试上一份完好的 .bak。
function readConfigFileWithRecovery() {
  if (!fs.existsSync(CONFIG_FILE)) {
    return null;
  }

  try {
    return readJsonObjectFileSync(CONFIG_FILE);
  } catch (error) {
    console.error(`[config] config.json is unreadable: ${error.message}`);
  }

  const corruptFile = `${CONFIG_FILE}.corrupt-${timestampForFile()}`;
  try {
    fs.renameSync(CONFIG_FILE, corruptFile);
    console.error(`[config] moved unreadable config to ${path.basename(corruptFile)}`);
  } catch (error) {
    console.error(`[config] failed to preserve unreadable config: ${error.message}`);
  }

  if (!fs.existsSync(CONFIG_BACKUP_FILE)) {
    return null;
  }

  try {
    const recovered = readJsonObjectFileSync(CONFIG_BACKUP_FILE);
    writeConfigFileSync(recovered);
    console.error('[config] recovered config.json from config.json.bak');
    return recovered;
  } catch (error) {
    console.error(`[config] config.json.bak is unreadable too: ${error.message}`);
    return null;
  }
}

function writeConfigFileSync(cfg) {
  const dir = path.dirname(CONFIG_FILE);
  fs.mkdirSync(dir, { recursive: true });
  const temp = path.join(dir, `.tmp-${path.basename(CONFIG_FILE)}-${process.pid}-${Date.now()}`);
  try {
    const fd = fs.openSync(temp, 'w', 0o600);
    try {
      fs.writeFileSync(fd, JSON.stringify(cfg, null, 2), 'utf8');
      fs.fsyncSync(fd);
    } finally {
      fs.closeSync(fd);
    }

    // 只把能正常解析的旧文件留作 .bak，避免用坏文件覆盖掉最后一份好配置。
    try {
      readJsonObjectFileSync(CONFIG_FILE);
      fs.copyFileSync(CONFIG_FILE, CONFIG_BACKUP_FILE);
    } catch {
      // 旧文件不存在或已损坏时保留现有 .bak。
    }

    fs.renameSync(temp, CONFIG_FILE);
  } catch (error) {
    fs.rmSync(temp, { force: true });
    throw error;
  }
}

async function saveConfig(cfg) {
  writeConfigFileSync(cfg);
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

function isRestoreSnapshotName(name) {
  return String(name || '').startsWith(RESTORE_SNAPSHOT_PREFIX);
}

function getRestoreSnapshotName(date = new Date()) {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: DISPLAY_TIMEZONE,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hourCycle: 'h23'
  }).formatToParts(date);
  const v = Object.fromEntries(parts.map((part) => [part.type, part.value]));
  return `${RESTORE_SNAPSHOT_PREFIX}${v.year}${v.month}${v.day}-${v.hour}${v.minute}${v.second}.tar.gz`;
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

function getBackupObjectKey(name, cfg) {
  const prefix = normalizeR2Prefix(cfg.r2Prefix);
  return prefix ? `${prefix}/${name}` : name;
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
    // 恢复前快照只在本地保存，由 pruneRestoreSnapshots 单独控制数量。
    if (isRestoreSnapshotName(file.name)) continue;
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

// strict 模式下 gzip/tar 截断或损坏会直接抛错，顺带完成完整性校验。
async function listTarEntries(archivePath) {
  const entries = [];
  await tar.t({
    file: archivePath,
    strict: true,
    onentry: (entry) => {
      const normalized = normalizeArchiveEntryPath(entry.path);
      if (normalized) entries.push(normalized);
    }
  });
  return entries;
}

async function validateBackupArchive(archivePath) {
  let entries;
  try {
    entries = await listTarEntries(archivePath);
  } catch (error) {
    throw httpError(400, `backup archive is damaged: ${error.message}`);
  }
  if (!entries.some((entry) => entry !== '.')) {
    throw httpError(400, 'backup archive is empty');
  }
  return entries;
}

function archiveTopLevelNames(entries) {
  const names = new Set();
  for (const entry of entries) {
    const top = entry.split('/')[0];
    if (top && top !== '.' && top !== '..') names.add(top);
  }
  return Array.from(names);
}

async function lstatOrNull(target) {
  return fsp.lstat(target).catch(() => null);
}

async function movePath(from, to) {
  try {
    await fsp.rename(from, to);
  } catch (error) {
    if (error.code !== 'EXDEV') throw error;
    await fsp.cp(from, to, { recursive: true, force: true, verbatimSymlinks: true });
    await fsp.rm(from, { recursive: true, force: true });
  }
}

// 与原来直接 tar.x 到数据目录的语义保持一致：覆盖备份里有的文件，不删除备份里没有的文件。
// 区别在于文件已经完整解压到暂存目录，这里基本只剩 rename，不会再出现解压到一半的状态。
async function mergeDirInto(sourceDir, targetDir) {
  await ensureDir(targetDir);
  const entries = await fsp.readdir(sourceDir, { withFileTypes: true });

  for (const entry of entries) {
    const from = path.join(sourceDir, entry.name);
    const to = path.join(targetDir, entry.name);
    const existing = await lstatOrNull(to);

    if (entry.isDirectory()) {
      if (existing && existing.isDirectory()) {
        await mergeDirInto(from, to);
        continue;
      }
      if (existing) await fsp.rm(to, { force: true });
      await movePath(from, to);
      continue;
    }

    if (existing && existing.isDirectory()) {
      await fsp.rm(to, { recursive: true, force: true });
    }
    await movePath(from, to);
  }
}

async function removeStaleRestoreStaging(targetDir) {
  const parent = path.dirname(targetDir);
  const prefix = `${RESTORE_STAGING_MARKER}${path.basename(targetDir)}-`;
  const siblings = await fsp.readdir(parent).catch(() => []);
  for (const name of siblings) {
    if (!name.startsWith(prefix)) continue;
    await fsp.rm(path.join(parent, name), { recursive: true, force: true }).catch((error) => {
      console.error(`[restore] failed to remove stale staging ${name}: ${error.message}`);
    });
  }
}

async function pruneRestoreSnapshots(backupDir) {
  const files = await fsp.readdir(backupDir, { withFileTypes: true }).catch(() => []);
  const snapshots = files
    .filter((file) => file.isFile() && isBackupFileName(file.name) && isRestoreSnapshotName(file.name))
    .map((file) => file.name)
    .sort()
    .reverse();

  for (const name of snapshots.slice(RESTORE_SNAPSHOT_KEEP)) {
    await deleteLocalBackup(backupDir, name, 'pruned old pre-restore snapshot');
  }
}

// 只打包恢复会覆盖到的顶层条目，过滤规则与日常备份一致，这样快照能完整回滚这次恢复。
async function createRestoreSnapshot(cfg, targetDir, topLevelNames) {
  const existing = [];
  for (const name of topLevelNames) {
    if (await lstatOrNull(path.join(targetDir, name))) existing.push(name);
  }
  if (!existing.length) return '';

  await ensureDir(cfg.backupDir);
  const name = getRestoreSnapshotName();
  const out = path.join(cfg.backupDir, name);
  const tempOut = `${out}.partial`;
  const t0 = Date.now();

  try {
    await tar.c({
      gzip: true,
      gzipOptions: { level: 1 },
      file: tempOut,
      cwd: targetDir,
      filter: (entryPath) => shouldInclude(entryPath)
    }, existing);
    await replaceFile(tempOut, out);
  } catch (error) {
    await fsp.rm(tempOut, { force: true }).catch(() => { });
    throw new Error(`failed to create pre-restore snapshot: ${error.message}`);
  }

  const stat = await fsp.stat(out);
  console.log(`[restore] snapshot saved name=${name} size=${(stat.size / 1048576).toFixed(2)}MB time=${Date.now() - t0}ms`);
  await pruneRestoreSnapshots(cfg.backupDir);
  return name;
}

async function resolveRestoreTargetDir(entries, cfg) {
  if (!SILLYDROID_EMBEDDED) return cfg.dataDir;

  const tavernRoot = process.env.TAVERN_DATA_ROOT || '';
  const tavernDataDir = process.env.DATA_DIR ||
    (tavernRoot ? path.join(tavernRoot, 'data') : cfg.dataDir);
  if (!tavernRoot) return cfg.dataDir;

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

// 备份、恢复、上传等会读写备份目录或数据目录的操作全局互斥；
// 耗时的备份/恢复作为后台任务运行，客户端用 GET /jobs/:id 轮询，避免 HTTP 读超时误判失败。
const jobs = new Map();
let activeOperation = null;

function busyError() {
  return httpError(409, `another backup operation is running: ${activeOperation.type}`);
}

async function runExclusive(type, task) {
  if (activeOperation) throw busyError();
  activeOperation = { type, job: null };
  try {
    return await task();
  } finally {
    activeOperation = null;
  }
}

function trimJobHistory() {
  for (const [id, job] of jobs) {
    if (jobs.size <= JOB_HISTORY_MAX) break;
    if (job.state !== 'running') jobs.delete(id);
  }
}

// reuseRunning 为 true 时，同类任务正在运行就直接复用（自动备份与手动备份撞车时共享同一次结果）。
function startJob(type, task, { reuseRunning = false } = {}) {
  if (activeOperation) {
    if (reuseRunning && activeOperation.type === type && activeOperation.job) {
      return { job: activeOperation.job, reused: true };
    }
    throw busyError();
  }

  const job = {
    id: createUuid(),
    type,
    state: 'running',
    startedAt: new Date().toISOString(),
    finishedAt: null,
    result: null,
    error: null,
    status: null
  };
  jobs.set(job.id, job);
  trimJobHistory();
  activeOperation = { type, job };

  job.promise = (async () => {
    try {
      job.result = await task();
      job.state = 'succeeded';
    } catch (error) {
      job.state = 'failed';
      job.error = error && error.message || String(error);
      job.status = error && error.status || 500;
      console.error(`[job] ${type} failed id=${job.id}:`, error && error.stack || error);
    } finally {
      job.finishedAt = new Date().toISOString();
      activeOperation = null;
    }
  })();

  return { job, reused: false };
}

function publicJob(job) {
  const { promise, status, ...rest } = job;
  return rest;
}

function isAsyncRequest(req) {
  const value = String(req.query.async || '').toLowerCase();
  return value === '1' || value === 'true';
}

// 未带 async=1 时保持旧的同步语义（网页端仍按同步方式调用）。
async function respondWithJob(req, res, type, task, options) {
  let started;
  try {
    started = startJob(type, task, options);
  } catch (error) {
    return res.status(error.status || 500).json({ ok: false, error: error.message });
  }

  const { job, reused } = started;
  if (isAsyncRequest(req)) {
    return res.status(202).json({ ok: true, job: publicJob(job), reused });
  }

  await job.promise;
  if (job.state === 'succeeded') {
    return res.json({ ok: true, ...job.result });
  }
  return res.status(job.status || 500).json({ ok: false, error: job.error });
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
    r2Configured: hasR2Config(cfg),
    busy: activeOperation ? activeOperation.type : null
  });
});

app.get('/jobs/:id', (req, res) => {
  const job = jobs.get(String(req.params.id || ''));
  if (!job) return res.status(404).json({ ok: false, error: 'job not found' });
  res.json({ ok: true, job: publicJob(job) });
});

app.get('/logs', (req, res) => {
  const limit = Math.min(parseInt(req.query.limit || '500', 10), LOG_MAX);
  res.json({ lines: LOG_BUF.slice(-limit) });
});

app.delete('/logs', (req, res) => {
  LOG_BUF.length = 0;
  res.json({ ok: true });
});

function createUploadLimiter(maxBytes, onChunk) {
  let received = 0;
  return new Transform({
    transform(chunk, encoding, callback) {
      received += chunk.length;
      onChunk(received);
      if (received > maxBytes) {
        callback(httpError(413, `upload exceeds ${maxBytes} bytes`));
        return;
      }
      callback(null, chunk);
    }
  });
}

// 先写 .partial，完整校验 tar.gz 后再改名，中断或损坏的上传不会变成一个"看起来正常"的备份。
app.post('/upload', async (req, res) => {
  const cfg = getConfig();
  const safeName = path.basename((req.query.name || '').toString());
  if (!safeName || !isBackupFileName(safeName) || isRestoreSnapshotName(safeName)) {
    return res.status(400).json({ ok: false, error: 'name must be a .tar.gz backup file name' });
  }
  const overwrite = ['1', 'true'].includes(String(req.query.overwrite || '').toLowerCase());
  const declaredLength = parseInt(req.headers['content-length'] || '', 10);
  if (Number.isFinite(declaredLength) && declaredLength > UPLOAD_MAX_BYTES) {
    return res.status(413).json({ ok: false, error: `upload exceeds ${UPLOAD_MAX_BYTES} bytes` });
  }

  try {
    const result = await runExclusive('upload', async () => {
      await ensureDir(cfg.backupDir);
      const out = path.join(cfg.backupDir, safeName);
      if (!overwrite && await fileExists(out)) {
        throw httpError(409, `backup already exists: ${safeName} (pass overwrite=1 to replace)`);
      }

      const tempOut = `${out}.partial`;
      let received = 0;
      try {
        await pipeline(
          req,
          createUploadLimiter(UPLOAD_MAX_BYTES, (count) => { received = count; }),
          fs.createWriteStream(tempOut)
        );
        if (received === 0) throw httpError(400, 'empty upload');
        await validateBackupArchive(tempOut);
        await replaceFile(tempOut, out);
      } catch (error) {
        await fsp.rm(tempOut, { force: true }).catch(() => { });
        throw error;
      }
      return { file: safeName, size: received };
    });

    console.log(`[upload] received: ${safeName} size=${result.size}`);
    res.json({ ok: true, ...result });
  } catch (e) {
    console.error(`[upload] error name=${safeName}:`, e.message);
    if (!res.headersSent) res.status(e.status || 500).json({ ok: false, error: e.message });
  }
});

async function performBackup(cfg) {
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

    return { file: name, warning: warning || undefined };
  } catch (e) {
    await fsp.rm(tempOut, { force: true }).catch(() => { });
    throw e;
  }
}

app.post('/backup', async (req, res) => {
  const cfg = getConfig();
  await respondWithJob(req, res, 'backup', () => performBackup(cfg), { reuseRunning: true });
});

app.post('/upload-r2', async (req, res) => {
  const cfg = getConfig();
  const name = (req.query.name || req.body?.name || '').toString();
  const safeName = path.basename(name);

  try {
    if (!safeName) return res.status(400).json({ ok: false, error: 'name required' });
    if (!hasR2Config(cfg)) return res.status(400).json({ ok: false, error: 'R2 not configured' });

    const result = await runExclusive('upload-r2', async () => {
      const localFile = path.join(cfg.backupDir, safeName);
      if (!await fileExists(localFile)) {
        throw httpError(404, 'local backup not found');
      }

      console.log(`[upload-r2] start name=${safeName}`);
      const uploaded = await uploadBackupToR2WithRetry(cfg, localFile, safeName, 3);
      await pruneLocalBackups(cfg.backupDir, '');
      return uploaded;
    });
    console.log(`[upload-r2] success name=${safeName} attempts=${result.attempts}`);
    res.json({ ok: true, attempts: result.attempts });
  } catch (e) {
    console.error('[upload-r2] error:', e && e.stack || e);
    res.status(e.status || 500).json({ ok: false, error: e.message });
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
        // 备份/恢复进行中不清理本地副本，避免删掉正在读取的文件。
        if (!activeOperation) {
          localItems = await removeUploadedLocalDuplicates(cfg.backupDir, localItems, remoteItems);
        }
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

// 恢复流程：取包 → 完整解压到同级暂存目录（损坏的包在这一步失败，不碰现有数据）
// → 为将被覆盖的顶层条目打恢复前快照 → 把暂存内容 rename 合并进目标目录。
async function performRestore(cfg, safeName) {
  const localFile = path.join(cfg.backupDir, safeName);
  // 临时下载文件不以 .tar.gz 结尾，避免在恢复过程中出现在备份列表里。
  const tempFile = path.join(cfg.backupDir, `.restore-${Date.now()}-${safeName}.download`);
  const t0 = Date.now();
  let usedTempFile = false;
  let stagingDir = '';

  try {
    let sourceFile = localFile;
    if (!await fileExists(localFile)) {
      if (!hasR2Config(cfg)) throw httpError(404, 'backup not found');

      await ensureDir(cfg.backupDir);
      usedTempFile = true;
      const downloaded = await downloadR2BackupToFile(cfg, safeName, tempFile);
      if (!downloaded) throw httpError(404, 'backup not found');
      sourceFile = tempFile;
      console.log(`[restore] pulled from R2: ${safeName}`);
    }

    const entries = await validateBackupArchive(sourceFile);
    const restoreTargetDir = await resolveRestoreTargetDir(entries, cfg);
    await ensureDir(restoreTargetDir);
    await removeStaleRestoreStaging(restoreTargetDir);

    stagingDir = path.join(
      path.dirname(restoreTargetDir),
      `${RESTORE_STAGING_MARKER}${path.basename(restoreTargetDir)}-${Date.now()}`
    );
    await ensureDir(stagingDir);
    await tar.x({ file: sourceFile, cwd: stagingDir, strict: true });
    console.log(`[restore] staged name=${safeName} time=${Date.now() - t0}ms`);

    const snapshot = await createRestoreSnapshot(cfg, restoreTargetDir, archiveTopLevelNames(entries));
    await mergeDirInto(stagingDir, restoreTargetDir);

    console.log(`[restore] done name=${safeName} cwd=${restoreTargetDir} snapshot=${snapshot || '-'} time=${Date.now() - t0}ms`);
    return { restoreTargetDir, snapshot: snapshot || undefined };
  } finally {
    if (stagingDir) {
      await fsp.rm(stagingDir, { recursive: true, force: true }).catch((error) => {
        console.error(`[restore] failed to remove staging dir: ${error.message}`);
      });
    }
    if (usedTempFile) {
      await fsp.rm(tempFile, { force: true }).catch(() => { });
    }
  }
}

app.post('/restore', async (req, res) => {
  const cfg = getConfig();
  const name = (req.query.name || req.body?.name || '').toString();
  const safeName = path.basename(name);
  if (!safeName) return res.status(400).json({ ok: false, error: 'name required' });

  await respondWithJob(req, res, 'restore', () => performRestore(cfg, safeName));
});

app.delete('/delete', async (req, res) => {
  const cfg = getConfig();

  try {
    const name = (req.query.name || '').toString();
    if (!name) return res.status(400).json({ ok: false, error: 'name required' });

    const safeName = path.basename(name);
    const result = await runExclusive('delete', async () => {
      const localFile = path.join(cfg.backupDir, safeName);
      let deletedLocal = false;
      let deletedRemote = false;

      if (await fileExists(localFile)) {
        await fsp.unlink(localFile);
        deletedLocal = true;
      }

      // 恢复前快照只存在本地，不去 R2 删同名对象。
      if (hasR2Config(cfg) && !isRestoreSnapshotName(safeName)) {
        await deleteR2Backup(cfg, safeName);
        deletedRemote = true;
      }

      if (!deletedLocal && !deletedRemote) {
        throw httpError(404, 'backup not found');
      }
      return { local: deletedLocal, remote: deletedRemote };
    });

    console.log(`[delete] done name=${safeName} local=${result.local} remote=${result.remote}`);
    res.json({ ok: true, ...result });
  } catch (e) {
    console.error('[delete] error:', e && e.stack || e);
    res.status(e.status || 500).json({ ok: false, error: e.message });
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

// Express 5 的 listen 回调在绑定失败时也会被调用，必须检查 error，否则端口被占用时会误报 listening。
// 备份端口失败直接退出；导入端口失败只记日志，不连累备份功能。
app.listen(PORT, BIND_HOST, (error) => {
  if (error) {
    console.error(`[st-remote-backup] failed to listen on ${BIND_HOST}:${PORT}: ${error.message}`);
    process.exit(1);
  }
  console.log(`[st-remote-backup] listening on ${BIND_HOST}:${PORT}, DATA_DIR=${startupConfig.dataDir}, BACKUP_DIR=${startupConfig.backupDir}`);
});

createImportApp().listen(JS_IMPORT_PORT, JS_IMPORT_BIND_HOST, (error) => {
  if (error) {
    console.error(`[st-js-import] failed to listen on ${JS_IMPORT_BIND_HOST}:${JS_IMPORT_PORT}: ${error.message}`);
    return;
  }
  console.log(`[st-js-import] listening on ${JS_IMPORT_BIND_HOST}:${JS_IMPORT_PORT}, DATA_DIR=${startupConfig.dataDir}`);
});
