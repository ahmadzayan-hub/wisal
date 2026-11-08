// Synthetic keys only. Tests call real relay logic, not an alternate implementation.
// Run: node --test test/ReleaseSecurityAcceptance.test.js
const { test, after } = require('node:test');
const cleanups = [];
after(async () => { for (const cleanup of cleanups.reverse()) await cleanup(); });
const assert = require('node:assert/strict');
const { generateKeyPairSync, sign: cryptoSign } = require('node:crypto');
const { createInMemoryStore } = require('../lib/store');
const { registerDevice, submitEnvelope, listInbox } = require('../lib/relay');
const { publishPreKeyBundle, claimPreKeyBundle, publishProof, claimProof } = require('../lib/prekeys');
const { revokeDevice, revokeProof } = require('../lib/revocation');
const NOW = 1800000000;
const sign = (who, proof) => cryptoSign('sha256', Buffer.from(proof), who.privateKey).toString('base64');
const bundle = (id = 1) => ({ deviceId: 'B', registrationId: 1, identityKeyB64: 'AQ==',
  signedPreKeyId: 1, signedPreKeyPublicB64: 'Ag==', signedPreKeySignatureB64: 'Aw==',
  kyberPreKeyId: 1, kyberPreKeyPublicB64: 'BA==', kyberPreKeySignatureB64: 'BQ==',
  oneTimePreKeyId: id, oneTimePreKeyPublicB64: 'Bg==' });
async function setup() {
  let store;
  if (process.env.RELAY_TEST_DATABASE_URL) {
    const { Pool } = require('pg');
    const { createPgStore } = require('../lib/pgStore');
    const schema = 'wisal_acceptance_' + require('node:crypto').randomBytes(10).toString('hex');
    const admin = new Pool({ connectionString: process.env.RELAY_TEST_DATABASE_URL, max: 1 });
    await admin.query(`CREATE SCHEMA ${schema}`);
    const pool = new Pool({ connectionString: process.env.RELAY_TEST_DATABASE_URL, max: 8,
      options: `-c search_path=${schema}` });
    cleanups.push(async () => { await pool.end(); await admin.query(`DROP SCHEMA ${schema} CASCADE`); await admin.end(); });
    store = createPgStore(pool);
  } else {
    if (process.env.REQUIRE_PG === '1') throw new Error('RELAY_TEST_DATABASE_URL required; no memory fallback');
    store = createInMemoryStore();
  }
  const identities = {};
  for (const id of ['A', 'B']) {
    const who = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
    identities[id] = who;
    const result = await registerDevice(store, { deviceId: id,
      publicKeyB64: who.publicKey.export({ type: 'spki', format: 'der' }).toString('base64'),
      signatureB64: sign(who, `wisal-direct-register:${id}`) }, NOW);
    assert.equal(result.ok, true);
  }
  const publish = (s = store, id = 1, ts = NOW) => {
    const b = bundle(id);
    return publishPreKeyBundle(s, { bundle: b, timestamp: ts,
      signatureB64: sign(identities.B, publishProof(b, ts)) }, NOW);
  };
  const claim = (s = store, ts = NOW) => claimPreKeyBundle(s, {
    requesterDeviceId: 'A', targetDeviceId: 'B', timestamp: ts,
    signatureB64: sign(identities.A, claimProof('A', 'B', ts)) }, NOW);
  const revoke = (id) => revokeDevice(store, { deviceId: id, timestamp: NOW,
    signatureB64: sign(identities[id], revokeProof(id, NOW)) }, NOW);
  return { store, identities, publish, claim, revoke };
}
// Pause immediately before the storage mutation. Revocation must dominate this
// operation once committed, including cleanup of an operation already in flight.
function pauseAt(store, method) {
  let entered, resume;
  const reached = new Promise(resolve => { entered = resolve; });
  const gate = new Promise(resolve => { resume = resolve; });
  return { reached, resume, wrapped: { ...store, [method]: async (...args) => {
    entered(); await gate; return store[method](...args);
  } } };
}
test('R01 concurrent claims deliver exactly one one-time key', async () => {
  const f = await setup(); await f.publish();
  const results = await Promise.all(Array.from({ length: 24 }, () => f.claim()));
  assert.equal(results.filter(r => r.ok).length, 1);
});
test('R02 consumed key cannot be restored by publication replay', async () => {
  const f = await setup(); await f.publish(); await f.claim();
  assert.equal((await f.publish()).ok, false);
});
test('R03 ordinary post-revocation publication and claims are refused', async () => {
  const f = await setup(); await f.publish(); await f.revoke('B');
  assert.equal((await f.publish(f.store, 2)).ok, false);
  assert.equal((await f.claim()).ok, false);
});
test('R04 revocation removes publication already in flight before its write', async () => {
  const f = await setup(); const p = pauseAt(f.store, 'putPreKeyBundle');
  const pending = f.publish(p.wrapped); await p.reached;
  await f.revoke('B'); p.resume(); await pending;
  assert.equal(await f.store.getPreKeyBundle('B'), null, 'revoked device regained a stored bundle');
});
test('R05 requester revocation prevents an in-flight claim consuming a key', async () => {
  const f = await setup(); await f.publish(); const p = pauseAt(f.store, 'claimPreKeyBundle');
  const pending = f.claim(p.wrapped); await p.reached;
  await f.revoke('A'); p.resume(); const result = await pending;
  assert.equal(result.ok, false, 'revoked requester received a one-time key');
  assert.equal((await f.store.getPreKeyBundle('B')).oneTimePreKeyClaimed, false);
});
test('R06 revoked recipient cannot regain an envelope through an in-flight submit', async () => {
  const f = await setup(); const p = pauseAt(f.store, 'putEnvelope');
  const envelope = { senderDeviceId: 'A', recipientDeviceId: 'B', ciphertextB64: 'AQ==',
    backend: 'SIGNAL', expiresAtEpochSec: NOW + 60 };
  const pending = submitEnvelope(p.wrapped, envelope, sign(f.identities.A, `A:B:AQ==:${NOW + 60}`), NOW);
  await p.reached; await f.revoke('B'); p.resume(); await pending;
  assert.equal((await f.store.listEnvelopesFor('B')).length, 0, 'envelope persisted after revocation cleanup');
});
test('R07 stale signed claim is refused without consumption', async () => {
  const f = await setup(); await f.publish();
  assert.equal((await f.claim(f.store, NOW - 301)).ok, false);
  assert.equal((await f.store.getPreKeyBundle('B')).oneTimePreKeyClaimed, false);
});
test('R08 signature for a different target cannot consume the bundle', async () => {
  const f = await setup(); await f.publish();
  const result = await claimPreKeyBundle(f.store, { requesterDeviceId: 'A', targetDeviceId: 'B',
    timestamp: NOW, signatureB64: sign(f.identities.A, claimProof('A', 'wrong', NOW)) }, NOW);
  assert.equal(result.ok, false);
  assert.equal((await f.store.getPreKeyBundle('B')).oneTimePreKeyClaimed, false);
});
test('R09 repeated self-revocation remains safe and cannot restore a bundle', async () => {
  const f = await setup(); await f.publish();
  assert.equal((await f.revoke('B')).ok, true);
  assert.equal((await f.revoke('B')).ok, true);
  assert.equal(await f.store.getPreKeyBundle('B'), null);
});
test('R10 concurrent different bundles sharing one id cannot both publish', async () => {
  const f = await setup(); const one = bundle(); const two = { ...one, identityKeyB64: 'Bw==' };
  const results = await Promise.all([one, two].map(b => publishPreKeyBundle(f.store, {
    bundle: b, timestamp: NOW, signatureB64: sign(f.identities.B, publishProof(b, NOW)) }, NOW)));
  assert.equal(results.filter(r => r.ok).length, 1);
});
