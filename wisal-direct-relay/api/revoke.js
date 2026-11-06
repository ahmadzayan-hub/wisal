// POST /api/revoke — جهاز بيلغي نفسه بتوقيع حديث على "revoke:{deviceId}:{timestamp}".
// بعدها حزمة مفاتيحه والمغلفات المستنياه بتتمسح، ومعرّفه بيتحجز للأبد.
const store = require('../lib/sharedStore');
const { revokeDevice } = require('../lib/revocation');
const { makeRateLimiter } = require('../lib/limiter');
const { safeLog } = require('../lib/log');
const { storageErrorReason } = require('../lib/storageError');

const allow = makeRateLimiter({ limit: 30, windowMs: 60000 });

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
    result = await revokeDevice(
      store,
      {
        deviceId: body.deviceId,
        timestamp: body.timestamp ? Number(body.timestamp) : undefined,
        signatureB64: body.signatureB64,
      },
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
