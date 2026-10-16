'use strict';

// 远程编辑（测试版）：sidecar 主动连到用户自己的中继，接收 RPC 读写 Tavern 数据目录。
// - 只在设置页完成配对并开启后才连接；解除配对会清掉 token。
// - 路径限定在 Tavern 根目录内，拒绝 secrets.json 与指向目录外的符号链接。
// - 每次写入/删除前把原文件存进 remote-edit-history，支持撤销。
// - 改完请求宿主刷新页面：ST 前端会把内存里的设置写回文件，不刷新的话改动可能被覆盖。

const fs = require('fs');
const fsp = fs.promises;
const path = require('path');
const crypto = require('crypto');
const zlib = require('zlib');

const TICK_INTERVAL_MS = 5000;
const RECONNECT_MIN_MS = 5000;
const RECONNECT_MAX_MS = 60000;
const IDLE_TIMEOUT_MS = 80000;
const MAX_READ_BYTES = 20 * 1024 * 1024;
const MAX_LIST_ENTRIES = 3000;
const HISTORY_KEEP = 200;
const BLOCKED_NAMES = new Set(['secrets.json']);
const LEGACY_CARD_FIELDS = ['name', 'description', 'personality', 'scenario', 'first_mes', 'mes_example', 'tags'];

function rpcError(status, message) {
  const error = new Error(message);
  error.status = status;
  return error;
}

function sha256(buffer) {
  return crypto.createHash('sha256').update(buffer).digest('hex');
}

// ---- PNG 文本块（与 SillyTavern src/character-card-parser.js 行为一致） ----

const PNG_SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

function crc32(buffer) {
  if (typeof zlib.crc32 === 'function') return zlib.crc32(buffer) >>> 0;
  let crc = 0xffffffff;
  for (const byte of buffer) {
    crc ^= byte;
    for (let k = 0; k < 8; k += 1) crc = (crc >>> 1) ^ (0xedb88320 & -(crc & 1));
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function parsePngChunks(buffer) {
  if (buffer.length < 8 || !buffer.subarray(0, 8).equals(PNG_SIGNATURE)) {
    throw rpcError(400, 'not a PNG file');
  }
  const chunks = [];
  let offset = 8;
  while (offset + 12 <= buffer.length) {
    const length = buffer.readUInt32BE(offset);
    const name = buffer.toString('latin1', offset + 4, offset + 8);
    const data = buffer.subarray(offset + 8, offset + 8 + length);
    chunks.push({ name, data });
    offset += 12 + length;
    if (name === 'IEND') break;
  }
  if (!chunks.length || chunks[chunks.length - 1].name !== 'IEND') {
    throw rpcError(400, 'PNG is truncated');
  }
  return chunks;
}

function encodePngChunks(chunks) {
  const parts = [PNG_SIGNATURE];
  for (const chunk of chunks) {
    const header = Buffer.alloc(8);
    header.writeUInt32BE(chunk.data.length, 0);
    header.write(chunk.name, 4, 'latin1');
    const crc = Buffer.alloc(4);
    crc.writeUInt32BE(crc32(Buffer.concat([header.subarray(4), chunk.data])), 0);
    parts.push(header, chunk.data, crc);
  }
  return Buffer.concat(parts);
}

function decodeTextChunk(data) {
  const separator = data.indexOf(0);
  if (separator < 0) return null;
  return { keyword: data.toString('latin1', 0, separator), text: data.toString('latin1', separator + 1) };
}

function encodeTextChunk(keyword, text) {
  return { name: 'tEXt', data: Buffer.concat([Buffer.from(keyword, 'latin1'), Buffer.from([0]), Buffer.from(text, 'latin1')]) };
}

function readCardFromPng(buffer) {
  const texts = parsePngChunks(buffer)
    .filter((chunk) => chunk.name === 'tEXt')
    .map((chunk) => decodeTextChunk(chunk.data))
    .filter(Boolean);
  const pick = texts.find((item) => item.keyword.toLowerCase() === 'ccv3') ||
    texts.find((item) => item.keyword.toLowerCase() === 'chara');
  if (!pick) throw rpcError(400, 'PNG has no character metadata');
  return JSON.parse(Buffer.from(pick.text, 'base64').toString('utf8'));
}

function writeCardToPng(buffer, card) {
  const chunks = parsePngChunks(buffer).filter((chunk) => {
    if (chunk.name !== 'tEXt') return true;
    const text = decodeTextChunk(chunk.data);
    const keyword = text ? text.keyword.toLowerCase() : '';
    return keyword !== 'chara' && keyword !== 'ccv3';
  });
  const json = JSON.stringify(card);
  chunks.splice(-1, 0, encodeTextChunk('chara', Buffer.from(json, 'utf8').toString('base64')));
  const v3 = { ...card, spec: 'chara_card_v3', spec_version: '3.0' };
  chunks.splice(-1, 0, encodeTextChunk('ccv3', Buffer.from(JSON.stringify(v3), 'utf8').toString('base64')));
  return encodePngChunks(chunks);
}

// ---- 主体 ----

function createRemoteEdit({ getConfig, saveConfig, log, logError }) {
  let ws = null;
  let status = 'disabled';
  let lastError = '';
  let connectedAt = null;
  let lastMessageAt = 0;
  let reconnectDelay = RECONNECT_MIN_MS;
  let nextConnectAt = 0;
  let reloadSeq = 0;
  const reloadWaiters = new Set();

  function tavernRoot(cfg) {
    return path.resolve(process.env.TAVERN_DATA_ROOT || cfg.dataDir);
  }

  function historyRoot(cfg) {
    return path.join(cfg.backupDir, 'remote-edit-history');
  }

  async function resolveSafePath(cfg, relPath, { allowRoot = false } = {}) {
    const root = tavernRoot(cfg);
    const normalized = String(relPath || '').replace(/\\/g, '/').replace(/^\/+/, '');
    const target = path.resolve(root, normalized);
    if (target !== root && !target.startsWith(root + path.sep)) throw rpcError(403, 'path escapes Tavern root');
    if (target === root && !allowRoot) throw rpcError(400, 'path required');
    if (normalized.split('/').some((segment) => BLOCKED_NAMES.has(segment.toLowerCase()))) {
      throw rpcError(403, 'access to this file is blocked');
    }

    // 已存在的最深一级父目录做 realpath，防止通过符号链接跳到根目录外。
    let probe = target;
    while (probe !== root) {
      try {
        const real = await fsp.realpath(probe);
        const realRoot = await fsp.realpath(root);
        if (real !== realRoot && !real.startsWith(realRoot + path.sep)) throw rpcError(403, 'path escapes Tavern root');
        break;
      } catch (error) {
        if (error.status) throw error;
        probe = path.dirname(probe);
      }
    }
    return { root, target, rel: path.relative(root, target).split(path.sep).join('/') };
  }

  async function readIfExists(file) {
    try {
      return await fsp.readFile(file);
    } catch (error) {
      if (error.code === 'ENOENT') return null;
      throw error;
    }
  }

  async function writeAtomic(file, buffer) {
    await fsp.mkdir(path.dirname(file), { recursive: true });
    const temp = path.join(path.dirname(file), `.remote-edit-${process.pid}-${Date.now()}-${path.basename(file)}`);
    try {
      await fsp.writeFile(temp, buffer);
      await fsp.rename(temp, file);
    } catch (error) {
      await fsp.rm(temp, { force: true }).catch(() => { });
      throw error;
    }
  }

  async function recordHistory(cfg, rel, action, before) {
    const root = historyRoot(cfg);
    const id = `${new Date().toISOString().replace(/[^\d]/g, '').slice(0, 17)}-${crypto.randomBytes(3).toString('hex')}`;
    const dir = path.join(root, id);
    await fsp.mkdir(dir, { recursive: true });
    if (before) await fsp.writeFile(path.join(dir, 'before'), before);
    await fsp.writeFile(path.join(dir, 'meta.json'), JSON.stringify({
      id,
      path: rel,
      action,
      existed: !!before,
      sha256Before: before ? sha256(before) : null,
      at: new Date().toISOString()
    }, null, 2));

    const entries = (await fsp.readdir(root).catch(() => [])).sort();
    for (const old of entries.slice(0, Math.max(0, entries.length - HISTORY_KEEP))) {
      await fsp.rm(path.join(root, old), { recursive: true, force: true }).catch(() => { });
    }
    return id;
  }

  function requestReload() {
    reloadSeq += 1;
    for (const waiter of reloadWaiters) waiter();
    reloadWaiters.clear();
    log(`[remote-edit] reload requested seq=${reloadSeq}`);
    return reloadSeq;
  }

  function waitReload(since, timeoutMs) {
    if (since < 0 || reloadSeq > since) return Promise.resolve(reloadSeq);
    return new Promise((resolve) => {
      const done = () => {
        clearTimeout(timer);
        reloadWaiters.delete(done);
        resolve(reloadSeq);
      };
      const timer = setTimeout(done, timeoutMs);
      reloadWaiters.add(done);
    });
  }

  function maybeReload(params, result) {
    if (params.reload === false) return result;
    return { ...result, reloadSeq: requestReload() };
  }

  async function writeWithHistory(cfg, params, buffer, action) {
    const { target, rel } = await resolveSafePath(cfg, params.path);
    const before = await readIfExists(target);
    if (params.expectSha256 && (!before || sha256(before) !== params.expectSha256)) {
      throw rpcError(409, 'file changed since it was read (sha256 mismatch)');
    }
    const historyId = await recordHistory(cfg, rel, action, before);
    await writeAtomic(target, buffer);
    log(`[remote-edit] ${action} ${rel} size=${buffer.length} history=${historyId}`);
    return { path: rel, size: buffer.length, sha256: sha256(buffer), historyId };
  }

  const handlers = {
    async info(cfg) {
      const root = tavernRoot(cfg);
      return {
        root,
        dataDir: cfg.dataDir,
        node: process.version,
        userDirExists: fs.existsSync(path.join(root, 'data', 'default-user'))
      };
    },

    async 'fs.list'(cfg, params) {
      const { target, root } = await resolveSafePath(cfg, params.path, { allowRoot: true });
      const depth = Math.min(Math.max(parseInt(params.depth || '1', 10) || 1, 1), 4);
      const entries = [];
      let truncated = false;

      async function walk(dir, level) {
        const items = await fsp.readdir(dir, { withFileTypes: true });
        items.sort((a, b) => a.name.localeCompare(b.name));
        for (const item of items) {
          if (BLOCKED_NAMES.has(item.name.toLowerCase())) continue;
          if (entries.length >= MAX_LIST_ENTRIES) {
            truncated = true;
            return;
          }
          const full = path.join(dir, item.name);
          const stat = await fsp.lstat(full).catch(() => null);
          if (!stat) continue;
          const type = stat.isDirectory() ? 'dir' : stat.isSymbolicLink() ? 'link' : 'file';
          entries.push({
            path: path.relative(root, full).split(path.sep).join('/'),
            type,
            size: stat.size,
            mtime: stat.mtime.toISOString()
          });
          if (type === 'dir' && level < depth) await walk(full, level + 1);
        }
      }

      await walk(target, 1);
      return { entries, truncated };
    },

    async 'fs.read'(cfg, params) {
      const { target, rel } = await resolveSafePath(cfg, params.path);
      const stat = await fsp.stat(target).catch(() => null);
      if (!stat || !stat.isFile()) throw rpcError(404, `file not found: ${rel}`);
      if (stat.size > MAX_READ_BYTES) throw rpcError(413, `file too large: ${stat.size} bytes`);
      const buffer = await fsp.readFile(target);
      const encoding = params.encoding === 'base64' ? 'base64' : 'utf8';
      return {
        path: rel,
        size: buffer.length,
        mtime: stat.mtime.toISOString(),
        sha256: sha256(buffer),
        encoding,
        content: buffer.toString(encoding)
      };
    },

    async 'fs.write'(cfg, params) {
      const encoding = params.encoding === 'base64' ? 'base64' : 'utf8';
      const buffer = Buffer.from(String(params.content ?? ''), encoding);
      if (!params.force && /\.json$/i.test(String(params.path))) {
        try {
          JSON.parse(buffer.toString('utf8'));
        } catch (error) {
          throw rpcError(400, `invalid JSON (pass force to skip): ${error.message}`);
        }
      }
      return maybeReload(params, await writeWithHistory(cfg, params, buffer, 'write'));
    },

    async 'fs.delete'(cfg, params) {
      const { target, rel } = await resolveSafePath(cfg, params.path);
      const stat = await fsp.lstat(target).catch(() => null);
      if (!stat || !stat.isFile()) throw rpcError(404, `file not found: ${rel}`);
      const historyId = await recordHistory(cfg, rel, 'delete', await fsp.readFile(target));
      await fsp.unlink(target);
      log(`[remote-edit] delete ${rel} history=${historyId}`);
      return maybeReload(params, { path: rel, historyId });
    },

    async 'char.read'(cfg, params) {
      const { target, rel } = await resolveSafePath(cfg, params.path);
      const buffer = await readIfExists(target);
      if (!buffer) throw rpcError(404, `file not found: ${rel}`);
      return { path: rel, sha256: sha256(buffer), card: readCardFromPng(buffer) };
    },

    async 'char.write'(cfg, params) {
      if (!params.card || typeof params.card !== 'object') throw rpcError(400, 'card object required');
      const { target, rel } = await resolveSafePath(cfg, params.path);
      const buffer = await readIfExists(target);
      if (!buffer) throw rpcError(404, `file not found: ${rel}`);
      const next = writeCardToPng(buffer, params.card);
      return maybeReload(params, await writeWithHistory(cfg, params, next, 'char.write'));
    },

    // 合并到 card.data，同时同步 ST 仍在读取的顶层旧字段，避免两处内容不一致。
    async 'char.patch'(cfg, params) {
      if (!params.data || typeof params.data !== 'object') throw rpcError(400, 'data object required');
      const { target, rel } = await resolveSafePath(cfg, params.path);
      const buffer = await readIfExists(target);
      if (!buffer) throw rpcError(404, `file not found: ${rel}`);
      const card = readCardFromPng(buffer);
      card.data = { ...(card.data || {}), ...params.data };
      for (const field of LEGACY_CARD_FIELDS) {
        if (Object.prototype.hasOwnProperty.call(params.data, field)) card[field] = params.data[field];
      }
      if (Object.prototype.hasOwnProperty.call(params.data, 'creator_notes')) card.creatorcomment = params.data.creator_notes;
      const next = writeCardToPng(buffer, card);
      const result = await writeWithHistory(cfg, params, next, 'char.patch');
      return maybeReload(params, { ...result, changed: Object.keys(params.data) });
    },

    async 'history.list'(cfg, params) {
      const root = historyRoot(cfg);
      const limit = Math.min(Math.max(parseInt(params.limit || '20', 10) || 20, 1), HISTORY_KEEP);
      const ids = (await fsp.readdir(root).catch(() => [])).sort().reverse().slice(0, limit);
      const items = [];
      for (const id of ids) {
        const meta = await fsp.readFile(path.join(root, id, 'meta.json'), 'utf8').then(JSON.parse).catch(() => null);
        if (meta) items.push(meta);
      }
      return { items };
    },

    async 'history.restore'(cfg, params) {
      const id = String(params.id || '');
      if (!/^[\w-]+$/.test(id)) throw rpcError(400, 'invalid history id');
      const dir = path.join(historyRoot(cfg), id);
      const meta = await fsp.readFile(path.join(dir, 'meta.json'), 'utf8').then(JSON.parse).catch(() => null);
      if (!meta) throw rpcError(404, `history not found: ${id}`);

      const { target, rel } = await resolveSafePath(cfg, meta.path);
      const current = await readIfExists(target);
      const undoId = await recordHistory(cfg, rel, `undo:${id}`, current);
      if (meta.existed) {
        await writeAtomic(target, await fsp.readFile(path.join(dir, 'before')));
      } else {
        await fsp.rm(target, { force: true });
      }
      log(`[remote-edit] restored ${rel} from ${id} (undo record ${undoId})`);
      return maybeReload(params, { path: rel, restoredFrom: id, historyId: undoId });
    },

    async 'ui.reload'() {
      return { reloadSeq: requestReload() };
    }
  };

  async function handleRpc(message) {
    const handler = handlers[message.method];
    try {
      if (!handler) throw rpcError(404, `unknown method: ${message.method}`);
      const result = await handler(getConfig(), message.params || {});
      return { type: 'rpc-result', id: message.id, ok: true, result };
    } catch (error) {
      logError(`[remote-edit] rpc ${message.method} failed: ${error.message}`);
      return { type: 'rpc-result', id: message.id, ok: false, status: error.status || 500, error: error.message };
    }
  }

  // ---- 连接维护 ----

  function wsUrl(baseUrl) {
    const url = new URL(baseUrl.replace(/\/+$/, '') + '/phone');
    url.protocol = url.protocol === 'http:' ? 'ws:' : 'wss:';
    return url.toString();
  }

  function isConfigured(cfg) {
    return !!(cfg.remoteEditEnabled && cfg.remoteEditUrl && cfg.remoteEditDeviceId && cfg.remoteEditToken);
  }

  function closeSocket(reason) {
    if (!ws) return;
    const socket = ws;
    ws = null;
    try {
      socket.close(1000, reason);
    } catch {
      // ignore
    }
  }

  function connect(cfg) {
    if (typeof WebSocket !== 'function') {
      status = 'error';
      lastError = `WebSocket is not available in Node ${process.version}`;
      return;
    }

    status = 'connecting';
    let socket;
    try {
      socket = new WebSocket(wsUrl(cfg.remoteEditUrl));
    } catch (error) {
      status = 'error';
      lastError = error.message;
      scheduleReconnect();
      return;
    }
    ws = socket;
    lastMessageAt = Date.now();

    socket.addEventListener('open', () => {
      socket.send(JSON.stringify({
        type: 'hello',
        deviceId: cfg.remoteEditDeviceId,
        token: cfg.remoteEditToken,
        info: { node: process.version, root: tavernRoot(cfg) }
      }));
    });

    socket.addEventListener('message', async (event) => {
      lastMessageAt = Date.now();
      let message;
      try {
        message = JSON.parse(typeof event.data === 'string' ? event.data : Buffer.from(event.data).toString('utf8'));
      } catch {
        return;
      }

      if (message.type === 'hello-ok') {
        status = 'connected';
        lastError = '';
        connectedAt = new Date().toISOString();
        reconnectDelay = RECONNECT_MIN_MS;
        log('[remote-edit] connected to relay');
      } else if (message.type === 'ping') {
        socket.send(JSON.stringify({ type: 'pong', at: message.at }));
      } else if (message.type === 'rpc') {
        const reply = await handleRpc(message);
        if (socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify(reply));
      }
    });

    socket.addEventListener('close', (event) => {
      if (ws === socket) ws = null;
      connectedAt = null;
      if (event.code === 4003 || event.code === 4005) {
        status = 'error';
        lastError = event.code === 4005 ? 'device revoked on relay, pair again' : 'relay rejected token, pair again';
        logError(`[remote-edit] ${lastError}`);
        nextConnectAt = Date.now() + RECONNECT_MAX_MS * 5;
        return;
      }
      if (isConfigured(getConfig())) {
        status = 'disconnected';
        if (event.reason) lastError = `closed: ${event.code} ${event.reason}`;
        scheduleReconnect();
      }
    });

    socket.addEventListener('error', (event) => {
      lastError = event && event.message ? event.message : 'websocket error';
    });
  }

  function scheduleReconnect() {
    nextConnectAt = Date.now() + reconnectDelay;
    reconnectDelay = Math.min(reconnectDelay * 2, RECONNECT_MAX_MS);
  }

  function tick() {
    let cfg;
    try {
      cfg = getConfig();
    } catch {
      return;
    }

    if (!isConfigured(cfg)) {
      if (ws) closeSocket('disabled');
      status = cfg.remoteEditDeviceId ? 'disabled' : 'unpaired';
      return;
    }

    if (ws && status === 'connected' && Date.now() - lastMessageAt > IDLE_TIMEOUT_MS) {
      logError('[remote-edit] relay idle timeout, reconnecting');
      closeSocket('idle');
      status = 'disconnected';
      scheduleReconnect();
      return;
    }

    if (!ws && Date.now() >= nextConnectAt) connect(cfg);
  }

  async function pair(baseUrl, code, name) {
    const url = String(baseUrl || '').trim().replace(/\/+$/, '');
    if (!/^https?:\/\//i.test(url)) throw rpcError(400, 'relay url must start with https://');
    const response = await fetch(`${url}/pair`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ code, name: name || 'stdroid' }),
      signal: AbortSignal.timeout(15000)
    });
    const json = await response.json().catch(() => ({}));
    if (!response.ok || !json.ok) throw rpcError(response.status || 502, json.error || `pair failed: HTTP ${response.status}`);

    closeSocket('re-pair');
    const cfg = getConfig();
    await saveConfig({
      ...cfg,
      remoteEditEnabled: true,
      remoteEditUrl: url,
      remoteEditDeviceId: json.deviceId,
      remoteEditToken: json.token
    });
    status = 'connecting';
    lastError = '';
    nextConnectAt = 0;
    reconnectDelay = RECONNECT_MIN_MS;
    log(`[remote-edit] paired with ${url} device=${json.deviceId}`);
    setTimeout(tick, 50);
    return statusSnapshot();
  }

  async function unpair() {
    const cfg = getConfig();
    await saveConfig({
      ...cfg,
      remoteEditEnabled: false,
      remoteEditDeviceId: '',
      remoteEditToken: ''
    });
    status = 'unpaired';
    closeSocket('unpaired');
    log('[remote-edit] unpaired');
    return statusSnapshot();
  }

  function statusSnapshot() {
    const cfg = getConfig();
    const paired = !!(cfg.remoteEditDeviceId && cfg.remoteEditToken);
    // 未配置时以配置为准，不依赖异步的连接事件（关闭回调可能晚于解除配对）。
    const state = isConfigured(cfg) ? status : (paired ? 'disabled' : 'unpaired');
    return {
      enabled: !!cfg.remoteEditEnabled,
      paired,
      url: cfg.remoteEditUrl || '',
      state,
      lastError,
      connectedAt
    };
  }

  setInterval(tick, TICK_INTERVAL_MS).unref();
  setTimeout(tick, 1000).unref();

  return {
    pair,
    unpair,
    status: statusSnapshot,
    waitReload,
    requestReload,
    // 测试用：不经过 WebSocket 直接调用 RPC 处理逻辑
    handleRpc
  };
}

module.exports = { createRemoteEdit, readCardFromPng, writeCardToPng };
