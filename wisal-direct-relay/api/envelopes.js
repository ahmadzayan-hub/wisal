// POST /api/envelopes — إيداع مغلف مشفّر (ADR-002 §5.3). السيرفر بيشوف
// توجيه + ciphertext معتم بس؛ التوقيع بيثبت إن المُرسِل فعلاً صاحب المفتاح
// الخاص المطابق للمُسجَّل، ومفيش قبول من غير كده.
const store = require('../lib/sharedStore');
const { submitEnvelope } = require('../lib/relay');
const { makeRateLimiter } = require('../lib/limiter');
const { safeLog } = require('../lib/log');
const { storageErrorReason } = require('../lib/storageError');

const allow = makeRateLimiter({ limit: 60, windowMs: 60000 });

module.exports = async function handler(req, res) {
  if (req.method !== 'POST') {
    res.status(405).json({ ok: false, error: 'method not allowed' });
    return;
  }
  if (!allow()) {
    res.status(429).json({ ok: false, error: 'rate limit exceeded — slow down' });
    return;
  }
  let body = req.body;
  if (typeof body === 'string') { try { body = JSON.parse(body); } catch { body = {}; } }
  body = body || {};

  let result;
  try {
    result = await submitEnvelope(
      store,
      {
        senderDeviceId: body.senderDeviceId,
        recipientDeviceId: body.recipientDeviceId,
        ciphertextB64: body.ciphertextB64,
        backend: body.backend,
        expiresAtEpochSec: body.expiresAtEpochSec,
      },
      body.signatureB64,
      Math.floor(Date.now() / 1000),
    );
  } catch (err) {
    // التخزين مش متاح (أو مش متظبط في الإنتاج): 503 من غير تفاصيل داخلية.
    safeLog('storage_error', { reason: storageErrorReason(err) });
    res.status(503).json({ ok: false, error: 'relay storage unavailable' });
    return;
  }
  res.status(result.ok ? 200 : 400).json(result);
};
