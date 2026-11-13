# Android relay key lifecycle transport

The Android `DirectRelayClient` now exposes three explicit caller operations:

- `publishPreKeys(identity, bundle, nowEpochSec)` signs the complete public bundle
  in the relay's canonical field order and validates the device/key receipt.
- `claimPreKeys(identity, targetDeviceId, nowEpochSec)` sends a signed POST and
  returns a complete structurally checked bundle only for that exact target.
  Missing/exhausted keys, wrong targets and malformed responses fail closed.
- `revokeDevice(identity, nowEpochSec)` sends a signed self-revocation request
  and succeeds only with a matching device ID and `revoked: true` receipt.

All three fail before network access on missing configuration or invalid input.
Only public material and signatures are sent. The shared HTTP client disables
automatic retries and redirects so a consumed one-time key is not silently
requested again and a signed body is not forwarded to another location.

The API and canonical proofs match `wisal-direct-relay/lib/prekeys.js` and
`lib/revocation.js`. The existing DTO fields are unchanged. Public wire validation
does not verify Signal signatures; `LibsignalKeys.toPreKeyBundle` and session
establishment retain that responsibility. No crypto primitive or protocol is added.

Validation is reproducible through [the JVM contract module](../verification/relay-client/README.md)
and the existing full Android PR workflow. All new tests use synthetic identities
and a loopback fake relay. They do not call a deployed relay or revoke a real device.

This completes the transport-method portion of Phase 3 item 9 only. No UI calls
these operations yet. Subsequent work adds durable Signal storage and monotonic
ID allocation. The isolated [pre-key maintenance integration](WisalPreKeyIntegrationReview.md)
connects explicit replenishment with durable intents and a non-consuming
publication-status endpoint. Automatic scheduling, lost-device recovery,
paired-device UI exchange and physical-device/offline/backup acceptance remain
separate gates. External security review is still pending. `mayClaimE2ee` stays false. The web/roadmap files touched by open
PR #10 are intentionally outside this change.
