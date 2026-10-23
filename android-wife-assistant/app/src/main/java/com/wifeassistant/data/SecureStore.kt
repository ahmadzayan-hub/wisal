package com.wifeassistant.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

// تخزين مشفّر لمفتاح Groq (Android Keystore + EncryptedSharedPreferences).
// لو التشفير فشل لأي سبب، بيرجع بأمان للتخزين العادي عشان التطبيق ما يقعش.
object SecureStore {
    private const val LEGACY_PREFS = "wife_assistant_settings"
    private const val KEY = "groqKey"

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

    // ---- تخزين أسرار عامة (هوية الجهاز وغيرها) ----
    // نفس سياسة الاحتياطي: التشفير أولًا، والفشل بيرجع للتخزين العادي بدل الكراش.
    fun getSecret(context: Context, key: String): String? =
        secure(context)?.getString(key, null) ?: legacy(context).getString(key, null)

    fun setSecret(context: Context, key: String, value: String) {
        val enc = secure(context)
        if (enc != null) enc.edit().putString(key, value).apply()
        else legacy(context).edit().putString(key, value).apply()
    }
}
