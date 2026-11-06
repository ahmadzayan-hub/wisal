// اختبارات إلغاء جهاز (ADR-002 release gate). شغّلها بـ: npm test
const assert = require('assert');
const { generateKeyPairSync, createSign, randomBytes } = require('crypto');
const { createInMemoryStore } = require('../lib/store');
const { registerDevice, submitEnvelope, listInbox, ackDelivery } = require('../lib/relay');
const { publishPreKeyBundle, claimPreKeyBundle, publishProof, claimProof } = require('../lib/prekeys');
const { revokeDevice, revokeProof } = require('../lib/revocation');

function makeIdentity() {
  const { publicKey, privateKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  return { publicKeyB64: publicKey.export({ type: 'spki', format: 'der' }).toString('base64'), privateKey };
}
function sign(privateKey, data) {
  const s = createSign('SHA256'); s.update(data); s.end();
  return s.sign(privateKey).toString('base64');
}
const b64 = (n) => randomBytes(n).toString('base64');
function makeBundle(deviceId, oneTimePreKeyId = 1) {
  return {
    deviceId, registrationId: 4242, identityKeyB64: b64(33),
    signedPreKeyId: 1, signedPreKeyPublicB64: b64(33), signedPreKeySignatureB64: b64(64),
    kyberPreKeyId: 1, kyberPreKeyPublicB64: b64(1569), kyberPreKeySignatureB64: b64(64),
    oneTimePreKeyId, oneTimePreKeyPublicB64: b64(33),
  };
}

const NOW = 1_800_000_000;

async function setup() {
  const store = createInMemoryStore();
  const ids = { devA: makeIdentity(), devB: makeIdentity(), devC: makeIdentity() };
  for (const [id, who] of Object.entries(ids)) {
    await registerDevice(store, {
      deviceId: id, publicKeyB64: who.publicKeyB64, signatureB64: sign(who.privateKey, `wisal-direct-register:${id}`),
    }, NOW);
  }
  const send = (from, to, ct = b64(16)) => submitEnvelope(store, {
    senderDeviceId: from, recipientDeviceId: to, ciphertextB64: ct, backend: 'SIGNAL', expiresAtEpochSec: NOW + 3600,
  }, sign(ids[from].privateKey, `${from}:${to}:${ct}:${NOW + 3600}`), NOW);
  const revoke = (id, who = ids[id], ts = NOW) => revokeDevice(store, {
    deviceId: id, timestamp: ts, signatureB64: sign(who.privateKey, revokeProof(id, ts)),
  }, NOW);
  const publish = (id, bundle) => publishPreKeyBundle(store, {
    bundle, timestamp: NOW, signatureB64: sign(ids[id].privateKey, publishProof(bundle, NOW)),
  }, NOW);
  const claim = (requester, target) => claimPreKeyBundle(store, {
    requesterDeviceId: requester, targetDeviceId: target, timestamp: NOW,
    signatureB64: sign(ids[requester].privateKey, claimProof(requester, target, NOW)),
  }, NOW);
  const inbox = (id) => listInbox(store, {
    deviceId: id, timestamp: NOW, signatureB64: sign(ids[id].privateKey, `fetch:${id}:${NOW}`),
  }, NOW);
  return { store, ids, send, revoke, publish, claim, inbox };
}

(async () => {
  // ---- الإلغاء بيمسح حزمة المفاتيح والمغلفات المستنية، ويسيب اللي الجهاز بعته ----
  {
    const { store, send, revoke, publish, inbox } = await setup();
    await publish('devB', makeBundle('devB'));
    const toB = await send('devA', 'devB');
    const fromB = await send('devB', 'devC');
    assert.strictEqual(toB.ok, true);
    assert.strictEqual(fromB.ok, true);

    const r = await revoke('devB');
    assert.deepStrictEqual(r, { ok: true, deviceId: 'devB', revoked: true });
    assert.strictEqual(store.getPreKeyBundle('devB'), null);
    assert.strictEqual(store.getEnvelope(toB.id), null, 'envelopes waiting for a revoked device are deleted');
    assert.ok(store.getEnvelope(fromB.id), 'envelopes the device sent before revocation stay for their recipients');
    assert.strictEqual((await inbox('devC')).items.length, 1);
    assert.strictEqual(store.getDevice('devB').revokedAtEpochSec, NOW);

    // إلغاء متكرر: نجاح من غير تغيير
    assert.strictEqual((await revoke('devB')).ok, true);
  }

  // ---- الجهاز الملغي ما يقدرش يعمل أي حاجة ----
  {
    const { store, ids, send, revoke, publish, claim, inbox } = await setup();
    const pending = await send('devB', 'devA');
    await revoke('devB');

    assert.strictEqual((await send('devB', 'devA')).reason, 'device revoked');
    assert.strictEqual((await inbox('devB')).reason, 'device revoked');
    assert.strictEqual((await publish('devB', makeBundle('devB', 2))).reason, 'device revoked');
    await publish('devA', makeBundle('devA'));
    assert.strictEqual((await claim('devB', 'devA')).reason, 'device revoked');
    const ack = await ackDelivery(store, {
      deviceId: 'devB', envelopeId: pending.id, signatureB64: sign(ids.devB.privateKey, `ack:${pending.id}`),
    }, NOW);
    assert.strictEqual(ack.reason, 'device revoked');

    // ومحاولة "إحياء" المعرّف: نفس المفتاح مرفوض، ومفتاح جديد مرفوض
    const again = await registerDevice(store, {
      deviceId: 'devB', publicKeyB64: ids.devB.publicKeyB64, signatureB64: sign(ids.devB.privateKey, 'wisal-direct-register:devB'),
    }, NOW + 10);
    assert.strictEqual(again.reason, 'device revoked');
    const thief = makeIdentity();
    const takeover = await registerDevice(store, {
      deviceId: 'devB', publicKeyB64: thief.publicKeyB64, signatureB64: sign(thief.privateKey, 'wisal-direct-register:devB'),
    }, NOW + 10);
    assert.strictEqual(takeover.ok, false);
  }

  // ---- الأجهزة التانية ما تقدرش تبعت لجهاز ملغي ولا تطلب مفاتيحه ----
  {
    const { send, revoke, publish, claim } = await setup();
    await publish('devB', makeBundle('devB'));
    await revoke('devB');
    assert.strictEqual((await send('devA', 'devB')).reason, 'recipient revoked');
    assert.strictEqual((await claim('devA', 'devB')).reason, 'target revoked');
  }

  // ---- إثبات الإلغاء: توقيع من مفتاح تاني، وقت قديم، جهاز مش مسجّل ----
  {
    const { store, ids, revoke } = await setup();
    assert.strictEqual((await revoke('devB', ids.devA)).reason, 'bad signature');
    assert.strictEqual((await revoke('devB', ids.devB, NOW - 301)).reason, 'stale timestamp');
    assert.strictEqual((await revoke('devZ', ids.devB)).reason, 'device not registered');
    assert.strictEqual(store.getDevice('devB').revokedAtEpochSec, undefined, 'failed revocations change nothing');
  }

  // ---- مستلم مش مسجّل: المغلف مرفوض (كان بيتخزّن ومحدش يسحبه أبدًا) ----
  {
    const { store, send } = await setup();
    const r = await send('devA', 'devNobody');
    assert.strictEqual(r.reason, 'recipient not registered');
    assert.strictEqual(store.allEnvelopes().length, 0);
  }

  console.log('revocation.test.js: all assertions passed ✅');
})().catch((err) => { console.error(err); process.exit(1); });
