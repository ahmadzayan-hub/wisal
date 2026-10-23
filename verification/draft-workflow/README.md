# SDK-free contract tests

This small JVM harness compiles the actual Android-independent DraftWorkflow, GenerationProtocol and AppDiagnostics sources with their JUnit tests. It does not copy production logic or call a live provider.

With JDK 21 and Gradle dependency access:

```sh
../../android-wife-assistant/gradlew test --no-daemon --max-workers=1
```

Full Android compilation, Robolectric flows and Compose captures still require the Android project's `testDebugUnitTest` workflow. Passing this focused harness alone is not a full Android pass.
