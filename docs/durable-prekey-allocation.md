# Durable pre-key allocation

`LibsignalCryptoProvider.nextPublishableBundle(deviceId)` prepares one fresh
Signal bundle using the persistent store's independent signed, Kyber and
one-time pre-key high-water marks. Allocation, key generation and verified
snapshot commit share the existing process-wide lock. A caller receives no
public bundle until its private keys are durably committed and read back.

This removes the need for a caller to guess increasing IDs after restart or
pre-key consumption. It requires `SignalStatePersistence`; the explicit
in-memory development provider refuses automatic allocation. The existing
`publishableBundle` API with caller-supplied IDs keeps its behavior.

Existing keys are retained so delayed messages can still use a previously
published bundle. Each ID space fails closed at `Int.MAX_VALUE`, without wrap,
partial snapshot updates or silently rotating the identity. The existing
8 MiB snapshot limit still applies; this API does not prune keys.

The caller must retain the returned public bundle for publication retries;
calling this method again intentionally prepares a different bundle. This
method makes no relay request and starts no scheduler. The isolated
[pre-key maintenance integration](WisalPreKeyIntegrationReview.md) reuses this
allocation boundary and commits a pending publication journal with the keys.
It adds policy-driven explicit invocation and relay-status reconciliation,
without enabling an application trigger. Scheduling, signed-key retirement,
verified pairing UI, lost-device recovery and physical-device/backup acceptance
remain separate work. Restoring an older
valid snapshot is still outside the rollback protection this store provides.
`mayClaimE2ee` remains false.

The SDK-independent security harness compiles the actual implementation and
`SignalPreKeyAllocationTest`. It checks restart with different high-water marks,
concurrent providers, old ciphertext after replenishment, consumed-ID rejection,
commit failure, exhaustion of each counter, owner mismatch and ephemeral refusal.
Fixtures use synthetic identities and memory-backed snapshot callbacks; they
do not establish Android Keystore or physical-device acceptance.
