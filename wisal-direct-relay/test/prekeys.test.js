// اختبارات نقية لنشر وطلب حزم المفاتيح المسبقة (بدون شبكة). شغّلها بـ: npm test
const assert = require('assert');
const { generateKeyPairSync, createSign, randomBytes } = require('crypto');
const { createInMemoryStore } = require('../lib/store');
const { registerDevice } = require('../lib/relay');
const {
  publishPreKeyBundle, claimPreKeyBundle, publishProof, claimProof, BUNDLE_FIELDS,
} = require('../lib/prekeys');

function makeIdentity() {
  const { publicKey, privateKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const publicKeyB64 = publicKey.export({ type: 'spki', format: 'der' }).toString('base64');
  return { publicKeyB64, privateKey };
}
function sign(privateKey, data) {
  const signer = createSign('SHA256');
  signer.update(data);
  signer.end();
  return signer.sign(privateKey).toString('base64');
}
const b64 = (n) => randomBytes(n).toString('base64');

// حزمة اصطناعية بنفس شكل SignalPreKeyBundleDto (أحجام قريبة من الحقيقية).
function makeBundle(deviceId, oneTimePreKeyId = 1) {
  return {
    deviceId,
    registrationId: 4242,
    identityKeyB64: b64(33),
    signedPreKeyId: 1,
    signedPreKeyPublicB64: b64(33),
    signedPreKeySignatureB64: b64(64),
    kyberPreKeyId: 1,
    kyberPreKeyPublicB64: b64(1569),
    kyberPreKeySignatureB64: b64(64),
    oneTimePreKeyId,
    oneTimePreKeyPublicB64: b64(33),
  };
}

const NOW = 1_800_000_000;

async function setup() {
  const store = createInMemoryStore();
  const alice = makeIdentity();
  const bob = makeIdentity();
  for (const [id, who] of [['devA', alice], ['devB', bob]]) {
    await registerDevice(store, {
      deviceId: id, publicKeyB64: who.publicKeyB64,
      signatureB64: sign(who.privateKey, `wisal-direct-register:${id}`),
    }, NOW);
  }
  const publish = (who, bundle, ts = NOW) =>
    publishPreKeyBundle(store, { bundle, timestamp: ts, signatureB64: sign(who.privateKey, publishProof(bundle, ts)) }, NOW);
  const claim = (who, requester, target, ts = NOW) =>
    claimPreKeyBundle(store, {
      requesterDeviceId: requester, targetDeviceId: target, timestamp: ts,
      signatureB64: sign(who.privateKey, claimProof(requester, target, ts)),
    }, NOW);
  return { store, alice, bob, publish, claim };
}

(async () => {
// ---- دورة كاملة: بوب ينشر → أليس تطلب → المفتاح الفردي بيتسلّم مرة واحدة ----
{
  const { store, alice, bob, publish, claim } = await setup();
  const bundle = makeBundle('devB', 1);
  assert.strictEqual((await publish(bob, bundle)).ok, true);

  const r = await claim(alice, 'devA', 'devB');
  assert.strictEqual(r.ok, true);
  assert.deepStrictEqual(Object.keys(r.bundle), BUNDLE_FIELDS);
  assert.strictEqual(r.bundle.oneTimePreKeyPublicB64, bundle.oneTimePreKeyPublicB64);

  // التاني: مرفوض — عمر المفتاح الفردي ما يتسلّم مرتين
  const again = await claim(alice, 'devA', 'devB');
  assert.strictEqual(again.ok, false);
  assert.match(again.reason, /no one-time pre-key/);
  // والمفتاح الفردي اتمسح من التخزين فعلًا، مش بس اتعلّم
  assert.strictEqual(store.getPreKeyBundle('devB').oneTimePreKeyPublicB64, null);
}

// ---- إعادة إرسال نشر قديم (replay) بعد الاستهلاك: مرفوضة ----
{
  const { alice, bob, publish, claim } = await setup();
  const first = makeBundle('devB', 1);
  await publish(bob, first);
  await claim(alice, 'devA', 'devB');

  const replay = await publish(bob, first, NOW + 10);
  assert.strictEqual(replay.ok, false);
  assert.match(replay.reason, /must be higher/);

  // حزمة جديدة بمعرّف أعلى: مقبولة، وتتطلب تاني بنجاح
  const second = makeBundle('devB', 2);
  assert.strictEqual((await publish(bob, second, NOW + 20)).ok, true);
  const r = await claim(alice, 'devA', 'devB');
  assert.strictEqual(r.ok, true);
  assert.strictEqual(r.bundle.oneTimePreKeyId, 2);
}

// ---- إعادة محاولة نفس الحزمة قبل الاستهلاك: مقبولة ومش بتعمل reset ----
{
  const { bob, publish } = await setup();
  const bundle = makeBundle('devB', 5);
  assert.strictEqual((await publish(bob, bundle)).ok, true);
  assert.strictEqual((await publish(bob, bundle, NOW + 30)).ok, true);
  // معرّف أقل من الحالي: مرفوض حتى لو المفتاح الحالي لسه ما اتسلّمش
  assert.strictEqual((await publish(bob, makeBundle('devB', 4))).ok, false);
}

// ---- النشر: جهاز مش مسجّل، توقيع من مفتاح تاني، حقل اتعدّل بعد التوقيع، وقت قديم ----
{
  const { alice, bob, publish, store } = await setup();
  assert.strictEqual((await publish(bob, makeBundle('devZ'))).reason, 'device not registered');

  // أليس توقّع حزمة باسم بوب: مرفوض (التوقيع لازم يبقى بمفتاح devB)
  assert.strictEqual((await publish(alice, makeBundle('devB'))).reason, 'bad signature');

  const bundle = makeBundle('devB');
  const sig = sign(bob.privateKey, publishProof(bundle, NOW));
  const tampered = { ...bundle, identityKeyB64: b64(33) };
  const r = await publishPreKeyBundle(store, { bundle: tampered, timestamp: NOW, signatureB64: sig }, NOW);
  assert.strictEqual(r.reason, 'bad signature');

  assert.strictEqual((await publish(bob, makeBundle('devB'), NOW - 301)).reason, 'stale timestamp');
  assert.strictEqual(store.getPreKeyBundle('devB'), null);
}

// ---- التحقق من الحقول: base64 غلط، معرّف صفر، حجم زيادة، حقل ناقص ----
{
  const { bob, publish } = await setup();
  assert.match((await publish(bob, { ...makeBundle('devB'), identityKeyB64: 'not base64!' })).reason, /identityKeyB64/);
  assert.match((await publish(bob, { ...makeBundle('devB'), oneTimePreKeyId: 0 })).reason, /oneTimePreKeyId/);
  assert.match((await publish(bob, { ...makeBundle('devB'), kyberPreKeyPublicB64: b64(4000) })).reason, /kyberPreKeyPublicB64/);
  const missing = makeBundle('devB');
  delete missing.signedPreKeySignatureB64;
  assert.match((await publish(bob, missing)).reason, /signedPreKeySignatureB64/);
}

// ---- حقول زيادة (زي مفتاح خاص اتبعت بالغلط) عمرها ما بتتخزّن ولا بتترجع ----
{
  const { store, alice, bob, publish, claim } = await setup();
  const bundle = { ...makeBundle('devB'), privateKeyB64: b64(32) };
  assert.strictEqual((await publish(bob, bundle)).ok, true);
  assert.strictEqual('privateKeyB64' in store.getPreKeyBundle('devB'), false);
  assert.strictEqual('privateKeyB64' in (await claim(alice, 'devA', 'devB')).bundle, false);
}

// ---- الطلب: طالب مش مسجّل، توقيع غلط، وقت قديم، طلب حزمة نفسك، مفيش حزمة ----
{
  const { store, alice, bob, publish, claim } = await setup();
  await publish(bob, makeBundle('devB'));

  const stranger = makeIdentity();
  assert.strictEqual((await claim(stranger, 'devX', 'devB')).reason, 'device not registered');
  // بوب يوقّع باسم أليس: مرفوض
  assert.strictEqual((await claim(bob, 'devA', 'devB')).reason, 'bad signature');
  assert.strictEqual((await claim(alice, 'devA', 'devB', NOW - 301)).reason, 'stale timestamp');
  assert.strictEqual((await claim(bob, 'devB', 'devB')).reason, 'cannot claim your own bundle');
  assert.strictEqual((await claim(bob, 'devB', 'devA')).reason, 'no bundle published');

  // ولا محاولة من دول استهلكت المفتاح الفردي
  assert.strictEqual(store.getPreKeyBundle('devB').oneTimePreKeyClaimed, false);
  assert.strictEqual((await claim(alice, 'devA', 'devB')).ok, true);
}

console.log('prekeys.test.js: all assertions passed ✅');
})().catch((err) => { console.error(err); process.exit(1); });
