// نشر وطلب حزم المفاتيح المسبقة (pre-key bundles) — الخطوة اللي بتسمح لجهاز
// يبدأ جلسة Signal (PQXDH) مع جهاز تاني من غير ما الاتنين يبقوا أونلاين.
//
// الـ relay هنا لسه "أنبوب غبي": بيخزّن مفاتيح عامة بس، وعمره ما بيشوف مفتاح
// خاص ولا رسالة. التحقق من توقيعات Signal نفسها (signed pre-key وKyber) شغل
// العميل عن طريق libsignal وقت بناء الجلسة — السيرفر بيتحقق بس إن الناشر هو
// صاحب الجهاز المسجّل (توقيع P-256 زي باقي العمليات).
//
// قواعد مهمة:
// - المفتاح الفردي (one-time pre-key) بيتسلّم مرة واحدة بالظبط وبعدين بيتمسح.
// - معرّف المفتاح الفردي لازم يزيد مع كل نشر جديد، عشان إعادة إرسال طلب نشر
//   قديم (replay) ما ترجّعش مفتاح اتستخدم قبل كده.
// - طلب حزمة جهاز تاني لازم يكون من جهاز مسجّل وبتوقيع حديث، عشان محدش مجهول
//   يقدر يستنزف المفاتيح الفردية.

const { verifySignature } = require('./crypto');
const { safeLog } = require('./log');

const PREKEY_CLOCK_SKEW_SECONDS = 300;
const MAX_B64_LENGTH = 4096; // مفتاح Kyber العام ~1568 بايت ≈ 2092 حرف base64
const MAX_PREKEY_ID = 0x7fffffff;
const B64_PATTERN = /^[A-Za-z0-9+/]+={0,2}$/;

// ترتيب الحقول هنا هو العقد مع العميل: نفس ترتيب SignalPreKeyBundleDto.
const BUNDLE_FIELDS = [
  'deviceId', 'registrationId', 'identityKeyB64',
  'signedPreKeyId', 'signedPreKeyPublicB64', 'signedPreKeySignatureB64',
  'kyberPreKeyId', 'kyberPreKeyPublicB64', 'kyberPreKeySignatureB64',
  'oneTimePreKeyId', 'oneTimePreKeyPublicB64',
];
const INT_FIELDS = new Set(['registrationId', 'signedPreKeyId', 'kyberPreKeyId', 'oneTimePreKeyId']);

function ok(value) { return { ok: true, ...value }; }
function refused(reason) { return { ok: false, reason }; }

// النص اللي الجهاز بيوقّعه عند النشر. Base64 ما فيهوش ":" فالفصل آمن.
function publishProof(bundle, timestamp) {
  return ['wisal-direct-prekeys', timestamp, ...BUNDLE_FIELDS.map((f) => bundle[f])].join(':');
}

function claimProof(requesterDeviceId, targetDeviceId, timestamp) {
  return `claim-prekeys:${requesterDeviceId}:${targetDeviceId}:${timestamp}`;
}

function validateBundle(bundle) {
  if (!bundle || typeof bundle !== 'object') return 'missing bundle';
  for (const field of BUNDLE_FIELDS) {
    const value = bundle[field];
    if (INT_FIELDS.has(field)) {
      if (!Number.isInteger(value) || value < 1 || value > MAX_PREKEY_ID) return `invalid ${field}`;
    } else if (field === 'deviceId') {
      if (typeof value !== 'string' || !value) return 'invalid deviceId';
    } else if (typeof value !== 'string' || !value || value.length > MAX_B64_LENGTH || !B64_PATTERN.test(value)) {
      return `invalid ${field}`;
    }
  }
  return null;
}

function sameBundle(a, b) {
  return BUNDLE_FIELDS.every((f) => a[f] === b[f]);
}

// ---- نشر حزمة ----
async function publishPreKeyBundle(store, { bundle, timestamp, signatureB64 }, nowEpochSec) {
  if (!bundle || !timestamp || !signatureB64) return refused('missing fields');
  const invalid = validateBundle(bundle);
  if (invalid) return refused(invalid);

  const device = await store.getDevice(bundle.deviceId);
  if (!device) return refused('device not registered');
  if (!verifySignature(device.publicKeyB64, publishProof(bundle, timestamp), signatureB64)) {
    return refused('bad signature');
  }
  if (Math.abs(nowEpochSec - Number(timestamp)) > PREKEY_CLOCK_SKEW_SECONDS) return refused('stale timestamp');

  // نفس الحزمة اللي لسه ما اتسلّمتش: إعادة محاولة آمنة (idempotent).
  const current = await store.getPreKeyBundle(bundle.deviceId);
  if (current && !current.oneTimePreKeyClaimed && sameBundle(current, bundle)) {
    return ok({ deviceId: bundle.deviceId, oneTimePreKeyId: bundle.oneTimePreKeyId });
  }
  const stored = {};
  for (const f of BUNDLE_FIELDS) stored[f] = bundle[f];
  // شرط "المعرّف لازم يزيد" بيتطبّق جوه التخزين ذرّيًا (نشرين متزامنين).
  const applied = await store.putPreKeyBundle(
    bundle.deviceId, { ...stored, publishedAtEpochSec: nowEpochSec, oneTimePreKeyClaimed: false },
  );
  if (!applied) return refused('one-time pre-key id must be higher than any previously published id');
  safeLog('prekeys_published', { deviceId: bundle.deviceId, atEpochSec: nowEpochSec });
  return ok({ deviceId: bundle.deviceId, oneTimePreKeyId: bundle.oneTimePreKeyId });
}

// ---- طلب حزمة جهاز تاني (بيستهلك المفتاح الفردي) ----
async function claimPreKeyBundle(store, { requesterDeviceId, targetDeviceId, timestamp, signatureB64 }, nowEpochSec) {
  if (!requesterDeviceId || !targetDeviceId || !timestamp || !signatureB64) return refused('missing fields');
  if (requesterDeviceId === targetDeviceId) return refused('cannot claim your own bundle');

  const requester = await store.getDevice(requesterDeviceId);
  if (!requester) return refused('device not registered');
  const proof = claimProof(requesterDeviceId, targetDeviceId, timestamp);
  if (!verifySignature(requester.publicKeyB64, proof, signatureB64)) return refused('bad signature');
  if (Math.abs(nowEpochSec - Number(timestamp)) > PREKEY_CLOCK_SKEW_SECONDS) return refused('stale timestamp');

  if (!(await store.getPreKeyBundle(targetDeviceId))) return refused('no bundle published');
  const bundle = await store.claimPreKeyBundle(targetDeviceId);
  // مفيش مفتاح فردي متاح: نرفض بدل ما نسلّم حزمة ناقصة أو نعيد استخدام مفتاح.
  // العميل الهدف لازم ينشر حزمة جديدة.
  if (!bundle) return refused('no one-time pre-key available');

  const out = {};
  for (const f of BUNDLE_FIELDS) out[f] = bundle[f];
  safeLog('prekeys_claimed', { deviceId: requesterDeviceId, targetDeviceId, atEpochSec: nowEpochSec });
  return ok({ bundle: out });
}

module.exports = {
  publishPreKeyBundle, claimPreKeyBundle, publishProof, claimProof,
  BUNDLE_FIELDS, PREKEY_CLOCK_SKEW_SECONDS,
};
