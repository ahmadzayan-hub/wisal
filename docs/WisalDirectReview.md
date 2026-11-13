# Wisal Direct two-device review

The `directReview` Android build opens a dedicated test UI in `com.wisal.app.directreview`. It uses the project’s actual Signal, encrypted storage, Android Keystore, WorkManager, and Direct Relay code. It uses the development signing certificate. The application package and evidence are for synthetic testing. Release acceptance is `HOLD` and `mayClaimE2ee=false`.

## Prepare the operator workstation

1. Use this source revision and the supplied APK hashes. Build with `./gradlew :app:assembleDirectReview :app:assembleAcceptance :app:assembleAcceptanceAndroidTest` in `android-wife-assistant` if the supplied packages need to be rebuilt. Record the new APK hashes and certificate fingerprints.
2. Start a disposable local PostgreSQL server and create a database named `wisal_acceptance_test`. Use `npm ci --ignore-scripts` in `wisal-direct-relay` to install the locked dependencies.
3. Create a test-only CA and issue a server certificate for `localhost`. Verify that the leaf certificate has `DNS:localhost` in its SAN. Set `DATABASE_URL` to the dedicated database on `127.0.0.1`. Set `WISAL_REVIEW_TLS_KEY`, `WISAL_REVIEW_TLS_CERT`, and `WISAL_REVIEW_FAULT_FILE` to private local paths. Set `NODE_ENV=production` and run `node verification/device/StartDirectReviewRelay.cjs` from the repository root. The helper binds to `127.0.0.1:8443` and prints the source and schema digests. Record them before and after the run.
4. From a trusted local terminal, use `curl --cacert Root.crt https://localhost:8443/__wisal_review` to retrieve the source, Relay and PostgreSQL versions, and schema digest. Obtain the expected public-key pin from the test server certificate with the commands below. Keep the certificate and pin on the test workstation and distribute the public CA and expected pin to the operator through the trusted test channel.

```sh
openssl x509 -in Localhost.crt -pubkey -noout \
  | openssl pkey -pubin -outform DER \
  | openssl dgst -sha256 -binary \
  | openssl base64 -A
```

Prefix that output with `sha256/` when entering it on Android. Install the public test CA into the dedicated test profile on each ARM64 device. The test application limits that user trust store to `localhost` and checks the configured SPKI pin. Run `adb -s SERIAL reverse tcp:8443 tcp:8443` separately on A and B. Repeat the reverse mapping after reboot when necessary. Use the APK’s `com.wisal.app.directreview` package, and use the separate acceptance APKs and `CollectDeviceEvidence.py` for the instrumented phases.

## Two-device sequence

1. Install the same review APK on A and B. Record each device’s model, Android version, APK hash and signing certificate. On both devices, enter the test relay pin and register. On a fresh test profile, first enter a deliberately incorrect pin and verify that no registration is written. Then enter the correct one.
2. Exchange the public pairing codes. Read the full expected fingerprint from the other physical device, independently of the supplied code. Enter both values on each device. Confirm that a substituted code fails against the expected fingerprint. Establish the pairing in both directions before sending.
3. Queue an initial message on A. Sync A, then B, and send a reply. Confirm the received messages appear after app recreation. Use a synthetic canary and compare against Relay request bodies, database exports and logs in the isolated test environment.
4. On an established session, disconnect the intended recipient while the sender syncs. Restore the reverse mapping and sync the recipient. Verify that a repeated envelope with identical ciphertext produces one inbox entry. Exercise expiry, process death and actual reboot separately.
5. To inject an HTTP response loss, write `{"id":"E03After","route":"/api/prekeys","at":"after"}` to the configured fault file. A fresh ID applies once during the Relay process lifetime. The `before` mode drops the request before a handler writes. The `after` mode drops only a successful response. `/api/envelopes` and `/api/ack` are also supported. Keep the metadata-only fault log with the client evidence.
6. Record whether automatic renewal runs after initial enrollment, after reconnecting and after restarting. WorkManager runs periodically subject to Android constraints. Use the instrumented build for a separate record of storage, process recovery and wrapping-key loss.

## What each result can establish

| Gate | Evidence to attach | Remaining requirement |
|---|---|---|
| E01 to E04 | Signed review APK and Relay hashes, two-device fingerprint and delivery traces, fault order, no-plaintext scan and reboot evidence | Execute on both physical devices |
| E05 and E06 | WorkManager observation, key IDs before and after rotation, delayed-message success before expiry, and refusal after the initial claim deadline | Physical scheduling and delayed-message behavior across the supported window. Review sends use a one-day absolute deadline for initial pre-key ciphertext. Triplet retirement is measured from confirmation of replacement and uses 14 days plus the existing 300-second skew window |
| E07 and E08 | Device-specific Keystore level, key loss behavior, cloud and OEM transfer evidence, and manual backup inspection | Signed release upgrade and legacy signer migration. Review enrollment requires a hardware-backed signer. Existing encoded identities are preserved for legacy flows and cannot be enrolled into Direct through this review path |
| E09 | Retained local checkpoint, rejected older snapshot, PostgreSQL rollback trial and verified original APK signing certificate | A local checkpoint detects restoring only the snapshot. Full local rollback and restoring PostgreSQL to before a claim still require protection outside the restored state |
| E10 | Relay records and two-device evidence for self-revocation and peer substitution refusal | Expiring single-use invitations and recovery or revocation after losing the signing device |

Use the same APK SHA-256, Relay source and schema digests for all evidence presented for acceptance. Keep the independent security review pending until the remaining release requirements and physical results can be reviewed together.
