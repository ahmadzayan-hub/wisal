# wisal-direct-relay

Store-and-forward relay for **Wisal Direct** — the future end-to-end
encrypted 1:1 messaging path described in
[`docs/decisions/ADR-002-e2ee-protocol.md`](../docs/decisions/ADR-002-e2ee-protocol.md).

This is **not** the WhatsApp Business relay (`../wisal-cloud-api`) and it is
**not** an E2EE protocol implementation. It is the dumb pipe in between:
devices register a public key, senders drop opaque encrypted envelopes,
recipients pick them up, and the server deletes an envelope the moment
delivery is confirmed. It never sees, stores, or logs plaintext — there is no
field in the schema for it.

## What this is not (read before extending)

- **Not encrypted messaging yet.** No client integrates a real E2EE backend
  (libsignal/vodozemac) against this relay yet — that's the next slice. Until
  that ships and passes the ADR-002 release gates, nothing in the product may
  call this "encrypted."
- **Storage is chosen by environment** (`lib/selectStore.js`):
  - `DATABASE_URL` set: PostgreSQL (`lib/pgStore.js`). Tables are created on
    first use (`CREATE TABLE IF NOT EXISTS`).
  - Production (`NODE_ENV` or `VERCEL_ENV` = `production`) without
    `DATABASE_URL`: every request returns `503 relay storage unavailable`. It
    never falls back to memory that resets on each cold start.
  - Otherwise (local dev, tests): in-memory (`lib/store.js`).
- **Race conditions are handled inside single SQL statements**, not
  read-then-write in Node: registration is insert-if-absent, a pre-key publish
  applies only if its one-time key id is higher, and a claim locks the row and
  clears the one-time key in one `UPDATE`. `test/pg-store.test.js` runs ten
  simultaneous claims and requires exactly one winner.

## Endpoints

All bodies are JSON. All mutating calls require a signature proving control
of the device's private key — the server never trusts a bare device id.

| Method | Path | Purpose | Auth |
|---|---|---|---|
| POST | `/api/devices` | Register a device (id + public key) | signs `wisal-direct-register:{deviceId}` |
| POST | `/api/envelopes` | Submit an encrypted envelope for a recipient | signs `{sender}:{recipient}:{ciphertextB64}:{expiresAt}` |
| GET | `/api/inbox?deviceId=&timestamp=&signatureB64=` | List envelopes addressed to you | signs `fetch:{deviceId}:{timestamp}` (±5min clock skew) |
| POST | `/api/ack` | Confirm delivery — deletes the envelope | signs `ack:{envelopeId}` |
| POST | `/api/prekeys` | Publish a Signal pre-key bundle (public keys only) | signs `wisal-direct-prekeys:{timestamp}:{bundle fields in SignalPreKeyBundleDto order}` (±5min) |
| POST | `/api/prekeys-claim` | Claim another device's bundle; consumes its one-time pre-key | signs `claim-prekeys:{requester}:{target}:{timestamp}` (±5min) |

Signatures are ECDSA-SHA256 over EC P-256 (`prime256v1`), public key as
X.509 SubjectPublicKeyInfo, base64-encoded — the same format
`android-wife-assistant`'s `DeviceIdentityCodec` produces, so an Android
client's identity keypair verifies here with no format conversion.

## Pre-key bundles

- The relay stores public keys only. It checks that the publisher owns the registered device (P-256 signature); Signal's own signed pre-key and Kyber signatures are verified by the client through libsignal when it builds the session.
- A one-time pre-key is handed out **once**, then deleted. A second claim is refused with `no one-time pre-key available` until the device publishes a new bundle. The relay never returns a bundle without its one-time key and never reuses one.
- Each new bundle must carry a higher `oneTimePreKeyId` than any the device published before, so replaying an old signed publish cannot bring back a used key. Re-sending the identical, still-unclaimed bundle is accepted as a retry.
- Claims require a registered, signed, recent request, so anonymous callers cannot drain one-time keys.
- Claims are atomic in PostgreSQL (see storage above); concurrent claims never receive the same one-time key.
- **Not yet built:** the Android client calls, persistence of pre-keys on the device, and identity-key verification between users (safety numbers). Nothing here supports an "encrypted" claim yet.

## Data retention

- An envelope's `expiresAtEpochSec` is capped at 14 days from submission.
- `GET /api/inbox` sweeps expired envelopes before returning results —
  nothing past-expiry is ever handed to a client.
- `POST /api/ack` deletes the envelope immediately on confirmed delivery.
  The server never keeps a copy "just in case."

## Why the logger throws instead of redacting

`lib/log.js` uses an allowlist, not a blocklist: only known-safe fields
(ids, timestamps, counts, error reasons) may be logged, and anything else —
including `ciphertextB64` — throws instead of silently logging. A blocklist
would need someone to remember to add every future content-bearing field; an
allowlist fails loud the first time code tries to log something it shouldn't,
which is what `test/plaintext-leak.test.js` exercises directly.

## Local development

```bash
cd wisal-direct-relay
npm install    # no runtime deps beyond Node's built-ins today
npm run check  # syntax
npm test       # pure logic tests, no server needed
```

To run the actual HTTP handlers locally, use the Vercel CLI (`vercel dev`)
from this directory — the handlers are standard `(req, res) => {}` Vercel
Node functions, same shape as `../wisal-cloud-api`.

## Deploy

New Vercel project, Root Directory = `wisal-direct-relay`. Set `DATABASE_URL`
to a PostgreSQL database (for example Neon or Vercel Postgres; add
`?sslmode=require` if the provider needs TLS). Without it, production returns
503 on every call. There is no shared API key to configure; every client
authenticates itself with its own device signature.

Tests: `npm test`. The PostgreSQL test runs when `RELAY_TEST_DATABASE_URL`
points at an empty test database (it drops and recreates the `relay_*`
tables). CI starts a PostgreSQL 16 service and sets `REQUIRE_PG=1`, so the
test fails rather than skips there.
