package com.wifeassistant.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

// تخزين مشفّر لمفتاح Groq (Android Keystore + EncryptedSharedPreferences).
// Encryption failures stop secret access; they never enable plaintext writes.
object SecureStore {
    private const val LEGACY_PREFS = "wife_assistant_settings"
    private const val KEY = "groqKey"
    private val secretPersistence = SecretPersistence()

    private fun secure(context: Context): SharedPreferences? = try {
        val master = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "wisal_secure",
            master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        null
    }

    private fun legacy(context: Context): SharedPreferences =
        context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)

    fun getGroqKey(context: Context): String {
        val enc = secure(context) ?: return ""
        enc.getString(KEY, null)?.let { return it }
        val old = legacy(context).getString(KEY, "").orEmpty()
        if (old.isNotBlank() && enc.edit().putString(KEY, old).commit()) {
            legacy(context).edit().remove(KEY).apply()
            return old
        }
        return ""
    }

    fun setGroqKey(context: Context, value: String) {
        // Empty is safe to remove even when device encryption is temporarily unavailable.
        if (value.isBlank()) {
            secure(context)?.edit()?.remove(KEY)?.apply()
            legacy(context).edit().remove(KEY).apply()
            return
        }
        val enc = secure(context) ?: error("Secure key storage is unavailable. No key was saved.")
        check(enc.edit().putString(KEY, value).commit()) { "Could not securely save the key." }
        legacy(context).edit().remove(KEY).apply()
    }

    // General secrets migrate only after a verified encrypted commit.
    fun getSecret(context: Context, key: String): String? =
        secretPersistence.read(key, secure(context)?.let(::PreferenceSecrets), PreferenceSecrets(legacy(context)))

    fun setSecret(context: Context, key: String, value: String) {
        secretPersistence.write(key, value, secure(context)?.let(::PreferenceSecrets), PreferenceSecrets(legacy(context)))
    }

    private class PreferenceSecrets(private val prefs: SharedPreferences) : SecretPreferences {
        override fun read(key: String): String? = prefs.getString(key, null)
        override fun write(key: String, value: String): Boolean = prefs.edit().putString(key, value).commit()
        override fun remove(key: String): Boolean = prefs.edit().remove(key).commit()
    }
}
