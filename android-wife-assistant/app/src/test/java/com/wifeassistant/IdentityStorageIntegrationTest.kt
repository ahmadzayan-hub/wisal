package com.wifeassistant

import com.wifeassistant.data.SecretPersistence
import com.wifeassistant.data.SecretPreferences
import com.wifeassistant.data.crypto.DeviceIdentityPersistence
import com.wifeassistant.data.crypto.LocalIdentity
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** Exercises the two production policy layers together. Not Android Keystore. */
class IdentityStorageIntegrationTest {
    private class Disk(var value: String? = null) : SecretPreferences {
        var writes = 0
        override fun read(key: String) = value
        override fun write(key: String, value: String): Boolean { writes++; this.value = value; return true }
        override fun remove(key: String): Boolean { value = null; return true }
    }
    private fun load(policy: SecretPersistence, secure: SecretPreferences?, legacy: SecretPreferences): LocalIdentity =
        DeviceIdentityPersistence.getOrCreate(
            { policy.read("deviceIdentity", secure, legacy) },
            { policy.write("deviceIdentity", it, secure, legacy) },
        )

    @Test fun migratedIdentitySurvivesPolicyRecreationWithoutRotation() {
        val originalDisk = Disk()
        val original = load(SecretPersistence(), originalDisk, Disk())
        val legacy = Disk(originalDisk.value); val encrypted = Disk()
        val migrated = load(SecretPersistence(), encrypted, legacy)
        val restarted = load(SecretPersistence(), encrypted, legacy)
        assertEquals(original, migrated); assertEquals(original, restarted)
        assertNull(legacy.value); assertEquals(1, encrypted.writes)
    }
    @Test fun unavailableEncryptionNeverReplacesLegacyIdentity() {
        val disk = Disk(); load(SecretPersistence(), disk, Disk())
        val legacy = Disk(disk.value); val snapshot = legacy.value
        assertTrue(runCatching { load(SecretPersistence(), null, legacy) }.isFailure)
        assertEquals(snapshot, legacy.value); assertEquals(0, legacy.writes)
    }
    @Test fun corruptedEncryptedRecordNeverTriggersFreshIdentity() {
        val encrypted = Disk("{corrupted"); val legacy = Disk()
        assertTrue(runCatching { load(SecretPersistence(), encrypted, legacy) }.isFailure)
        assertEquals("{corrupted", encrypted.value); assertEquals(0, encrypted.writes)
    }
    @Test fun failedCommitBlocksCacheThenRestartReadsLastDurableIdentity() {
        val disk = Disk(); val original = load(SecretPersistence(), disk, Disk())
        var cache = disk.value
        val failed = object : SecretPreferences {
            override fun read(key: String) = cache
            override fun write(key: String, value: String): Boolean { cache = value; return false }
            override fun remove(key: String) = false
        }
        val policy = SecretPersistence()
        assertTrue(runCatching { policy.write("deviceIdentity", "uncommitted", failed, Disk()) }.isFailure)
        assertTrue(runCatching { load(policy, failed, Disk()) }.isFailure)
        assertEquals(original, load(SecretPersistence(), disk, Disk()))
    }
    @Test fun simultaneousFirstUseAcrossBothPoliciesHasOneDurableIdentity() {
        val encrypted = Disk(); val legacy = Disk(); val policy = SecretPersistence()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val identities = pool.invokeAll(List(32) { Callable { load(policy, encrypted, legacy) } })
                .map { it.get() }
            assertEquals(1, identities.distinct().size); assertEquals(1, encrypted.writes)
            assertEquals(identities.first(), load(SecretPersistence(), encrypted, legacy))
        } finally { pool.shutdownNow() }
    }
}
