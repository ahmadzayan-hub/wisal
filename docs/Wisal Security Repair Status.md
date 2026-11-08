# Wisal security repair candidate

Prepared for Ahmed Zaian. 9 November 2026, Dubai.

Status: maintenance hardening merged under explicit owner authorization in PR 13.
E2EE acceptance remains HOLD; this is not a claim that encrypted messaging is live.

## Current evidence after the original review

PR 13 was merged as bd83abdb84d5a7c2883141688492eb58ef398881.
Security CI 42521077776 passed native PostgreSQL and 69 JVM tests without skips.
Android CI 42521077784 compiled the adapter and passed 233 unit tests without skips.
The production website was deployed from this merge. Signed Android publication
is tracked separately and requires the existing certificate to match. Physical
device, anti-rollback, verified pairing UI and durable app-delivery acceptance
remain open. The sections below preserve the earlier local review evidence.
Based on PR 11 `824eda74db9faffbca59b7353f4a3b64b34696de` and PR 12
`544270191e176f3e1da30ca3a87ec2e0faddcd71`.

## Implemented changes

* Memory storage checks both device states at the write/claim boundary. PostgreSQL locks all involved device rows in sorted order in the same transaction as the write/claim. Revocation's existing row update serializes against these locks. A submit refused by storage cannot produce a success receipt. Claim now passes the requester id into storage.
* SignalStatePersistence serializes native libsignal records, local identity, registration id, peer pins, prekeys, sessions, Kyber replay markers and consumed-key high-water marks. Each operation reloads a private working copy under a process-wide lock. It commits one snapshot and checks read-back before releasing output. Protocol failures discard the working copy. Failed/unverified commits block that instance; the Android SecureStore boundary also blocks secret access after failed commits.
* AndroidSignalProvider connects the snapshot callbacks to SecureStore and binds the snapshot to the saved P-256 device id. This adapter has NOT been compiled or exercised in a full Android build here. Callbacks in JVM tests deliberately use synthetic memory/files; those fixtures do not prove at-rest encryption.
* Repeated prekey ids fail before replacing pending keys. Persistent high-water marks prevent reuse of a consumed id after reopening. Callers must supply fresh increasing ids when replenishing; an automatic rotation/replenishment scheduler is not implemented.
* SignalPeerBinding signs a versioned, domain-separated binding of device id, registration id and Signal identity key with the existing P-256 signing key. Persistent peer trust requires explicit pinning with this proof and refuses unbound or changed identities. The trusted P-256 public identity must come from verified pairing, not from the relay alongside the proof.
* The release factory rejects an implicit ephemeral Signal store. mayClaimE2ee remains false. The explicit memory constructor remains available to existing tests/development code.
* npm test now includes the deterministic revocation acceptance suite. A new SDK-independent Gradle verifier and pull-request CI configuration require native PostgreSQL tests with REQUIRE_PG=1. The workflow was prepared but NOT run on GitHub.

## Verified evidence

* 69 JVM JUnit tests passed, compiled from the actual Kotlin sources with Kotlin 2.0.21, JRE 17, serialization 1.7.3, libsignal-client 0.86.5, OkHttp 4.12.0 and coroutines 1.9.0. This direct compiler/JUnit invocation is not a full Android or Gradle build.
* Tests include the original identity contracts, 5 cross-layer storage contracts, Signal protocol tests, snapshot failures, concurrent first use across providers, changed/unbound peer rejection, failed ratchet commit, owner mismatch, and release factory refusal.
* Two separate JVM subprocesses persist synthetic snapshots to test-owned files, reopen sessions, decrypt a queued Whisper message, reply, reject replay and preserve identities/high-water marks. They do not exercise Android process lifecycle or Keystore.
* An HTTP integration test uses the real DirectRelayClient and real Node API handlers over loopback, real Signal ciphertext and synthetic P-256 signatures. It tests publication, one-time claim, offline delivery, acknowledgement, reply after reload and post-revocation refusal. The relay is in memory, not production PostgreSQL.
* Ten relay security acceptance tests pass, including the three previously red revocation interleavings. The five existing relay assertion suites also pass. Native PostgreSQL's existing test is SKIPPED locally because no test database is configured.
* Thirteen sequential SQL assertions pass against PGlite 0.5.15 executing pgStore's actual PostgreSQL SQL. This checks SQL syntax/sequential behavior, not independent-connection row locks, deadlocks or crash recovery.
* Mutation checks: removing the new atomic publication guard makes R04 fail. Removing the prekey collision guard makes the pending-message regression fail. Both mutations were restored before final tests.
* Relay npm audit found zero known advisories against the unchanged dependency lockfile. This is not an external cryptographic review or a complete Android dependency audit.

## Blocking acceptance still open

1. Run the new REQUIRE_PG=1 suite on native PostgreSQL with separate connections, including races and transaction rollback. SQL embedding is not sufficient evidence. No production database was used.
2. Compile the full Android app, test the SecureStore adapter and verify signed build continuity on the intended ARM64 devices. This environment lacks Android SDK/adb/devices. The app's P-256 private key is currently exportable and encrypted by the Keystore-backed preferences boundary; hardware-backed non-exportable migration remains a documented production gate.
3. Test two physical devices: locking/unlocking, reboot before/after first unlock, alias failure/invalidation, interrupted commits, OEM/cloud transfer, manual backup/restore and reinstall. allowBackup=false alone is not evidence of OEM behavior.
4. Wire verified P-256/Signal binding exchange, explicit confirmation, durable provider and relay lifecycle into the actual Android pairing/messaging flow. The new binding API and loopback test do not prove the existing UI does this. Never take the trust anchor from an untrusted relay response.
5. Complete the application's durable outbox/inbox/ack contract. Persisting the ratchet before returning plaintext does not atomically persist the user's delivered message; a process crash between decrypt commit and app delivery must not lose the message. No such UI/message repository transaction is claimed here.
6. Prove rejection of older valid snapshots/backup rollback and define explicit identity-loss recovery. The snapshot adapter does not itself provide rollback resistance across a restored valid encrypted snapshot. It supports one Android process only, and is limited to 8 MiB and 1:1 Signal sessions. Group sender-key storage is deliberately rejected.
7. Define lost-device revocation/recovery and key rotation. Current relay revocation is self-revocation only; it cannot revoke an unavailable stolen device from another trusted device.
8. Complete an independent security review and present device results and residual risks before enabling E2EE. The owner has explicitly authorized maintenance merging and deployment; this does not close the E2EE acceptance gates.

## Reproduce

From a checkout containing the two base PR snapshots and this candidate:

```bash
cd wisal-direct-relay
npm ci --ignore-scripts
npm run check
npm test
```

For real SQL acceptance, supply an isolated disposable test database in
RELAY_TEST_DATABASE_URL and set REQUIRE_PG=1 before npm test. The existing
pg-store.test.js deletes relay_* tables; NEVER point it at production. The new
acceptance tests create/drop their own randomly named schemas. CI credentials
are synthetic test credentials only.

With JDK 17, Node, Gradle and dependencies available:

```bash
gradle --no-daemon -p verification/security test
```

This verifier compiles actual core/transport sources without an Android SDK.
Run the existing Android build/unit-test workflows separately. No CI success is
claimed merely because the configuration was added.

## Sources inspected

The Java APIs and serialization behavior were checked against the published
[libsignal-client 0.86.5 source artifact](https://repo.maven.apache.org/maven2/org/signal/libsignal-client/0.86.5/libsignal-client-0.86.5-sources.jar), especially InMemorySignalProtocolStore,
InMemoryKyberPreKeyStore and InMemoryIdentityKeyStore. Existing protocol and
transport source came from [PR 11](https://github.com/ahmadzayan-hub/wisal/pull/11)
and [PR 12](https://github.com/ahmadzayan-hub/wisal/pull/12).
