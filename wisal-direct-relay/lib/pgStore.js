// تخزين PostgreSQL للإنتاج — نفس واجهة createInMemoryStore بالظبط، بس كل
// الدوال async. ده اللي بيشيل الـ blocker الموثّق: التخزين في الذاكرة بيضيع
// مع كل cold start على Vercel.
//
// العمليات اللي فيها سباق (race) متنفّذة كجملة SQL واحدة ذرّية، مش "اقرأ
// وبعدين اكتب" من Node:
// - putDevice: INSERT ... ON CONFLICT DO NOTHING، وبعدين قراءة المخزّن فعلًا.
// - putPreKeyBundle: الكتابة مشروطة إن معرّف المفتاح الفردي أعلى من أي سابق.
// - claimPreKeyBundle: UPDATE واحد بيقفل الصف (FOR UPDATE) ويمسح المفتاح
//   الفردي ويرجّع قيمته القديمة — طلبين متزامنين عمرهم ما ياخدوا نفس المفتاح.
//
// السكيمة فيها توجيه + ciphertext معتم + مفاتيح عامة بس — مفيش عمود plaintext.

const SCHEMA = `
CREATE TABLE IF NOT EXISTS relay_devices (
  device_id               TEXT PRIMARY KEY,
  public_key_b64          TEXT NOT NULL,
  registered_at_epoch_sec BIGINT NOT NULL
);
ALTER TABLE relay_devices ADD COLUMN IF NOT EXISTS revoked_at_epoch_sec BIGINT;
CREATE TABLE IF NOT EXISTS relay_envelopes (
  id                  TEXT PRIMARY KEY,
  sender_device_id    TEXT NOT NULL,
  recipient_device_id TEXT NOT NULL,
  ciphertext_b64      TEXT NOT NULL,
  backend             TEXT NOT NULL,
  expires_at_epoch_sec BIGINT NOT NULL,
  created_at_epoch_sec BIGINT NOT NULL
);
CREATE INDEX IF NOT EXISTS relay_envelopes_recipient_idx ON relay_envelopes (recipient_device_id);
CREATE INDEX IF NOT EXISTS relay_envelopes_expiry_idx ON relay_envelopes (expires_at_epoch_sec);
CREATE TABLE IF NOT EXISTS relay_prekey_bundles (
  device_id                    TEXT PRIMARY KEY,
  registration_id              INTEGER NOT NULL,
  identity_key_b64             TEXT NOT NULL,
  signed_pre_key_id            INTEGER NOT NULL,
  signed_pre_key_public_b64    TEXT NOT NULL,
  signed_pre_key_signature_b64 TEXT NOT NULL,
  kyber_pre_key_id             INTEGER NOT NULL,
  kyber_pre_key_public_b64     TEXT NOT NULL,
  kyber_pre_key_signature_b64  TEXT NOT NULL,
  one_time_pre_key_id          INTEGER NOT NULL,
  one_time_pre_key_public_b64  TEXT,
  one_time_pre_key_claimed     BOOLEAN NOT NULL DEFAULT FALSE,
  published_at_epoch_sec       BIGINT NOT NULL
);
ALTER TABLE relay_prekey_bundles ADD COLUMN IF NOT EXISTS bundle_digest TEXT;
`;

const BUNDLE_COLUMNS = [
  ['deviceId', 'device_id'],
  ['registrationId', 'registration_id'],
  ['identityKeyB64', 'identity_key_b64'],
  ['signedPreKeyId', 'signed_pre_key_id'],
  ['signedPreKeyPublicB64', 'signed_pre_key_public_b64'],
  ['signedPreKeySignatureB64', 'signed_pre_key_signature_b64'],
  ['kyberPreKeyId', 'kyber_pre_key_id'],
  ['kyberPreKeyPublicB64', 'kyber_pre_key_public_b64'],
  ['kyberPreKeySignatureB64', 'kyber_pre_key_signature_b64'],
  ['oneTimePreKeyId', 'one_time_pre_key_id'],
  ['oneTimePreKeyPublicB64', 'one_time_pre_key_public_b64'],
  ['oneTimePreKeyClaimed', 'one_time_pre_key_claimed'],
  ['publishedAtEpochSec', 'published_at_epoch_sec'],
  ['bundleDigest', 'bundle_digest'],
];

function rowToDevice(r) {
  const device = { deviceId: r.device_id, publicKeyB64: r.public_key_b64, registeredAtEpochSec: Number(r.registered_at_epoch_sec) };
  if (r.revoked_at_epoch_sec !== null && r.revoked_at_epoch_sec !== undefined) {
    device.revokedAtEpochSec = Number(r.revoked_at_epoch_sec);
  }
  return device;
}
function rowToEnvelope(r) {
  return {
    id: r.id, senderDeviceId: r.sender_device_id, recipientDeviceId: r.recipient_device_id,
    ciphertextB64: r.ciphertext_b64, backend: r.backend,
    expiresAtEpochSec: Number(r.expires_at_epoch_sec), createdAtEpochSec: Number(r.created_at_epoch_sec),
  };
}
function rowToBundle(r) {
  const out = {};
  for (const [key, col] of BUNDLE_COLUMNS) out[key] = r[col];
  out.publishedAtEpochSec = Number(out.publishedAtEpochSec);
  return out;
}

// pool: pg.Pool (أو أي حاجة عندها query وconnect). بيتحقن عشان الاختبارات.
function createPgStore(pool) {
  let schemaReady = null;
  const ready = () => {
    if (!schemaReady) schemaReady = pool.query(SCHEMA).catch((err) => { schemaReady = null; throw err; });
    return schemaReady;
  };
  const q = async (text, params) => { await ready(); return pool.query(text, params); };
  // Serialize mutations with revoke's UPDATE on the same device rows. Lock in
  // stable order for opposite-direction submissions/claims, avoiding deadlocks.
  const withActiveDevices = async (ids, refused, operation) => {
    if (ids.some((id) => typeof id !== 'string' || !id)) return refused;
    const unique = [...new Set(ids)].sort();
    await ready();
    const client = await pool.connect();
    try {
      await client.query('BEGIN');
      const { rows } = await client.query(
        'SELECT device_id, revoked_at_epoch_sec FROM relay_devices WHERE device_id = ANY($1::text[]) ORDER BY device_id FOR UPDATE',
        [unique],
      );
      const result = rows.length === unique.length && rows.every((r) => r.revoked_at_epoch_sec == null)
        ? await operation(client) : refused;
      await client.query('COMMIT');
      return result;
    } catch (err) {
      await client.query('ROLLBACK').catch(() => {});
      throw err;
    } finally { client.release(); }
  };

  return {
    // ---- الأجهزة ----
    async getDevice(deviceId) {
      const { rows } = await q('SELECT * FROM relay_devices WHERE device_id = $1', [deviceId]);
      return rows[0] ? rowToDevice(rows[0]) : null;
    },
    async putDevice(device) {
      await q(
        `INSERT INTO relay_devices (device_id, public_key_b64, registered_at_epoch_sec)
         VALUES ($1, $2, $3) ON CONFLICT (device_id) DO NOTHING`,
        [device.deviceId, device.publicKeyB64, device.registeredAtEpochSec],
      );
      const { rows } = await q('SELECT * FROM relay_devices WHERE device_id = $1', [device.deviceId]);
      return rowToDevice(rows[0]);
    },
    // كل الخطوات في transaction واحدة: يا تتنفّذ كلها يا ولا واحدة.
    async revokeDevice(deviceId, nowEpochSec) {
      await ready();
      const client = await pool.connect();
      try {
        await client.query('BEGIN');
        const { rowCount } = await client.query(
          `UPDATE relay_devices SET revoked_at_epoch_sec = $2
            WHERE device_id = $1 AND revoked_at_epoch_sec IS NULL`,
          [deviceId, nowEpochSec],
        );
        if (rowCount === 1) {
          await client.query('DELETE FROM relay_prekey_bundles WHERE device_id = $1', [deviceId]);
          await client.query('DELETE FROM relay_envelopes WHERE recipient_device_id = $1', [deviceId]);
        }
        await client.query('COMMIT');
        return rowCount === 1;
      } catch (err) {
        await client.query('ROLLBACK').catch(() => {});
        throw err;
      } finally {
        client.release();
      }
    },

    // ---- المغلفات ----
    async putEnvelope(e) {
      return withActiveDevices([e.senderDeviceId, e.recipientDeviceId], false, async (client) => {
        await client.query(
          `INSERT INTO relay_envelopes
             (id, sender_device_id, recipient_device_id, ciphertext_b64, backend, expires_at_epoch_sec, created_at_epoch_sec)
           VALUES ($1, $2, $3, $4, $5, $6, $7)`,
          [e.id, e.senderDeviceId, e.recipientDeviceId, e.ciphertextB64, e.backend, e.expiresAtEpochSec, e.createdAtEpochSec],
        );
        return true;
      });
    },
    async getEnvelope(id) {
      const { rows } = await q('SELECT * FROM relay_envelopes WHERE id = $1', [id]);
      return rows[0] ? rowToEnvelope(rows[0]) : null;
    },
    async deleteEnvelope(id) {
      await q('DELETE FROM relay_envelopes WHERE id = $1', [id]);
    },
    async listEnvelopesFor(recipientDeviceId) {
      const { rows } = await q(
        'SELECT * FROM relay_envelopes WHERE recipient_device_id = $1 ORDER BY created_at_epoch_sec, id',
        [recipientDeviceId],
      );
      return rows.map(rowToEnvelope);
    },
    async allEnvelopes() {
      const { rows } = await q('SELECT * FROM relay_envelopes ORDER BY created_at_epoch_sec, id');
      return rows.map(rowToEnvelope);
    },
    async sweepExpired(nowEpochSec) {
      const { rowCount } = await q('DELETE FROM relay_envelopes WHERE expires_at_epoch_sec <= $1', [nowEpochSec]);
      return rowCount;
    },

    // ---- حزم المفاتيح المسبقة ----
    async putPreKeyBundle(deviceId, bundle) {
      return withActiveDevices([deviceId], false, async (client) => {
        const values = BUNDLE_COLUMNS.map(([key]) => (key === 'deviceId' ? deviceId : bundle[key]));
        const cols = BUNDLE_COLUMNS.map(([, col]) => col);
        const updates = cols.filter((c) => c !== 'device_id').map((c) => `${c} = EXCLUDED.${c}`).join(', ');
        // الكتابة بتحصل بس لو المعرّف الجديد أعلى من المخزّن — ذرّيًا في نفس الجملة.
        const { rowCount } = await client.query(
          `INSERT INTO relay_prekey_bundles (${cols.join(', ')})
           VALUES (${cols.map((_, i) => `$${i + 1}`).join(', ')})
           ON CONFLICT (device_id) DO UPDATE SET ${updates}
           WHERE relay_prekey_bundles.one_time_pre_key_id < EXCLUDED.one_time_pre_key_id`,
          values,
        );
        return rowCount === 1;
      });
    },
    async getPreKeyBundle(deviceId) {
      const { rows } = await q('SELECT * FROM relay_prekey_bundles WHERE device_id = $1', [deviceId]);
      return rows[0] ? rowToBundle(rows[0]) : null;
    },
    async claimPreKeyBundle(deviceId, requesterDeviceId) {
      if (deviceId === requesterDeviceId) return null;
      return withActiveDevices([deviceId, requesterDeviceId], null, async (client) => {
        const { rows } = await client.query(
          `UPDATE relay_prekey_bundles AS b
              SET one_time_pre_key_public_b64 = NULL, one_time_pre_key_claimed = TRUE
             FROM (SELECT device_id, one_time_pre_key_public_b64 AS claimed_key
                     FROM relay_prekey_bundles
                    WHERE device_id = $1 AND NOT one_time_pre_key_claimed
                    FOR UPDATE) AS old
            WHERE b.device_id = old.device_id AND NOT b.one_time_pre_key_claimed
           RETURNING b.*, old.claimed_key`,
          [deviceId],
        );
        if (!rows[0]) return null;
        const bundle = rowToBundle(rows[0]);
        return { ...bundle, oneTimePreKeyPublicB64: rows[0].claimed_key, oneTimePreKeyClaimed: false };
      });
    },
  };
}

module.exports = { createPgStore, SCHEMA };
