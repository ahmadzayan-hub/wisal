# Wisal pre-key publication integration review

Author: Ahmed Zaian

Review date: 11 November 2026, Asia/Dubai

Disposition: isolated review change; operational activation and E2EE acceptance remain HOLD.

## What this change establishes

`DirectPreKeyMaintenance.runOnce(identity)` connects the local policy, real
libsignal generation, verified snapshot storage, Direct Relay publication and
publication confirmation. It is an explicit bounded call. No application screen,
startup hook, WorkManager job, periodic scheduler or background service invokes it.
No real device or deployed relay is used by its tests.

The device must already be registered. The integration does not register, revoke,
replace identities, pair users, repair old snapshots or migrate live data.

The review branch builds on PR #20. PR #18 is already in its ancestry. PR #19's
pairing implementation is tested in a separate local combined checkout, without
copying that implementation or merging either PR into main.

| Dependency | Examined immutable reference | Reuse |
| --- | --- | --- |
| PR #18 | `9b2cb3fd681de50fccacc6bd62d71a76ffae5cb6` (main merge) | Existing independent high-water counters, native key generation and verified commit boundary |
| PR #19 | `cea77a1f46d2f1ae2d039db98f5246b984eab9aa` | Pairing acceptance remains unchanged; compatibility tested separately |
| PR #20 | `a926d6bc7aa9b3fc0530c4e40e4be60cf207161d` | Existing seven-day local rotation decision, local inventory diagnostics and timestamp field |

PR #20 was created at 22:04 UTC on 10 November, which was 02:04 on 11 November
in Dubai. The source review uses the exact commit, not an assumption about dates.

## Where the original execution chain stopped

1. `PreKeyReplenishmentPolicy.evaluate` returned a decision only.
2. `LibsignalCryptoProvider.replenishmentDecision` read local private-key counts.
   Its historical `hasPublishedBundle` name actually meant a local signed key had
   been generated. It did not prove relay publication.
3. `nextPublishableBundle` already committed private keys and increasing IDs
   before returning a public bundle. It did not journal the caller's intention
   to publish or persist an acceptance result.
4. `DirectRelayClient.publishPreKeys` signed the request and checked a matching
   device/key receipt, but no durable component connected these steps.
5. The relay rejected any attempt to republish a consumed one-time key. If a
   successful publication response was lost and a peer then claimed the key,
   blindly retrying the same request could never reconcile the result.

Local and relay inventory are different. A relay claim removes the public
one-time key before the recipient receives/decrypts the message and removes its
private key. Replenishment based on the local count alone misses this interval.

## State and transaction boundaries

`PreparedPreKeyPublication` stores the complete public bundle, generation time,
canonical relay scope and existing P-256 publisher public key. Private material
stays in the existing Signal snapshot. The snapshot now has separate
`pendingPreKeyPublication` and `acceptedPreKeyPublication` fields.

Preparation shares one existing persistence transaction with native key
generation and all three high-water counters. Exact read-back must succeed
before the bundle can reach the relay. A failed or ambiguous write withholds the
result and blocks that provider instance until restart. Android's existing
`AndroidSignalProvider` remains the owner of the process singleton and encrypted
storage adapter. No unchecked preferences store was introduced.

Acceptance is a second verified local transaction. It reloads the current
snapshot and checks the exact pending object before moving it to accepted, so
intervening session/message updates are retained. HTTP success alone does not
clear the journal.

Snapshot loading validates journal ownership, the Signal identity, registration
and key IDs, retained signed/Kyber public keys and signatures, and the one-time
public key when its private record remains. Corruption is preserved and refused.

| Local state | Observed relay state | Action |
| --- | --- | --- |
| No journal | No publication | Evaluate policy, prepare and publish one bundle |
| Pending | Exact same bundle, available | Commit acceptance without resending |
| Pending | Exact same bundle, consumed | Commit acceptance without restoring the key; a later call can replenish |
| Pending | Exact previous accepted bundle | Retry the same pending bundle with a newly signed timestamp |
| Pending, first publication | No publication | Retry the same pending bundle |
| Accepted | Exact same bundle, available and not due | Return CURRENT |
| Accepted | Exact same bundle, consumed or age due | Prepare one fresh triplet with increasing IDs |
| Accepted | Missing/different/unknown publication | Stop for review |
| No journal | Existing remote publication | Stop for explicit legacy migration review |
| Any journal | Different relay or publisher identity | Refuse before network access |

A process-wide coroutine mutex serializes reopened coordinators across the
complete operation. The native storage lock is not held over network I/O. This
is single-process coordination, not a distributed or multiprocess lease.

## Non-consuming publication status

New review-only endpoint:

```text
POST /api/prekeys-status
prekeys-status:{deviceId}:{timestamp}:{requestNonce}
```

The owner signs the request with the existing P-256 device identity. The relay
checks registration, signature, revocation, a positive safe-integer timestamp
within 300 seconds, and a 32-character random hexadecimal nonce. The response
echoes the owner and nonce and returns a complete bundle digest, one-time key ID,
availability, publication time and server time. It does not return key bytes,
consume a key, reserve availability or replenish anything.

The digest is SHA256 over UTF-8 bytes of the existing public bundle field order,
prefixed with `wisal-prekey-bundle-v1`. The relay computes and stores it at
publication. It survives removal of the one-time public key during claim. The
PostgreSQL change adds a nullable `bundle_digest TEXT` column. This additive
schema change was exercised only on disposable local databases.

Android requires HTTPS, except literal loopback HTTP for tests. It rejects URL
credentials, query strings and fragments; binds the journal to the normalized
relay URL; and checks response owner, nonce, digest, key ID and time. Automatic
HTTP retries and redirects remain disabled. Confirmation compares the digest
of the entire prepared bundle, not just the key ID.

This is an HTTPS-authenticated service observation. It is not an independently
signed server attestation or proof of complete E2EE. A key can be claimed just
after status is read; `oneTimePreKeyAvailable` is a point-in-time observation.

Legacy unclaimed relay bundles can be observed without mutation if their public
fields remain intact. A consumed legacy bundle with no stored digest returns
`publication receipt unavailable`. The new coordinator does not silently adopt
legacy remote publications. Existing consumed-key replay rejection remains in
place.

The new status validation also prompted a narrow repair of fractional/unsafe
timestamp acceptance in existing publish/claim handlers. A regression reproducer
fails against PR #20 and passes against this review change.

## Failure handling

| Failure | Verified or intended behavior |
| --- | --- |
| Offline before status | No new pre-keys or publication request; first local access may initialize the existing Signal identity |
| Publication unavailable before acceptance | Durable pending bundle stays; explicit retry uses the same public bytes and IDs |
| Accepted publication response lost | Pending stays; next invocation reconciles exact status |
| Response lost, then key claimed | Reconcile retained digest without republishing or reviving the consumed key |
| Status failure after successful publish | Preserve pending; do not report accepted locally |
| Preparation write/read-back fails | No publication request; provider remains blocked after ambiguous write |
| Acceptance write fails | Do not report success; reopen/restart and reconcile durable state |
| Concurrent invocations | Serialize and observe the same accepted bundle; no extra ID allocation |
| Cancellation after remote acceptance | Propagate cancellation and retain pending for later reconciliation |
| Clock backwards before persisted generation | Explicit clock error; do not report CURRENT |
| Clock jumps after preflight | Bound subsequent samples to the observed relay time before staging/publication; stop instead of poisoning the rotation timestamp |
| Clock failure after publication | Report uncertainty; retain pending because the relay may already have accepted it |
| ID exhaustion | Retain PR #18 fail-closed behavior; no wraparound or identity replacement |
| Remote state differs from journal | Stop for review; never infer acceptance from an error string |

There is no internal retry loop or persisted timer. Repeated invocations are
caller-controlled. A future scheduler needs its own reviewed connectivity,
backoff, lifecycle and device tests before activation.

## Policy interpretation and remaining gates

The default age trigger remains seven days. The relay currently stores one
available bundle, so maintenance uses a floor of one. PR #18 generates a complete
signed/Kyber/one-time triplet. One-time exhaustion therefore also rotates the
signed and Kyber keys earlier than seven days; it is not an OTP-only batch API.

Old private keys remain for delayed messages. There is no new retirement/pruning
policy. The existing 8 MiB snapshot ceiling still fails closed. These constraints
limit long-term unattended operation and must be reviewed before scheduling.

An already persisted far-future rotation time is detected but not silently
repaired. Restoring an older valid snapshot or a rolled-back relay database is
outside these guarantees. No claim of anti-rollback protection is made.

The snapshot still uses version 1 with additive fields. Older strict JSON readers
will reject new snapshots. Downgrades, backup restoration and device transfer
are not established. Do not clear identity or security data as a workaround.

Physical ARM64 devices, Android Keystore, two-device operational exchange,
backup/restore, independent security review and final E2EE acceptance remain
separate gates. `LibsignalCryptoProvider.mayClaimE2ee` stays `false`.

## Verification and reproduction

Fresh machine-readable results, raw logs, JUnit XML and source hashes are in
[`evidence/PreKeyIntegration20261111`](evidence/PreKeyIntegration20261111).
`VerificationSummary.json` is the authoritative count/status record.
`SourceManifest.json` binds the reviewed implementation/tests to the PR #20 base;
`EvidenceManifest.json` hashes the evidence files. Baseline results were rerun
against the immutable PR #20 archive and are retained separately.

Portable commands with JDK 17, Gradle 8.9, Node and PostgreSQL available:

```bash
gradle --no-daemon --console=plain -p verification/security test
gradle --no-daemon --console=plain -p verification/relay-client test
```

From `wisal-direct-relay`, install the locked dependencies with
`npm ci --ignore-scripts`, then run `npm run check` and `npm test` with
`REQUIRE_PG=1` and a disposable `RELAY_TEST_DATABASE_URL`. Do not point the tests
at a live database: the existing database test resets its test schema.

The separate PR #19 combined checkout additionally runs
`gradle --no-daemon --console=plain -p verification/pairing test` and the full
security module. Its pairing source hashes must still match PR #19 exactly.

Local native Signal tests use synthetic identities, memory callbacks and, for
process restart, disposable plaintext synthetic file fixtures. Actual HTTP tests
use the real Android transport and Node API handlers over loopback, with explicit
fault injection. These are distinct from Android encrypted storage acceptance.

## Review and operational boundary

The proposed GitHub PR is Draft and stacked on `feat/prekey-rotation-policy`
(PR #20), keeping the incremental review separate. Exact-branch Vercel opt-outs
prevent automatic preview deployments for `review/WisalPreKeyIntegration`; every
other configuration field is preserved. Existing Android PR CI can build test
artifacts and unsigned candidates, but release signing/publication requires an
explicit workflow dispatch and is not requested here.

Before an operational change, Ahmed Zaian must separately approve the exact
merge/deployment/device-test action. A merge can trigger the repository's existing
deployment behavior; do not equate approving code review with approving a live
relay schema update or Android release.

## Primary references

- [PR #18](https://github.com/ahmadzayan-hub/wisal/pull/18)
- [PR #19](https://github.com/ahmadzayan-hub/wisal/pull/19)
- [PR #20](https://github.com/ahmadzayan-hub/wisal/pull/20)
- [Signal PQXDH publication and delayed-key handling](https://signal.org/docs/specifications/pqxdh/#publishing-keys)
- [Kotlin coroutine mutex semantics](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.sync/-mutex/)
- [Kotlin cancellation and withContext](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/with-context.html)
- [Vercel branch deployment controls](https://vercel.com/docs/project-configuration/git-configuration#git.deploymentenabled)

The repository pins Kotlin 2.0.21, coroutines 1.9.0, serialization 1.7.3,
OkHttp 4.12.0 and libsignal 0.86.5. The checked source is compiled against those
versions; current documentation is used for API semantics, not to imply a
dependency upgrade.
