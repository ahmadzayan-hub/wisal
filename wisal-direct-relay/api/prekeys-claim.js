// POST /api/prekeys-claim — جهاز مسجّل بيطلب حزمة جهاز تاني عشان يبدأ جلسة.
// POST مش GET لأن الطلب بيستهلك المفتاح الفردي (بيغيّر الحالة). الطلب موقّع
// بـ "claim-prekeys:{requester}:{target}:{timestamp}" عشان محدش مجهول يستنزف
// المفاتيح الفردية.
const store = require('../lib/sharedStore');
const { claimPreKeyBundle } = require('../lib/prekeys');
const { makeRateLimiter } = require('../lib/limiter');

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

  const result = claimPreKeyBundle(
    store,
    {
      requesterDeviceId: body.requesterDeviceId,
      targetDeviceId: body.targetDeviceId,
      timestamp: body.timestamp ? Number(body.timestamp) : undefined,
      signatureB64: body.signatureB64,
    },
    Math.floor(Date.now() / 1000),
  );
  res.status(result.ok ? 200 : 400).json(result);
};
