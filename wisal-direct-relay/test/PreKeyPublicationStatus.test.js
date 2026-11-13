// Synthetic identities, real signed requests and actual relay storage logic.
// These tests do not exercise a deployed service or Android device.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { generateKeyPairSync, sign: cryptoSign, randomBytes } = require('node:crypto');
const { createInMemoryStore } = require('../lib/store');
const { createPgStore, SCHEMA } = require('../lib/pgStore');
const { registerDevice } = require('../lib/relay');
const { publishPreKeyBundle, claimPreKeyBundle, publishProof, claimProof, bundleDigest } = require('../lib/prekeys');
const { preKeyPublicationStatus, statusProof } = require('../lib/prekeysStatus');

const NOW = 1_800_000_000;
const NONCE = '00112233445566778899aabbccddeeff';
const sign = (who, proof) => cryptoSign('sha256', Buffer.from(proof, 'utf8'), who.privateKey).toString('base64');
const makeBundle = (id = 1) => ({ deviceId: 'B', registrationId: 1, identityKeyB64: 'AQ==',
  signedPreKeyId: 2, signedPreKeyPublicB64: 'Ag==', signedPreKeySignatureB64: 'Aw==',
  kyberPreKeyId: 3, kyberPreKeyPublicB64: 'BA==', kyberPreKeySignatureB64: 'BQ==',
  oneTimePreKeyId: id, oneTimePreKeyPublicB64: 'Bg==' });

async function setup() {
  const store = createInMemoryStore();
  const identities = {};
  for (const id of ['A', 'B']) {
    const who = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
    identities[id] = who;
    assert.equal((await registerDevice(store, { deviceId: id,
      publicKeyB64: who.publicKey.export({ type: 'spki', format: 'der' }).toString('base64'),
      signatureB64: sign(who, `wisal-direct-register:${id}`) }, NOW)).ok, true);
  }
  const request = (deviceId = 'B', timestamp = NOW, requestNonce = NONCE, who = identities.B) => ({
    deviceId, timestamp, requestNonce, signatureB64: sign(who, statusProof(deviceId, timestamp, requestNonce)),
  });
  const status = (r = request(), s = store) => preKeyPublicationStatus(s, r, NOW);
  const publish = (bundle = makeBundle(), timestamp = NOW) => publishPreKeyBundle(store, {
    bundle, timestamp, signatureB64: sign(identities.B, publishProof(bundle, timestamp)),
  }, NOW);
  const claim = (timestamp = NOW) => claimPreKeyBundle(store, {
    requesterDeviceId: 'A', targetDeviceId: 'B', timestamp,
    signatureB64: sign(identities.A, claimProof('A', 'B', timestamp)),
  }, NOW);
  return { store, identities, request, status, publish, claim };
}

test('P01 authenticated absent publication is explicit and does not create keys', async () => {
  const f = await setup();
  const expected = { ok: true, deviceId: 'B', requestNonce: NONCE, serverTimeEpochSec: NOW,
    bundleDigest: null, oneTimePreKeyId: null, oneTimePreKeyAvailable: false, publishedAtEpochSec: null };
  assert.deepEqual(await f.status(), expected);
  assert.deepEqual(await f.status(), expected);
  assert.equal(await f.store.getPreKeyBundle('B'), null);
});

test('P02 status identifies the complete accepted bundle without returning its keys', async () => {
  const f = await setup(); const b = makeBundle();
  assert.equal((await f.publish(b)).ok, true);
  const result = await f.status();
  assert.deepEqual(result, { ok: true, deviceId: 'B', requestNonce: NONCE, serverTimeEpochSec: NOW,
    bundleDigest: bundleDigest(b), oneTimePreKeyId: 1, oneTimePreKeyAvailable: true, publishedAtEpochSec: NOW });
  for (const field of ['identityKeyB64', 'oneTimePreKeyPublicB64', 'signedPreKeyPublicB64', 'privateKeyB64', 'bundle']) {
    assert.equal(field in result, false);
  }
  assert.notEqual(bundleDigest({ ...b, signedPreKeyId: 4 }), result.bundleDigest);
  assert.notEqual(bundleDigest({ ...b, oneTimePreKeyPublicB64: 'Bw==' }), result.bundleDigest);
});

test('P03 lost publication response can be reconciled after claim without restoring the key', async () => {
  const f = await setup(); const b = makeBundle();
  await f.publish(b); // Client may have lost this response.
  assert.equal((await f.claim()).ok, true);
  const status = await f.status();
  assert.equal(status.ok, true);
  assert.equal(status.bundleDigest, bundleDigest(b));
  assert.equal(status.oneTimePreKeyAvailable, false);
  assert.equal((await f.store.getPreKeyBundle('B')).oneTimePreKeyPublicB64, null);
  assert.equal((await f.publish(b)).ok, false);
  assert.equal((await f.claim()).ok, false);
  assert.equal((await f.status()).bundleDigest, status.bundleDigest);
});

test('P04 repeated status calls never consume or reinsert a one-time key', async () => {
  const f = await setup(); await f.publish();
  const before = JSON.stringify(await f.store.getPreKeyBundle('B'));
  for (let i = 0; i < 8; i++) assert.equal((await f.status()).oneTimePreKeyAvailable, true);
  assert.equal(JSON.stringify(await f.store.getPreKeyBundle('B')), before);
  assert.equal((await f.claim()).ok, true);
});

test('P05 nonce is covered by the signature and echoed for the matching request', async () => {
  const f = await setup(); const request = f.request();
  const another = randomBytes(16).toString('hex');
  assert.equal((await f.status({ ...request, requestNonce: another })).reason, 'bad signature');
  assert.equal((await f.status(f.request('B', NOW, another))).requestNonce, another);
});

test('P06 another registered device cannot read the owner publication', async () => {
  const f = await setup(); await f.publish();
  assert.equal((await f.status(f.request('B', NOW, NONCE, f.identities.A))).reason, 'bad signature');
  // A request for A signed by A observes A, never B's bundle.
  assert.equal((await f.status(f.request('A', NOW, NONCE, f.identities.A))).bundleDigest, null);
});

test('P07 revocation refuses status even with a fresh valid owner signature', async () => {
  const f = await setup(); await f.publish();
  await f.store.revokeDevice('B', NOW);
  assert.equal((await f.status()).reason, 'device revoked');
});

test('P08 unregistered owner cannot inspect a publication', async () => {
  const f = await setup();
  assert.equal((await f.status(f.request('unknown'))).reason, 'device not registered');
});

test('P09 timestamp skew boundaries are enforced in both directions', async () => {
  const f = await setup();
  for (const offset of [-300, 0, 300]) assert.equal((await f.status(f.request('B', NOW + offset))).ok, true);
  for (const offset of [-301, 301]) assert.equal((await f.status(f.request('B', NOW + offset))).reason, 'stale timestamp');
});

test('P10 noninteger, unsafe and nonnumeric timestamps fail before storage access', async () => {
  const f = await setup(); let accessed = false;
  const noReads = { getDevice() { accessed = true; throw new Error('unexpected access'); } };
  for (const timestamp of [NaN, Infinity, -Infinity, 0, -1, NOW + 0.5, Number.MAX_SAFE_INTEGER + 1, String(NOW), null, {}, []]) {
    assert.equal((await preKeyPublicationStatus(noReads, { ...f.request(), timestamp }, NOW)).reason, 'invalid timestamp');
  }
  assert.equal(accessed, false);
});

test('P11 malformed nonce, owner and signature are rejected before storage access', async () => {
  const f = await setup(); let accessed = false;
  const noReads = { getDevice() { accessed = true; throw new Error('unexpected access'); } };
  for (const requestNonce of ['', 'ab'.repeat(15), 'ab'.repeat(17), 'AB'.repeat(16), 'x'.repeat(32), {}, null]) {
    assert.equal((await preKeyPublicationStatus(noReads, { ...f.request(), requestNonce }, NOW)).reason, 'invalid request nonce');
  }
  for (const deviceId of ['', 'A:B', 'x'.repeat(201), {}, null]) {
    assert.equal((await preKeyPublicationStatus(noReads, { ...f.request(), deviceId }, NOW)).reason, 'invalid deviceId');
  }
  for (const signatureB64 of ['', {}, 'a'.repeat(1025)]) {
    assert.equal((await preKeyPublicationStatus(noReads, { ...f.request(), signatureB64 }, NOW)).reason, 'invalid signature');
  }
  assert.equal(accessed, false);
});

test('P12 legacy unclaimed publication derives a digest without mutating storage', async () => {
  const f = await setup(); const b = makeBundle();
  await f.store.putPreKeyBundle('B', { ...b, publishedAtEpochSec: NOW, oneTimePreKeyClaimed: false });
  const before = JSON.stringify(await f.store.getPreKeyBundle('B'));
  assert.equal((await f.status()).bundleDigest, bundleDigest(b));
  assert.equal(JSON.stringify(await f.store.getPreKeyBundle('B')), before);
});

test('P13 legacy claimed publication without digest cannot be certified', async () => {
  const f = await setup();
  await f.store.putPreKeyBundle('B', { ...makeBundle(), publishedAtEpochSec: NOW, oneTimePreKeyClaimed: false });
  await f.claim();
  assert.equal((await f.status()).reason, 'publication receipt unavailable');
});

test('P14 malformed or conflicting stored receipts fail closed', async () => {
  for (const digest of ['not a digest', 'a'.repeat(64)]) {
    const f = await setup();
    await f.store.putPreKeyBundle('B', { ...makeBundle(), bundleDigest: digest,
      publishedAtEpochSec: NOW, oneTimePreKeyClaimed: false });
    assert.equal((await f.status()).reason, 'publication receipt unavailable');
  }
  const f = await setup();
  const incomplete = { ...makeBundle(), publishedAtEpochSec: NOW, oneTimePreKeyClaimed: false };
  delete incomplete.signedPreKeyPublicB64;
  await f.store.putPreKeyBundle('B', incomplete);
  assert.equal((await f.status()).reason, 'publication receipt unavailable');
});

test('P15 storage read failures cannot be mistaken for absent publication', async () => {
  const f = await setup();
  const broken = { ...f.store, getPreKeyBundle() { throw new Error('synthetic unavailable'); } };
  await assert.rejects(f.status(f.request(), broken), /synthetic unavailable/);
});

test('P16 publication and claim refuse invalid numeric timestamps without changing stock', async () => {
  const f = await setup(); await f.publish();
  for (const timestamp of [Infinity, -1, NOW + 0.5, Number.MAX_SAFE_INTEGER + 1]) {
    assert.equal((await f.publish(makeBundle(2), timestamp)).reason, 'invalid timestamp');
    assert.equal((await f.claim(timestamp)).reason, 'invalid timestamp');
  }
  assert.equal((await f.status()).oneTimePreKeyId, 1);
  assert.equal((await f.status()).oneTimePreKeyAvailable, true);
});

test('P17 PostgreSQL adapter maps stored digest and additive schema without changing consumed-key semantics', async () => {
  // Adapter contract only; the separate pg-store suite requires real PostgreSQL.
  assert.match(SCHEMA, /ALTER TABLE relay_prekey_bundles ADD COLUMN IF NOT EXISTS bundle_digest TEXT/);
  const row = { device_id: 'B', registration_id: 1, identity_key_b64: 'AQ==',
    signed_pre_key_id: 2, signed_pre_key_public_b64: 'Ag==', signed_pre_key_signature_b64: 'Aw==',
    kyber_pre_key_id: 3, kyber_pre_key_public_b64: 'BA==', kyber_pre_key_signature_b64: 'BQ==',
    one_time_pre_key_id: 1, one_time_pre_key_public_b64: null, one_time_pre_key_claimed: true,
    published_at_epoch_sec: String(NOW), bundle_digest: bundleDigest(makeBundle()) };
  const pool = { async query(sql) { return { rows: sql.includes('SELECT * FROM relay_prekey_bundles') ? [row] : [] }; } };
  const result = await createPgStore(pool).getPreKeyBundle('B');
  assert.equal(result.bundleDigest, row.bundle_digest);
  assert.equal(result.oneTimePreKeyPublicB64, null);
  assert.equal(result.oneTimePreKeyClaimed, true);
  assert.equal(result.publishedAtEpochSec, NOW);
});
