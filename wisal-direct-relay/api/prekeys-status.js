// POST /api/prekeys-status: only the owner may observe their current publication.
// The request is signed and nonce-bound. This endpoint never claims or creates keys.
const store = require('../lib/sharedStore');
const { preKeyPublicationStatus } = require('../lib/prekeysStatus');
const { makeRateLimiter } = require('../lib/limiter');
const { safeLog } = require('../lib/log');
const { storageErrorReason } = require('../lib/storageError');

const allow = makeRateLimiter({ limit: 60, windowMs: 60000 });

module.exports = async function handler(req, res) {
  if (typeof res.setHeader === 'function') res.setHeader('Cache-Control', 'no-store');
  if (req.method !== 'POST') {
    res.status(405).json({ ok: false, error: 'method not allowed' });
    return;
  }
  if (!allow()) {
    res.status(429).json({ ok: false, error: 'rate limit exceeded' });
    return;
  }
  let body = req.body;
  if (typeof body === 'string') { try { body = JSON.parse(body); } catch { body = {}; } }
  body = body || {};

  try {
    const result = await preKeyPublicationStatus(store, {
      deviceId: body.deviceId, timestamp: body.timestamp,
      requestNonce: body.requestNonce, signatureB64: body.signatureB64,
    }, Math.floor(Date.now() / 1000));
    res.status(result.ok ? 200 : 400).json(result);
  } catch (err) {
    safeLog('storage_error', { reason: storageErrorReason(err) });
    res.status(503).json({ ok: false, error: 'relay storage unavailable' });
  }
};
