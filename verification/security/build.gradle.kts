plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
}
repositories { mavenCentral() }
kotlin { jvmToolchain(17) }
sourceSets {
    main {
        kotlin.srcDir("../../android-wife-assistant/app/src/main/java")
        kotlin.include("com/wifeassistant/data/SecretPersistence.kt",
            "com/wifeassistant/data/crypto/DeviceIdentity.kt",
            "com/wifeassistant/data/crypto/DeviceIdentityPersistence.kt",
            "com/wifeassistant/data/crypto/CryptoProvider.kt",
            "com/wifeassistant/data/crypto/LibsignalKeys.kt",
            "com/wifeassistant/data/crypto/LibsignalCryptoProvider.kt",
            "com/wifeassistant/data/crypto/SignalPreKeyBundleDto.kt",
            "com/wifeassistant/data/crypto/SignalStatePersistence.kt",
            "com/wifeassistant/data/crypto/SignalMessageQueue.kt",
            "com/wifeassistant/data/crypto/SignalWireCodec.kt",
            "com/wifeassistant/data/crypto/SignalPeerBinding.kt",
            "com/wifeassistant/data/pairing/DirectRelayClient.kt",
            "com/wifeassistant/data/pairing/DirectRelayProofs.kt",
            "com/wifeassistant/data/pairing/DirectSignalDelivery.kt")
    }
    test {
        kotlin.srcDir("../../android-wife-assistant/app/src/test/java")
        kotlin.include("com/wifeassistant/SecretPersistenceTest.kt",
            "com/wifeassistant/DeviceIdentityPersistenceTest.kt",
            "com/wifeassistant/IdentityStorageIntegrationTest.kt",
            "com/wifeassistant/LibsignalCryptoProviderTest.kt",
            "com/wifeassistant/CryptoProviderTest.kt",
            "com/wifeassistant/SignalReleaseAcceptanceTest.kt",
            "com/wifeassistant/SignalPersistenceTest.kt",
            "com/wifeassistant/SignalMessageQueueTest.kt",
            "com/wifeassistant/DirectRelayClientTest.kt",
            "com/wifeassistant/DirectRelayProofsTest.kt",
            "com/wifeassistant/DirectRelayKeyLifecycleTest.kt",
            "com/wifeassistant/SignalRelayIntegrationTest.kt",
            "com/wifeassistant/SignalProcessRestartTest.kt")
    }
}
dependencies {
    implementation("org.signal:libsignal-client:0.86.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
tasks.test {
    maxParallelForks = 1
    systemProperty("wisal.childClasspath", sourceSets.test.get().runtimeClasspath.asPath)
    systemProperty("wisal.repository", file("../..").absolutePath)
}
