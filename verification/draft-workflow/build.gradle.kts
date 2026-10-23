plugins { kotlin("jvm") version "2.0.21" }
repositories { mavenCentral() }
kotlin { jvmToolchain(21) }
sourceSets {
    main {
        kotlin.srcDir("../../android-wife-assistant/app/src/main/java")
        kotlin.include("com/wifeassistant/data/DraftWorkflow.kt", "com/wifeassistant/data/GenerationProtocol.kt", "com/wifeassistant/data/AppDiagnostics.kt")
    }
    test {
        kotlin.srcDir("../../android-wife-assistant/app/src/test/java")
        kotlin.include("com/wifeassistant/DraftWorkflowTest.kt", "com/wifeassistant/AppDiagnosticsTest.kt")
    }
}
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation("junit:junit:4.13.2")
}
tasks.test { maxParallelForks = 1 }
