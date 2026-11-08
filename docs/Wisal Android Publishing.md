# Publish a Wisal Android maintenance update

Prepared for Ahmed Zaian. 9 November 2026, Dubai.

The repository had zero Actions secrets when checked for this iteration. The
previous publication attempt stopped before building because the original
keystore secret was absent. Repeated permission to publish cannot reconstruct a
missing private key from an APK or certificate.

## Restore the existing identity

Use the original .jks/.keystore file and its existing alias/passwords. Add these
repository Actions secrets in GitHub settings, not in issues, source or chat:

| Secret | Value |
|---|---|
| KEYSTORE_BASE64 | Base64 of the original keystore file |
| KEYSTORE_PASSWORD | Original keystore password |
| KEY_ALIAS | Original signing alias |
| KEY_PASSWORD | Original key password |

Settings: https://github.com/ahmadzayan-hub/wisal/settings/secrets/actions

The checksum-matched published android-latest APK was com.wisal.app, version
1.0.4, versionCode 4. Its signing certificate SHA-256 was:

37c512f0eee1147c1631c14606ec592b990b48e50a71d0c688396b9ee78a00e9

This identifies the published distribution, not an inspection of a physical
phone. If the installed app came from a different build, verify its certificate
and version before updating. Do not uninstall to get around a mismatch.

## Run the existing publisher

Open Actions > Build Android APK > Run workflow on main. Set publish_release to
true, supply a versionCode higher than both the published and installed version,
and supply the expected existing certificate SHA-256. For the known public
versionCode 4, the prepared next maintenance code is 5, subject to checking the
installed version. The workflow validates credentials, builds APK/AAB and verifies
the resulting APK's certificate before replacing android-latest.

No replacement key, debug-key distribution or unsigned public APK is an
acceptable substitute for the original signing identity. WisalUnsignedCandidate
artifacts are for build review only. New E2EE activation remains outside the
maintenance publisher until its physical-device and application gates pass.
