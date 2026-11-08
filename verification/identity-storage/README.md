# Identity storage failure contracts

Run `gradle --no-daemon -p verification/identity-storage test` with JDK 17 and
Gradle 8.14.3. The dedicated workflow pins those versions. This compiles the actual
production secret migration and identity validation policies without an Android
SDK. All identities and secret values in these tests are synthetic.

Tests cover unavailable encryption, failed writes/read-back, interrupted legacy
cleanup, competing first-use callers, corruption, mismatched key pairs, incorrect
EC curves and redacted errors. In-memory preference adapters deliberately inject
storage failures. They do **not** implement encryption or prove Android Keystore
behavior. Existing Android CI additionally compiles the bridge/UI and runs these
tests in the app's test suite. Physical-device locking, backup/restore, Keystore
invalidation and hardware-backed key acceptance still need device validation.
