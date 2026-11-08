plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
}
repositories { mavenCentral() }
kotlin { jvmToolchain(17) }
sourceSets {
    main {
        kotlin.srcDir("../../android-wife-assistant/app/src/main/java")
        kotlin.include("com/wifeassistant/data/SecretPersistence.kt", "com/wifeassistant/data/crypto/DeviceIdentity.kt",
            "com/wifeassistant/data/crypto/DeviceIdentityPersistence.kt")
    }
    test {
        kotlin.srcDir("../../android-wife-assistant/app/src/test/java")
        kotlin.include("com/wifeassistant/SecretPersistenceTest.kt", "com/wifeassistant/DeviceIdentityPersistenceTest.kt")
    }
}
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation("junit:junit:4.13.2")
}
tasks.test { maxParallelForks = 1 }
