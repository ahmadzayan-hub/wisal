// إلغاء جهاز (ADR-002: device revocation من البوابات المطلوبة قبل أي ادعاء
// تشفير). الجهاز بيلغي نفسه بتوقيع حديث على "revoke:{deviceId}:{timestamp}".
//
// بعد الإلغاء: حزمة مفاتيحه والمغلفات اللي مستنياه بتتمسح، ومعرّفه بيفضل
// محجوز (محدش يقدر يسجّله تاني ولا يبعت له ولا يطلب مفاتيحه)، والجهاز نفسه
// ما يقدرش يبعت ولا يسحب ولا ينشر.
//
// مش متغطّي هنا: جهاز ضاع أو اتسرق ما يقدرش يلغي نفسه. ده محتاج جهاز تاني
// مربوط بنفس المستخدم أو مفتاح استرداد — مش موجودين لسه (multi-device لاحقًا).

const { verifySignature } = require('./crypto');
const { safeLog } = require('./log');

const REVOKE_CLOCK_SKEW_SECONDS = 300;

function ok(value) { return { ok: true, ...value }; }
function refused(reason) { return { ok: false, reason }; }

function revokeProof(deviceId, timestamp) {
  return `revoke:${deviceId}:${timestamp}`;
}

async function revokeDevice(store, { deviceId, timestamp, signatureB64 }, nowEpochSec) {
  if (!deviceId || !timestamp || !signatureB64) return refused('missing fields');
  const device = await store.getDevice(deviceId);
  if (!device) return refused('device not registered');
  if (!verifySignature(device.publicKeyB64, revokeProof(deviceId, timestamp), signatureB64)) {
    return refused('bad signature');
  }
  if (Math.abs(nowEpochSec - Number(timestamp)) > REVOKE_CLOCK_SKEW_SECONDS) return refused('stale timestamp');

  // إلغاء متكرر: نجاح من غير ما يغيّر حاجة (العميل ممكن يعيد المحاولة).
  const revokedNow = await store.revokeDevice(deviceId, nowEpochSec);
  if (revokedNow) safeLog('device_revoked', { deviceId, atEpochSec: nowEpochSec });
  return ok({ deviceId, revoked: true });
}

module.exports = { revokeDevice, revokeProof, REVOKE_CLOCK_SKEW_SECONDS };
