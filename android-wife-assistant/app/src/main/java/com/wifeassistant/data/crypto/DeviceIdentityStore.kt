package com.wifeassistant.data.crypto

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import com.wifeassistant.data.SecureStore
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec

object DeviceIdentityStore {
    private const val KEY = "deviceIdentity"
    private const val PROVIDER = "AndroidKeyStore"
    private const val PREFIX = "WisalDeviceSigning."
    private const val NEW_ALIAS = "WisalDeviceSigning.Initial"

    fun getOrCreate(context: Context): DeviceSigningIdentity = load(context, requireHardware = false)

    internal fun getForDirect(context: Context, enroll: Boolean): DeviceSigningIdentity =
        load(context, requireHardware = true, enroll = enroll)

    private fun load(context: Context, requireHardware: Boolean, enroll: Boolean = true): DeviceSigningIdentity =
        DeviceIdentityPersistence.openKeystoreIdentity(
            read = { SecureStore.getSecret(context, KEY) },
            write = { SecureStore.setSecret(context, KEY, it) },
            open = { alias -> open(alias).also { if (requireHardware) check(isHardwareBacked(it.private)) } },
            create = if (enroll) {
                { create().also { if (requireHardware) check(isHardwareBacked(it.second.private)) } }
            } else null,
            allowLegacy = !requireHardware,
        )

    private fun store() = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun open(alias: String): KeyPair {
        require(alias.startsWith(PREFIX))
        val store = store()
        val entry = store.getEntry(alias, null) as KeyStore.PrivateKeyEntry
        return KeyPair(entry.certificate.publicKey, entry.privateKey)
    }

    private fun create(): Pair<String, KeyPair> {
        val store = store()
        // A failed preference commit can leave the initial key in Keystore.
        // Only the original app-data record can prove its device ID, so refuse.
        check(!store.aliases().toList().any { it.startsWith(PREFIX) })
        val parameters = KeyGenParameterSpec.Builder(NEW_ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).apply {
            initialize(parameters)
            generateKeyPair()
        }
        return NEW_ALIAS to open(NEW_ALIAS)
    }

    @Suppress("DEPRECATION")
    private fun isHardwareBacked(key: PrivateKey): Boolean {
        val info = KeyFactory.getInstance(key.algorithm, PROVIDER).getKeySpec(key, KeyInfo::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            info.securityLevel == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT ||
                info.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX
        } else {
            info.isInsideSecureHardware
        }
    }
}
