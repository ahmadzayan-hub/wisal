# Wisal Android device acceptance

Prepared for Ahmed Zaian. 9 November 2026, Dubai.

## Status and boundary

The kit compiles Android instrumentation against the actual application classes.
Compilation and artifact upload do not execute these tests. Record NOT_RUN until
an attached device returns a complete instrumentation result. A passing isolated
test is supporting evidence only: the release gate remains HOLD and
mayClaimE2ee=false. This is not an end-user Signal messaging implementation.

The application id is com.wisal.app.acceptance and its test id is
com.wisal.app.acceptance.test. It uses synthetic identities in a separate UID,
with test-only debug signing. It cannot update com.wisal.app. Its main activity is
disabled. Never use a production account or real messages in this kit.

The collector never installs, clears data, uninstalls, reboots, pulls private
preferences or dumps logcat. It checks installed APK SHA-256 against supplied
artifacts, stops only the acceptance application, runs a selected phase, and
records a summary. It hashes the device serial and instrumentation output rather
than saving either raw value. Compare source SHA and checksums to the matching
GitHub Actions artifact; a command-line source label alone is not attestation.

## Run on an authorised ARM64 test device

Requirements: Python 3.11+, Android platform-tools/adb, USB debugging authorised
for this workstation, unlocked device, ARM64 Android API 26 or newer. Do not
uninstall a production app to solve any signing conflict. Use a dedicated test
device for the key-loss scenario.

Download WisalDeviceAcceptance from the successful workflow for the selected
source commit. Verify Kit Checksums.txt locally. Install only these two isolated
packages using adb -s SERIAL install -r WisalAcceptance.apk and adb -s SERIAL
install -r WisalAcceptanceTests.apk. If an older acceptance kit has an incompatible
debug signature, select a fresh test device rather than deleting unknown data.

Run the following command with your device serial, full Source Commit.txt value
and a fresh output filename (one physical line in PowerShell or a terminal):

```text
python CollectDeviceEvidence.py --serial SERIAL --apk WisalAcceptance.apk --test-apk WisalAcceptanceTests.apk --source-commit FULL_SHA --phase prepare --output "Prepare Evidence.json"
```

Repeat the command with phase resume and output "Resume Evidence.json", then
phase verify and output "Verify Evidence.json". Each invocation stops and starts
a new instrumentation process. Between prepare and resume, separately perform a
real device reboot and unlock if collecting reboot evidence. The collector does
not infer a reboot from process death. Record the reboot steps and observed
device state in the operator report. A resume run is single-use; repeating it
with a changed TTL intentionally rejects reuse of the stable message id.

Finally run phase keyLoss with --allow-test-key-loss and output "Key Loss Evidence.json".
This deletes the wrapping key in the isolated acceptance UID. It deliberately
makes that test identity unrecoverable. Run last; do not attempt recovery by
creating a replacement identity. Existing evidence files are never overwritten.

Exit codes: 0 confirms one selected test passed; 1 means instrumentation did not
confirm success; 2 means prerequisites failed. ADB exit code 0 or a zero-test
run alone cannot pass acceptance. EMULATOR is explicitly labelled. A device
report labelled UNVERIFIED_PHYSICAL still requires operator confirmation of the
physical model and Android version.

## Prioritised acceptance matrix

All physical results below start NOT_RUN. Keep expected and actual results,
device/OS, source SHA, APK checksum, timestamp, operator and sanitised evidence.

| Priority | Scenario | Required result | Execution |
|---|---|---|---|
| P0 | Concurrent first identity requests | All 12 calls return one identity, no silent overwrite | prepare on device |
| P0 | Malformed stored identity | Fail closed; malformed value is preserved until explicitly restored by the synthetic fixture | prepare on device |
| P0 | Encrypted storage | Raw encrypted preference values contain neither serialized identity nor private key; legacy store empty | prepare on device |
| P0 | Keystore wrapping-key loss | Existing ciphertext preserved; identity access fails; no plaintext fallback or replacement identity | keyLoss, isolated test UID only |
| P0 | Installed-app upgrade | Original release certificate matches; update preserves identity and data | Physical signed release upgrade, separate from this kit |
| P0 | OEM/cloud/manual backup restore | Secrets excluded or restored records rejected safely; no invisible identity reset or valid-snapshot rollback | Physical target OEM/OS and documented backup paths; NOT automated |
| P0 | Two real devices and revocation | Verified peer binding, bidirectional delivery, offline/retry/replay, revocation race prevents new access | Two physical devices and real staging relay; NOT automated by this kit |
| P1 | Signal after process death | Session, outbox, inbox and pending ACK recover; identity unchanged | prepare, resume, verify as separate invocations |
| P1 | Signal after reboot | Same persisted session and identity after actual reboot/unlock | Same phases with operator-observed reboot |
| P1 | Locked/before-first-unlock | Secrets unavailable safely; no rotation or fallback | Physical lock/boot transitions; NOT automated |
| P1 | Key hardware properties | Record KeyInfo/TEE or StrongBox properties and migration plan; wrapping encryption is not non-exportable P-256 identity storage | Physical security review; NOT automated |
| P1 | Corrupt/interrupted Signal write | No ACK or ratchet advance without durable inbox/outbox commit | Existing synthetic JVM fault injection, plus physical power/interruption testing |

The in-device Signal test uses two synthetic participants on one phone. It is
not a two-phone exchange, relay integration, user-facing pairing verification,
hardware non-exportability proof, or backup/rollback resistance proof. Keystore
key deletion is not equivalent to every OEM invalidation/lock failure.

## Build from source and documentation

Run ./gradlew assembleAcceptance assembleAcceptanceAndroidTest in
android-wife-assistant. Android CI compiles and checks both package ids and APK
signatures, runs the evidence parser tests, and uploads the kit. No connected
device is implied. The production APK still requires its original keystore.

Android's runner and variant setup follow the official documentation:
https://developer.android.com/training/testing/instrumented-tests/androidx-test-libraries/runner
https://developer.android.com/studio/test/advanced-test-setup
Pinned test-only runner/ext versions align with the existing test-core 1.6 family:
https://developer.android.com/jetpack/androidx/releases/test
