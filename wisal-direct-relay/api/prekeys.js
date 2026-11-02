// POST /api/prekeys — نشر حزمة مفاتيح مسبقة (مفاتيح عامة بس) لجهاز مسجّل.
// الجهاز بيوقّع الحزمة كلها + timestamp بمفتاح الهوية P-256 بتاعه.
const store = require('../lib/sharedStore');
const { publishPreKeyBundle } = require('../lib/prekeys');
const { makeRateLimiter } = require('../lib/limiter');

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

  const result = publishPreKeyBundle(
    store,
    { bundle: body.bundle, timestamp: body.timestamp ? Number(body.timestamp) : undefined, signatureB64: body.signatureB64 },
    Math.floor(Date.now() / 1000),
  );
  res.status(result.ok ? 200 : 400).json(result);
};
