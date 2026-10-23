# Safe Wisal releases

## Android: preserve the installation

The current package is `com.wisal.app`, source namespace `com.wifeassistant`, minimum API 26, ARM64 only. Do not rename storage keys or change package identity to work around an upgrade issue.

Before publishing an upgrade:

1. Export an in-app backup and keep it private. A JSON backup does not preserve every integration secret or device identity.
2. Record the installed versionName/versionCode and its signing-certificate SHA-256 fingerprint from a trusted build/device inspection. Do not assume this new repository's CI run counter exceeds builds from the previous repository.
3. Locate the **existing** upgrade-compatible signing key. Do not generate a new key as a substitute. If the key is lost, stop and plan a migration with the owner instead of asking them to uninstall.
4. Verify that the source commit's tests passed. Inspect UI render artifacts and perform real-device upgrade validation before calling a release production-ready.
5. Explicitly dispatch **Build Android APK**, enabling `publish_release`, supplying a higher `release_version_code` and the expected existing `signing_sha256`.

The workflow uses already-configured repository secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Creating or changing credentials is a separate owner-approved operation. Never put these values in source, logs, issues or chat.

Pull requests and ordinary main pushes test source only. They cannot overwrite `android-latest` or `apk-dist`. Missing signing secrets block publication. Gradle does not fall back to a freshly generated debug identity for release builds. Existing release assets are not pruned as an incidental cleanup.

The workflow verifies the built APK certificate against the supplied fingerprint before publishing. The owner still needs to establish that this fingerprint belongs to the installed app. A signed AAB alone does not mean an app meets Google Play submission requirements.

## Download channels

- [Android channel](https://github.com/ahmadzayan-hub/wisal/releases/tag/android-latest)
- [Windows channel](https://github.com/ahmadzayan-hub/wisal/releases/tag/windows-latest)

A rolling release may predate the current source. Verify commit provenance and checksums. Never advise users to bypass a signing conflict or an unverified operating-system security warning.

## Web and services

`wisal-web/` remains a static site; preserve the configured hosting project and use that folder as its root. Its source includes production URLs that should be verified against the actual configured domain before claiming a live deployment.

The cloud API, Direct relay and Telegram bot retain their existing deployment boundaries. Source changes do not prove any service was deployed. Do not create new cloud accounts, credentials or paid services as part of a routine UI update.

For Telegram hosted generation, `GROQ_ENABLED=false` is the safe default. Set it to true only after reviewing the account's plan and prompt-data transmission, and explicitly select `GROQ_MODEL`. Local templates remain available without hosted generation.
