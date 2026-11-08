package com.wifeassistant

import com.wifeassistant.data.SecretPersistence
import com.wifeassistant.data.SecretPreferences
import org.junit.Assert.*
import org.junit.Test

class SecretPersistenceTest {
    private val key = "deviceIdentity"
    private val storage = SecretPersistence()
    private class Memory(var value: String? = null) : SecretPreferences {
        var reads = 0; var writes = 0; var removals = 0
        var allowWrite = true; var allowRemove = true; var retainWrite = true
        override fun read(key: String): String? { reads++; return value }
        override fun write(key: String, value: String): Boolean {
            writes++
            if (allowWrite && retainWrite) this.value = value
            return allowWrite
        }
        override fun remove(key: String): Boolean {
            removals++
            if (allowRemove) value = null
            return allowRemove
        }
    }
    private fun refused(block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertNull(failure?.cause)
    }

    @Test fun unavailableEncryptionCannotReadOrWritePlaintextFallback() {
        val legacy = Memory("synthetic-existing-secret")
        refused { storage.read(key, null, legacy) }
        refused { storage.write(key, "synthetic-new-secret", null, legacy) }
        assertEquals(0, legacy.reads); assertEquals(0, legacy.writes); assertEquals(0, legacy.removals)
        assertEquals("synthetic-existing-secret", legacy.value)
    }
    @Test fun migrationCommitsAndReadsBackBeforeRemovingLegacy() {
        val secure = Memory(); val legacy = Memory("synthetic-legacy-secret")
        assertEquals("synthetic-legacy-secret", storage.read(key, secure, legacy))
        assertEquals("synthetic-legacy-secret", secure.value)
        assertEquals(1, secure.writes); assertTrue(secure.reads >= 2)
        assertNull(legacy.value); assertEquals(0, legacy.writes)
    }
    @Test fun failedEncryptedCommitPreservesLegacyWithoutReturningIt() {
        val secure = Memory().apply { allowWrite = false }; val legacy = Memory("synthetic-old")
        refused { storage.read(key, secure, legacy) }
        assertEquals("synthetic-old", legacy.value); assertEquals(0, legacy.removals)
    }
    @Test fun falseSuccessFromBackendDoesNotRemoveTheOnlyCopy() {
        val secure = Memory().apply { retainWrite = false }; val legacy = Memory("synthetic-old")
        refused { storage.read(key, secure, legacy) }
        assertEquals("synthetic-old", legacy.value); assertEquals(0, legacy.removals)
    }
    @Test fun interruptedLegacyCleanupRetriesWithoutOverwritingEncryptedIdentity() {
        val secure = Memory(); val legacy = Memory("synthetic-old").apply { allowRemove = false }
        refused { storage.read(key, secure, legacy) }
        assertEquals("synthetic-old", secure.value); assertEquals("synthetic-old", legacy.value)
        legacy.allowRemove = true
        assertEquals("synthetic-old", storage.read(key, secure, legacy))
        assertEquals(1, secure.writes); assertNull(legacy.value)
    }
    @Test fun encryptedIdentityWinsOverAStaleLegacyCopy() {
        val secure = Memory("synthetic-current"); val legacy = Memory("synthetic-stale")
        assertEquals("synthetic-current", storage.read(key, secure, legacy))
        assertEquals(0, secure.writes); assertNull(legacy.value)
    }
    @Test fun missingSecretIsDistinctFromUnavailableStorage() {
        val secure = Memory(); val legacy = Memory()
        assertNull(storage.read(key, secure, legacy))
        assertEquals(0, secure.writes); assertEquals(0, legacy.writes)
    }
    @Test fun writesRequireVerifiedCommitAndNeverWriteLegacy() {
        val secure = Memory(); val legacy = Memory("synthetic-old")
        storage.write(key, "synthetic-new", secure, legacy)
        assertEquals("synthetic-new", secure.value); assertNull(legacy.value); assertEquals(0, legacy.writes)
        secure.allowWrite = false
        refused { storage.write(key, "synthetic-rejected", secure, legacy) }
        assertEquals("synthetic-new", secure.value)
    }
    @Test fun failedCommitCannotBeReadBackFromAnUncommittedMemoryCache() {
        val secure = Memory(); val legacy = Memory()
        val cachedButNotCommitted = object : SecretPreferences {
            override fun read(key: String) = secure.read(key)
            override fun write(key: String, value: String): Boolean { secure.value = value; return false }
            override fun remove(key: String) = false
        }
        refused { storage.write(key, "synthetic-uncommitted", cachedButNotCommitted, legacy) }
        refused { storage.read(key, cachedButNotCommitted, legacy) }
        refused { storage.write(key, "synthetic-replacement", cachedButNotCommitted, legacy) }
        assertEquals("synthetic-uncommitted", secure.value)
        assertEquals(0, legacy.writes)
    }
    @Test fun providerExceptionsAreSanitizedWithoutLeakingTheirCause() {
        val broken = object : SecretPreferences {
            override fun read(key: String): String? = error("synthetic-sensitive-provider-message")
            override fun write(key: String, value: String) = false
            override fun remove(key: String) = false
        }
        val failure = runCatching { storage.read(key, broken, Memory()) }.exceptionOrNull()!!
        assertFalse(failure.toString().contains("synthetic-sensitive")); assertNull(failure.cause)
    }
}
