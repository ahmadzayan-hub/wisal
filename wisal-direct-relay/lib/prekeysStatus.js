// Authenticated, non-consuming status for the publishing device. This is an
// observation, not a reservation: another registered device can claim the OPK
// after this read. HTTPS authenticates the relay; no new crypto protocol is used.
const { verifySignature } = require('./crypto');
const { bundleDigest, validTimestamp, validateBundle, PREKEY_CLOCK_SKEW_SECONDS } = require('./prekeys');

const NONCE_PATTERN = /^[0-9a-f]{32}$/;
const DIGEST_PATTERN = /^[0-9a-f]{64}$/;
const refused = (reason) => ({ ok: false, reason });

function statusProof(deviceId, timestamp, requestNonce) {
  return `prekeys-status:${deviceId}:${timestamp}:${requestNonce}`;
}

async function preKeyPublicationStatus(store, { deviceId, timestamp, requestNonce, signatureB64 }, nowEpochSec) {
  if (typeof deviceId !== 'string' || !deviceId || deviceId.length > 200 || deviceId.includes(':')) {
    return refused('invalid deviceId');
  }
  if (!validTimestamp(timestamp) || !validTimestamp(nowEpochSec)) return refused('invalid timestamp');
  if (typeof requestNonce !== 'string' || !NONCE_PATTERN.test(requestNonce)) return refused('invalid request nonce');
  if (typeof signatureB64 !== 'string' || !signatureB64 || signatureB64.length > 1024) return refused('invalid signature');

  const device = await store.getDevice(deviceId);
  if (!device) return refused('device not registered');
  if (!verifySignature(device.publicKeyB64, statusProof(deviceId, timestamp, requestNonce), signatureB64)) {
    return refused('bad signature');
  }
  if (device.revokedAtEpochSec) return refused('device revoked');
  if (Math.abs(nowEpochSec - timestamp) > PREKEY_CLOCK_SKEW_SECONDS) return refused('stale timestamp');

  const current = await store.getPreKeyBundle(deviceId);
  const base = { ok: true, deviceId, requestNonce, serverTimeEpochSec: nowEpochSec };
  if (!current) {
    return { ...base, bundleDigest: null, oneTimePreKeyId: null,
      oneTimePreKeyAvailable: false, publishedAtEpochSec: null };
  }

  // Validate surviving public fields before treating a stored receipt as useful.
  // For a claimed OPK only its erased public bytes are intentionally absent.
  const publicFields = current.oneTimePreKeyClaimed
    ? { ...current, oneTimePreKeyPublicB64: 'AQ==' } : current;
  if (current.deviceId !== deviceId || validateBundle(publicFields)) {
    return refused('publication receipt unavailable');
  }

  // Old unclaimed rows still contain all public fields, so the digest can be
  // derived without a write. A legacy claimed row has lost its OPK and cannot
  // prove a complete publication; refusing is safer than inventing a receipt.
  let digest = current.bundleDigest;
  if (digest == null && !current.oneTimePreKeyClaimed && current.oneTimePreKeyPublicB64) {
    digest = bundleDigest(current);
  }
  if (typeof digest !== 'string' || !DIGEST_PATTERN.test(digest)
      || !Number.isInteger(current.oneTimePreKeyId) || current.oneTimePreKeyId < 1 || current.oneTimePreKeyId > 0x7fffffff
      || !validTimestamp(current.publishedAtEpochSec)
      || typeof current.oneTimePreKeyClaimed !== 'boolean'
      || (!current.oneTimePreKeyClaimed && (!current.oneTimePreKeyPublicB64 || digest !== bundleDigest(current)))) {
    return refused('publication receipt unavailable');
  }

  return { ...base, bundleDigest: digest, oneTimePreKeyId: current.oneTimePreKeyId,
    oneTimePreKeyAvailable: !current.oneTimePreKeyClaimed, publishedAtEpochSec: current.publishedAtEpochSec };
}

module.exports = { preKeyPublicationStatus, statusProof };
