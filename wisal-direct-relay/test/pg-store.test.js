// اختبارات تخزين PostgreSQL الحقيقي: نفس سيناريوهات الـ relay + سباقات
// متزامنة (تسجيل، نشر، طلب مفتاح فردي) اللي التخزين في الذاكرة ما يقدرش يكشفها.
//
// محتاج قاعدة بيانات اختبار فاضية: RELAY_TEST_DATABASE_URL. لو مش متظبط
// الاختبار بيتخطّى نفسه — إلا لو REQUIRE_PG=1 (زي في CI)، ساعتها بيفشل.
// تحذير: الاختبار بيمسح جداول relay_* في القاعدة دي.
const assert = require('assert');
const { generateKeyPairSync, createSign, randomBytes } = require('crypto');

const url = process.env.RELAY_TEST_DATABASE_URL;
if (!url) {
  if (process.env.REQUIRE_PG === '1') {
    console.error('pg-store.test.js: RELAY_TEST_DATABASE_URL is required (REQUIRE_PG=1)');
    process.exit(1);
  }
  console.log('pg-store.test.js: SKIPPED (set RELAY_TEST_DATABASE_URL to run against PostgreSQL)');
  process.exit(0);
}

const { Pool } = require('pg');
const { createPgStore } = require('../lib/pgStore');
const { registerDevice, submitEnvelope, listInbox, ackDelivery } = require('../lib/relay');
const { publishPreKeyBundle, claimPreKeyBundle, publishProof, claimProof } = require('../lib/prekeys');

function makeIdentity() {
  const { publicKey, privateKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  return { publicKeyB64: publicKey.export({ type: 'spki', format: 'der' }).toString('base64'), privateKey };
}
function sign(privateKey, data) {
  const s = createSign('SHA256'); s.update(data); s.end();
  return s.sign(privateKey).toString('base64');
}
const b64 = (n) => randomBytes(n).toString('base64');
function makeBundle(deviceId, oneTimePreKeyId) {
  return {
    deviceId, registrationId: 4242, identityKeyB64: b64(33),
    signedPreKeyId: 1, signedPreKeyPublicB64: b64(33), signedPreKeySignatureB64: b64(64),
    kyberPreKeyId: 1, kyberPreKeyPublicB64: b64(1569), kyberPreKeySignatureB64: b64(64),
    oneTimePreKeyId, oneTimePreKeyPublicB64: b64(33),
  };
}

const NOW = 1_800_000_000;

(async () => {
  const pool = new Pool({ connectionString: url, max: 10 });
  await pool.query('DROP TABLE IF EXISTS relay_devices, relay_envelopes, relay_prekey_bundles');
  const store = createPgStore(pool);

  const alice = makeIdentity();
  const bob = makeIdentity();
  const reg = (id, who, key = who.publicKeyB64) => registerDevice(store, {
    deviceId: id, publicKeyB64: key, signatureB64: sign(who.privateKey, `wisal-direct-register:${id}`),
  }, NOW);

  // ---- تسجيل: نجاح، إعادة بنفس المفتاح، انتحال بمفتاح تاني ----
  assert.strictEqual((await reg('devA', alice)).ok, true);
  assert.strictEqual((await reg('devB', bob)).ok, true);
  assert.strictEqual((await reg('devA', alice)).ok, true);
  assert.match((await reg('devA', bob)).reason, /different key/);
  assert.strictEqual((await store.getDevice('devA')).publicKeyB64, alice.publicKeyB64);

  // ---- سباق تسجيل: نفس المعرّف بمفتاحين مختلفين في نفس اللحظة → واحد بس يكسب ----
  {
    const ids = [makeIdentity(), makeIdentity(), makeIdentity(), makeIdentity()];
    const results = await Promise.all(ids.map((who) => reg('devRace', who)));
    assert.strictEqual(results.filter((r) => r.ok).length, 1, 'exactly one concurrent registration may win');
    const winner = ids[results.findIndex((r) => r.ok)];
    assert.strictEqual((await store.getDevice('devRace')).publicKeyB64, winner.publicKeyB64);
  }

  // ---- مغلف: إيداع → صندوق وارد → تأكيد (بيمسح) → اكتساح المنتهي ----
  {
    const ct = Buffer.from('opaque').toString('base64');
    const exp = NOW + 3600;
    const sub = await submitEnvelope(store, {
      senderDeviceId: 'devA', recipientDeviceId: 'devB', ciphertextB64: ct, backend: 'SIGNAL', expiresAtEpochSec: exp,
    }, sign(alice.privateKey, `devA:devB:${ct}:${exp}`), NOW);
    assert.strictEqual(sub.ok, true);

    const inbox = await listInbox(store, { deviceId: 'devB', timestamp: NOW, signatureB64: sign(bob.privateKey, `fetch:devB:${NOW}`) }, NOW);
    assert.strictEqual(inbox.items.length, 1);
    assert.strictEqual(inbox.items[0].ciphertextB64, ct);
    assert.strictEqual(inbox.items[0].createdAtEpochSec, NOW);

    const ack = await ackDelivery(store, { deviceId: 'devB', envelopeId: sub.id, signatureB64: sign(bob.privateKey, `ack:${sub.id}`) }, NOW);
    assert.strictEqual(ack.ok, true);
    assert.strictEqual(await store.getEnvelope(sub.id), null);

    const shortExp = NOW + 50;
    await submitEnvelope(store, {
      senderDeviceId: 'devA', recipientDeviceId: 'devB', ciphertextB64: ct, backend: 'SIGNAL', expiresAtEpochSec: shortExp,
    }, sign(alice.privateKey, `devA:devB:${ct}:${shortExp}`), NOW);
    const later = NOW + 200;
    const swept = await listInbox(store, { deviceId: 'devB', timestamp: later, signatureB64: sign(bob.privateKey, `fetch:devB:${later}`) }, later);
    assert.strictEqual(swept.items.length, 0);
  }

  const publish = (bundle, ts = NOW) => publishPreKeyBundle(
    store, { bundle, timestamp: ts, signatureB64: sign(bob.privateKey, publishProof(bundle, ts)) }, NOW,
  );
  const claim = (who, requester) => claimPreKeyBundle(store, {
    requesterDeviceId: requester, targetDeviceId: 'devB', timestamp: NOW,
    signatureB64: sign(who.privateKey, claimProof(requester, 'devB', NOW)),
  }, NOW);

  // ---- حزمة: نشر → طلب مرة واحدة → replay مرفوض ----
  {
    const first = makeBundle('devB', 1);
    assert.strictEqual((await publish(first)).ok, true);
    const r = await claim(alice, 'devA');
    assert.strictEqual(r.ok, true);
    assert.strictEqual(r.bundle.oneTimePreKeyPublicB64, first.oneTimePreKeyPublicB64);
    assert.strictEqual(r.bundle.kyberPreKeyPublicB64, first.kyberPreKeyPublicB64);
    assert.match((await claim(alice, 'devA')).reason, /no one-time pre-key/);
    const stored = await store.getPreKeyBundle('devB');
    assert.strictEqual(stored.oneTimePreKeyPublicB64, null);
    assert.strictEqual(stored.oneTimePreKeyClaimed, true);
    assert.match((await publish(first, NOW + 1)).reason, /must be higher/);
  }

  // ---- سباق طلب: ١٠ طلبات متزامنة على نفس المفتاح الفردي → واحد بس ياخده ----
  {
    const requesters = [];
    for (let i = 0; i < 10; i++) {
      const who = makeIdentity();
      await reg(`devC${i}`, who);
      requesters.push([who, `devC${i}`]);
    }
    const bundle = makeBundle('devB', 2);
    assert.strictEqual((await publish(bundle)).ok, true);
    const results = await Promise.all(requesters.map(([who, id]) => claim(who, id)));
    const winners = results.filter((r) => r.ok);
    assert.strictEqual(winners.length, 1, `exactly one concurrent claim may receive the one-time key, got ${winners.length}`);
    assert.strictEqual(winners[0].bundle.oneTimePreKeyPublicB64, bundle.oneTimePreKeyPublicB64);
    for (const r of results.filter((x) => !x.ok)) assert.match(r.reason, /no one-time pre-key/);
  }

  // ---- سباق نشر: نشرين متزامنين بنفس المعرّف الجديد → واحد بس يتكتب ----
  {
    const results = await Promise.all([publish(makeBundle('devB', 3)), publish(makeBundle('devB', 3))]);
    assert.strictEqual(results.filter((r) => r.ok).length, 1, 'two concurrent publishes of one id may not both apply');
  }

  // ---- السكيمة: مفيش عمود لمحتوى أو مفتاح خاص ----
  {
    const { rows } = await pool.query(
      "SELECT column_name FROM information_schema.columns WHERE table_name LIKE 'relay_%'",
    );
    for (const { column_name: col } of rows) {
      assert.ok(!/plain|private|secret|message|text$/.test(col), `unexpected column: ${col}`);
    }
  }

  await pool.end();
  console.log('pg-store.test.js: all assertions passed ✅');
})().catch((err) => { console.error(err); process.exit(1); });
