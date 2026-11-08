# Relay client JVM contracts

This module compiles the actual Android transport, P-256 identity codec, wire DTO
and proof functions on JDK 17, without an Android SDK or native Signal library:

```sh
gradle --no-daemon -p verification/relay-client test
```

Gradle 8.14.3 is pinned in the dedicated CI workflow. Tests use loopback
MockWebServer and generated synthetic device identities. They cover existing
register/envelope/inbox/ack calls and new publish/claim/self-revocation calls,
canonical proof strings, valid P-256 signatures, invalid inputs, mismatched
responses, exhausted keys, malformed responses and refusal to follow redirects.
The new operations make no automatic retry; a claim consumes a one-time key.

`SignalPreKeyBundleDto` is the existing public wire model, now in a standalone
source file. Structural checks are not Signal key/signature verification; that
remains the job of libsignal when establishing a session. No production service,
private device identity, database or messaging UI is exercised here.

Full Android compilation/unit tests remain in `android.yml`. This independent
suite does not establish Android Keystore persistence, offline two-device
delivery, replenishment/rotation policy, UI wiring, or E2EE release readiness.
