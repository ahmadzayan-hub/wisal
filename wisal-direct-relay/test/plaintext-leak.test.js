// اختبار آلي مطلوب في §12: "امسح السجلات والبيانات المخزّنة بحثًا عن رسائل
// اختبار نصية معروفة" — بيتأكد إن نص عادي (زي لو حصل خطأ في العميل وبعت
// plaintext بالغلط) عمره ما يظهر في تخزين السيرفر ولا في أي سطر لوج، حتى لو
// اتحط قيمته base64-encoded في حقل ciphertextB64 (المكان الوحيد المسموح).
const assert = require('assert');
const { generateKeyPairSync, createSign } = require('crypto');
const { createInMemoryStore } = require('../lib/store');
const { registerDevice, submitEnvelope, listInbox, ackDelivery } = require('../lib/relay');
const { safeLog, ALLOWED_FIELDS } = require('../lib/log');

function makeIdentity() {
  const { publicKey, privateKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  return { publicKeyB64: publicKey.export({ type: 'spki', format: 'der' }).toString('base64'), privateKey };
}
function sign(privateKey, data) {
  const s = createSign('SHA256'); s.update(data); s.end();
  return s.sign(privateKey).toString('base64');
}

(async () => {
const NOW = 1_800_000_000;
const SEEDED_PLAINTEXT = 'وحشتني أوي يا قمر — هذه رسالة اختبار سرّية 12345';

const store = createInMemoryStore();
const alice = makeIdentity();
const bob = makeIdentity();
await registerDevice(store, { deviceId: 'devA', publicKeyB64: alice.publicKeyB64, signatureB64: sign(alice.privateKey, 'wisal-direct-register:devA') }, NOW);
await registerDevice(store, { deviceId: 'devB', publicKeyB64: bob.publicKeyB64, signatureB64: sign(bob.privateKey, 'wisal-direct-register:devB') }, NOW);

// العميل الحقيقي بيبعت ciphertext ناتج تشفير فعلي — هنا بنمثّله بـ base64 عادي
// (المهم: الحقل ده هو المكان الوحيد المسموح يحمل تمثيل للمحتوى، والسيرفر
// بيتعامل معاه كـ opaque bytes ومايفتحوش أبدًا).
const ciphertextB64 = Buffer.from(SEEDED_PLAINTEXT, 'utf8').toString('base64');
const expiresAt = NOW + 3600;
const proof = `devA:devB:${ciphertextB64}:${expiresAt}`;
const submit = await submitEnvelope(
  store,
  { senderDeviceId: 'devA', recipientDeviceId: 'devB', ciphertextB64, backend: 'VODOZEMAC', expiresAtEpochSec: expiresAt },
  sign(alice.privateKey, proof),
  NOW,
);
assert.strictEqual(submit.ok, true);

await listInbox(store, { deviceId: 'devB', timestamp: NOW + 1, signatureB64: sign(bob.privateKey, `fetch:devB:${NOW + 1}`) }, NOW + 1);
await ackDelivery(store, { deviceId: 'devB', envelopeId: submit.id, signatureB64: sign(bob.privateKey, `ack:${submit.id}`) }, NOW + 2);

// ---- 1) السكيمة: كل مغلف مخزّن فيه توجيه + ciphertext معتم بس ----
const store2 = createInMemoryStore(); // مغلف ثاني حي (الأول اتمسح بعد التسليم) عشان نفحص شكل السجل
await registerDevice(store2, { deviceId: 'devA', publicKeyB64: alice.publicKeyB64, signatureB64: sign(alice.privateKey, 'wisal-direct-register:devA') }, NOW);
await registerDevice(store2, { deviceId: 'devB', publicKeyB64: bob.publicKeyB64, signatureB64: sign(bob.privateKey, 'wisal-direct-register:devB') }, NOW);
const submit2 = await submitEnvelope(
  store2,
  { senderDeviceId: 'devA', recipientDeviceId: 'devB', ciphertextB64, backend: 'VODOZEMAC', expiresAtEpochSec: expiresAt },
  sign(alice.privateKey, proof),
  NOW,
);
const stored = store2.getEnvelope(submit2.id);
const allowedKeys = new Set(['id', 'senderDeviceId', 'recipientDeviceId', 'ciphertextB64', 'backend', 'expiresAtEpochSec', 'createdAtEpochSec']);
for (const key of Object.keys(stored)) {
  assert.ok(allowedKeys.has(key), `unexpected field in stored envelope: ${key}`);
}

// ---- 2) النص الصافي (SEEDED_PLAINTEXT) عمره ما يظهر حرفيًا في أي مكان في الـ
// store — الظهور الوحيد المسموح هو نسخته base64 جوه ciphertextB64 ----
const dump = JSON.stringify(Array.from(store2.allEnvelopes()));
assert.ok(!dump.includes(SEEDED_PLAINTEXT), 'plaintext test message leaked into server storage!');
assert.ok(dump.includes(ciphertextB64), 'sanity: base64 ciphertext form should be present');

// ---- 3) اللوجر بيرفض أي حقل غير مسموح — يمنع تسريب مستقبلي بالغلط ----
assert.throws(
  () => safeLog('envelope_submitted', { ciphertextB64, deviceId: 'devA' }),
  /not in the logging allowlist/,
  'logger must refuse to log a ciphertext/content field',
);
assert.throws(
  () => safeLog('debug', { plaintext: SEEDED_PLAINTEXT }),
  /not in the logging allowlist/,
);
// الاستخدام الطبيعي (معرّفات وأوقات بس) لازم يشتغل عادي
const line = safeLog('envelope_submitted', { senderDeviceId: 'devA', recipientDeviceId: 'devB', envelopeId: submit2.id, atEpochSec: NOW });
assert.ok(!line.includes(SEEDED_PLAINTEXT));
assert.ok(!ALLOWED_FIELDS.has('ciphertextB64'), 'ciphertextB64 must never be an allowed log field');

console.log('plaintext-leak.test.js: all assertions passed ✅');
})().catch((err) => { console.error(err); process.exit(1); });
