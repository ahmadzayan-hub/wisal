# Wisal durable delivery and release build

Prepared for Ahmed Zaian. 9 November 2026, Dubai.

This iteration completes a core delivery transaction which was missing from the
previous Signal persistence candidate. It does not complete Android UI pairing,
physical-device acceptance or final E2EE release authorization.

## Implemented

* Outbound ciphertext and the new ratchet state commit together. A caller's stable
  message id binds recipient, payload digest and expiry. Retrying after reload
  returns the same ciphertext rather than advancing the ratchet again.
* Inbound plaintext, native ratchet/prekey mutations and a pending ACK record
  commit in the same encrypted snapshot. Restarting before UI delivery cannot
  discard the only plaintext copy. Inbox rows have stable sender-bound ciphertext
  digests, so retries with different relay envelope ids do not create duplicate rows.
* Envelope ids cannot silently change their contents. Unknown, malformed or
  oversized input cannot commit an inbox record or ACK intent.
* DirectSignalDelivery submits saved outbox ciphertext, and ACKs only durable
  inbox records. If the server deletes a message but the ACK response is lost,
  the next poll can retire the stale local ACK intent on the relay's existing
  authenticated 'not found' response. The inbox row is never deleted by ACK cleanup.
* AndroidSignalProvider exposes the queue through the existing SecureStore-backed
  snapshot. No plaintext snapshot file is used by the Android adapter.
* State-capacity rejection is checked before the ambiguous-write latch. An
  oversized operation therefore does not block reads of the last durable state.
* Signing secrets are no longer job-wide environment variables. Only signing
  prerequisite, decode and signing steps receive the credentials they need.
  All four original signing credentials are required before publication.
* CI now builds an explicitly unsigned Release candidate with R8 and validates
  its archive/package metadata. It records the source commit and checksum and
  uploads a seven-day review artifact. It does not create a replacement signing
  key or replace the public APK channel.

## Limits retained explicitly

The snapshot is single-process and limited to 8 MiB; the queue is bounded to
1,000 inbox rows, 1,000 outbox rows and 10,000 receipt ids. Capacity failures
preserve durable state. User-facing history deletion/retention and capacity UX
are not implemented by this core. The UI must consume stable inbox rows and use
DirectSignalDelivery instead of the low-level CryptoSession.decrypt API.

Verified pairing UI, automatic replenishment/rotation, lost-device recovery,
anti-rollback against an older valid snapshot, hardware-backed signing-key
migration and physical Android lock/backup/restore testing remain open. This
change does not make those gaps disappear. mayClaimE2ee remains false, and new
Signal end-user messaging is not switched on merely because these tests pass.

## Verification

The SDK-independent verification/security Gradle project includes the queue and
transport coordinator, native libsignal tests, HTTP handlers and separate-JVM
restart fixtures. Cases cover failed commit, retry, deduplication, concurrency,
changed envelope contents, native replay rejection, recovery before UI delivery
and ACK-response loss. Synthetic plaintext files are used only by JVM lifecycle
fixtures and deleted afterward; those tests do not verify Android Keystore.

The Android workflow must pass the full unit suite and actual unsigned Release
build. An unsigned artifact is a build-review candidate, not an installable or
published final update. Signed publication still uses the existing android.yml
workflow dispatch and requires the original signing identity.
