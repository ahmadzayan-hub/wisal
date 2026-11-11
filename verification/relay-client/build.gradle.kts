plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
}
repositories { mavenCentral() }
kotlin { jvmToolchain(17) }
sourceSets {
    main {
        kotlin.srcDir("../../android-wife-assistant/app/src/main/java")
        kotlin.include("com/wifeassistant/data/crypto/DeviceIdentity.kt", "com/wifeassistant/data/crypto/SignalPreKeyBundleDto.kt",
            "com/wifeassistant/data/pairing/DirectRelayClient.kt", "com/wifeassistant/data/pairing/DirectRelayProofs.kt",
            "com/wifeassistant/data/pairing/PreKeyPublicationTransport.kt")
    }
    test {
        kotlin.srcDir("../../android-wife-assistant/app/src/test/java")
        kotlin.include("com/wifeassistant/DirectRelayClientTest.kt", "com/wifeassistant/DirectRelayProofsTest.kt", "com/wifeassistant/DirectRelayKeyLifecycleTest.kt",
            "com/wifeassistant/PreKeyPublicationTransportTest.kt")
    }
}
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
tasks.test { maxParallelForks = 1 }
