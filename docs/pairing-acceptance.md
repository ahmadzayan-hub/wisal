# Durable local invitation acceptance

This slice repairs local invitation acceptance. It does not enable encrypted
messaging or certify a person's identity.

The accept screen reads the clock again when the user presses Accept. Invitations
which expire while the screen is open are refused. Import and acceptance validate
the bounded payload, exact link prefix, canonical invitation/device identifiers,
32-byte token, supported P-256 public key and a positive lifetime of at most
48 hours. Future-created invitations are refused until their creation time.

The existing public peer list and accepted-invitation IDs remain in their existing
preferences keys and format. They are now read under one process-owned store and
written with one synchronous SharedPreferences commit. Success requires a true
commit result and matching read-back; ambiguous writes block the store for the
rest of the process because Android may retain failed writes in memory.

Replay, concurrent duplicate acceptance, duplicate device IDs and changed public
keys cannot add or replace a peer. A changed identity needs a separate, verified
recovery flow; this code does not silently approve it. Invalid or incomplete
existing preferences fail closed and are preserved, not replaced with empty
history. Other settings and application data are unchanged.

## Verification

The SDK-independent module compiles the actual invitation and persistence-policy
sources. Run from the repository root:

```sh
bash android-wife-assistant/gradlew --no-daemon -p verification/pairing test
```

On Windows use `android-wife-assistant\gradlew.bat` with the same arguments.
The full Android workflow separately compiles the preferences adapter and runs
Compose/Robolectric scenarios for acceptance, reopen, late expiry, corrupt storage
and changed-key refusal. All identities and stored values in tests are synthetic.

Passing core tests is not a claim that Android tests, device power-loss recovery,
Keystore, backup/restore, signed release continuity or physical devices passed.
The public metadata store does not add hardware-backed integrity or anti-rollback.
The one-time token is only enforced against local history: an offline copied
invitation cannot obtain authoritative remote revocation/global single-use status.
Verified out-of-band pairing, P-256/Signal binding exchange, messaging UI,
replenishment/rotation, lost-device recovery and independent security review
remain separate gates. `mayClaimE2ee` is unchanged.
