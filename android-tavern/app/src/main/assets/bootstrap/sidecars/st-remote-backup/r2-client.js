const crypto = require('crypto');

// R2（S3 兼容）SigV4 签名客户端，从 server.js 抽出，便于其他 sidecar 模块复用。
const EMPTY_SHA256 = crypto.createHash('sha256').update('').digest('hex');

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
    duplex: options.duplex,
    signal: options.signal
  });
}

async function readR2Error(action, response) {
  const body = await response.text().catch(() => '');
  const msg = body.replace(/\s+/g, ' ').trim().slice(0, 300);
  return new Error(`[r2] ${action} failed (${response.status}): ${msg || response.statusText}`);
}

module.exports = {
  EMPTY_SHA256,
  normalizeR2Prefix,
  getR2Endpoint,
  hasR2Config,
  encodeRfc3986,
  encodeS3Path,
  buildCanonicalQuery,
  buildAmzDate,
  hmac,
  sha256Hex,
  getSigningKey,
  signedR2Fetch,
  readR2Error
};
