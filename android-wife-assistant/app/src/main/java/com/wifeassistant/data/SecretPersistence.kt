package com.wifeassistant.data

/** Commit-aware boundary; implemented by encrypted preferences on Android. */
interface SecretPreferences {
    fun read(key: String): String?
    fun write(key: String, value: String): Boolean
    fun remove(key: String): Boolean
}

/** Never reads legacy plaintext as usable secret material without migrating it first. */
class SecretPersistence {
    private val failure = "Secure storage is unavailable or could not be verified. No plaintext fallback was used."
    private var unverifiedWrite = false

    @Synchronized
    fun read(key: String, encrypted: SecretPreferences?, legacy: SecretPreferences): String? = guarded {
        check(!unverifiedWrite)
        val secure = encrypted ?: error(failure)
        val current = secure.read(key)
        if (current != null) {
            clearLegacy(key, legacy)
            current
        } else {
            val old = legacy.read(key) ?: return@guarded null
            persist(key, old, secure)
            clearLegacy(key, legacy)
            old
        }
    }

    @Synchronized
    fun write(key: String, value: String, encrypted: SecretPreferences?, legacy: SecretPreferences) = guarded {
        check(!unverifiedWrite)
        persist(key, value, encrypted ?: error(failure))
        clearLegacy(key, legacy)
    }

    private fun persist(key: String, value: String, secure: SecretPreferences) {
        // SharedPreferences may change its memory cache even if disk commit fails.
        // Do not trust that cache on a later call in this process after a failed write.
        unverifiedWrite = true
        check(secure.write(key, value))
        check(secure.read(key) == value)
        unverifiedWrite = false
    }

    private fun clearLegacy(key: String, legacy: SecretPreferences) {
        if (legacy.read(key) != null) check(legacy.remove(key))
    }

    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (_: Exception) {
        // Storage/provider exception messages can contain secret material.
        throw IllegalStateException(failure)
    }
}
