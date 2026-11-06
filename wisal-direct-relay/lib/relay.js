// منطق الـ relay (ADR-002 §5.3): تسجيل جهاز، إيداع مغلف مشفّر، سحب صندوق
// الوارد، وتأكيد التسليم (بيمسح المغلف فورًا — مفيش احتفاظ بعد التسليم).
//
// كل دالة نقية قدر الإمكان: التخزين والوقت بيتحقنوا، فالاختبارات حتمية من
// غير سيرفر أو ساعة حقيقية. كل عملية تعديل بتتحقق بتوقيع صاحب الجهاز —
// السيرفر عمره ما يقبل حاجة من غير إثبات ملكية المفتاح الخاص.

const { verifySignature } = require('./crypto');
const { safeLog } = require('./log');

const MAX_ENVELOPE_TTL_SECONDS = 14 * 24 * 3600; // أقصى احتفاظ (§5.3: انتهاء صريح)
const FETCH_CLOCK_SKEW_SECONDS = 300; // نافذة قبول توقيع "fetch" — يمنع إعادة استخدام قديم

function ok(value) { return { ok: true, ...value }; }
function refused(reason) { return { ok: false, reason }; }

// ---- تسجيل جهاز ----
// إثبات الملكية: توقيع على "wisal-direct-register:{deviceId}" بالمفتاح الخاص.
async function registerDevice(store, { deviceId, publicKeyB64, signatureB64 }, nowEpochSec) {
  if (!deviceId || !publicKeyB64 || !signatureB64) return refused('missing fields');
  const proof = `wisal-direct-register:${deviceId}`;
  if (!verifySignature(publicKeyB64, proof, signatureB64)) return refused('bad signature');

  // إعادة تسجيل بنفس المفتاح مسموحة (idempotent)؛ بمفتاح مختلف مرفوضة —
  // يمنع محاولة انتحال جهاز عن طريق تسجيل مفتاح جديد على نفس المعرّف.
  // putDevice بيدخل لو مش موجود بس ويرجّع المخزّن فعلًا، فالمقارنة آمنة حتى
  // مع طلبين متزامنين.
  const stored = await store.putDevice({ deviceId, publicKeyB64, registeredAtEpochSec: nowEpochSec });
  if (stored.publicKeyB64 !== publicKeyB64) return refused('device id already bound to a different key');
  // معرّف جهاز ملغي محجوز للأبد — حتى بنفس المفتاح.
  if (stored.revokedAtEpochSec) return refused('device revoked');
  safeLog('device_registered', { deviceId, atEpochSec: nowEpochSec });
  return ok({ deviceId });
}

// ---- إيداع مغلف ----
async function submitEnvelope(store, envelope, signatureB64, nowEpochSec) {
  const { senderDeviceId, recipientDeviceId, ciphertextB64, backend, expiresAtEpochSec } = envelope || {};
  if (!senderDeviceId || !recipientDeviceId || !ciphertextB64 || !backend || !expiresAtEpochSec) {
    return refused('missing fields');
  }
  const sender = await store.getDevice(senderDeviceId);
  if (!sender) return refused('sender not registered');

  const proof = `${senderDeviceId}:${recipientDeviceId}:${ciphertextB64}:${expiresAtEpochSec}`;
  if (!verifySignature(sender.publicKeyB64, proof, signatureB64)) return refused('bad signature');
  if (sender.revokedAtEpochSec) return refused('device revoked');

  // المستلم لازم يكون مسجّل ومش ملغي — غير كده المغلف عمره ما هيتسحب، وكان
  // أي حد يقدر يملا التخزين بمغلفات لمعرّفات مش موجودة.
  const recipient = await store.getDevice(recipientDeviceId);
  if (!recipient) return refused('recipient not registered');
  if (recipient.revokedAtEpochSec) return refused('recipient revoked');

  if (expiresAtEpochSec <= nowEpochSec) return refused('already expired');
  if (expiresAtEpochSec - nowEpochSec > MAX_ENVELOPE_TTL_SECONDS) return refused('ttl too long');

  const id = `${senderDeviceId}:${nowEpochSec}:${Math.random().toString(36).slice(2, 10)}`;
  await store.putEnvelope({
    id, senderDeviceId, recipientDeviceId, ciphertextB64, backend,
    expiresAtEpochSec, createdAtEpochSec: nowEpochSec,
  });
  safeLog('envelope_submitted', { senderDeviceId, recipientDeviceId, envelopeId: id, backend, atEpochSec: nowEpochSec });
  return ok({ id });
}

// ---- سحب صندوق الوارد ----
// إثبات الطلب: توقيع على "fetch:{deviceId}:{timestamp}" بمفتاح المستلم نفسه،
// والـ timestamp لازم يكون قريب من وقت السيرفر (يمنع إعادة استخدام توقيع قديم).
async function listInbox(store, { deviceId, timestamp, signatureB64 }, nowEpochSec) {
  if (!deviceId || !timestamp || !signatureB64) return refused('missing fields');
  const device = await store.getDevice(deviceId);
  if (!device) return refused('device not registered');

  const proof = `fetch:${deviceId}:${timestamp}`;
  if (!verifySignature(device.publicKeyB64, proof, signatureB64)) return refused('bad signature');
  if (device.revokedAtEpochSec) return refused('device revoked');
  if (Math.abs(nowEpochSec - Number(timestamp)) > FETCH_CLOCK_SKEW_SECONDS) return refused('stale timestamp');

  await store.sweepExpired(nowEpochSec);
  const items = (await store.listEnvelopesFor(deviceId)).map(
    ({ id, senderDeviceId, ciphertextB64, backend, createdAtEpochSec }) =>
      ({ id, senderDeviceId, ciphertextB64, backend, createdAtEpochSec }),
  );
  return ok({ items });
}

// ---- تأكيد التسليم (بيمسح المغلف فورًا) ----
async function ackDelivery(store, { deviceId, envelopeId, signatureB64 }, nowEpochSec) {
  if (!deviceId || !envelopeId || !signatureB64) return refused('missing fields');
  const device = await store.getDevice(deviceId);
  if (!device) return refused('device not registered');

  const proof = `ack:${envelopeId}`;
  if (!verifySignature(device.publicKeyB64, proof, signatureB64)) return refused('bad signature');
  if (device.revokedAtEpochSec) return refused('device revoked');

  const env = await store.getEnvelope(envelopeId);
  if (!env) return refused('not found');
  if (env.recipientDeviceId !== deviceId) return refused('not your envelope');

  await store.deleteEnvelope(envelopeId);
  safeLog('envelope_delivered', { deviceId, envelopeId, atEpochSec: nowEpochSec });
  return ok({});
}

module.exports = {
  registerDevice, submitEnvelope, listInbox, ackDelivery,
  MAX_ENVELOPE_TTL_SECONDS, FETCH_CLOCK_SKEW_SECONDS,
};
