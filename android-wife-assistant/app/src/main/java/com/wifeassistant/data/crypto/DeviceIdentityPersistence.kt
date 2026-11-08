package com.wifeassistant.data.crypto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.interfaces.ECKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

@Serializable
private data class StoredIdentity(val deviceId: String, val publicKeyB64: String, val privateKeyB64: String)

/** Serialization/validation policy shared by Android and JVM failure-path tests. */
object DeviceIdentityPersistence {
    private val json = Json { ignoreUnknownKeys = true }
    private const val FAILURE = "Device identity could not be safely loaded or saved. Existing identity was not replaced."

    @Synchronized
    fun getOrCreate(read: () -> String?, write: (String) -> Unit): LocalIdentity = try {
        val existing = read()
        if (existing != null) {
            decode(existing)
        } else {
            val fresh = DeviceIdentityCodec.generate()
            val encoded = json.encodeToString(StoredIdentity.serializer(), StoredIdentity(fresh.deviceId, fresh.publicKeyB64, fresh.privateKeyB64))
            write(encoded)
            check(read() == encoded)
            fresh
        }
    } catch (_: Exception) {
        // A malformed serialized identity can include private key bytes in a parser error.
        throw IllegalStateException(FAILURE)
    }

    private fun decode(raw: String): LocalIdentity {
        require(raw.length <= 16_384)
        val stored = json.decodeFromString(StoredIdentity.serializer(), raw)
        require(stored.deviceId.isNotBlank() && stored.deviceId.length <= 200 && !stored.deviceId.contains(':'))
        val factory = KeyFactory.getInstance("EC")
        val publicKey = factory.generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(stored.publicKeyB64))) as ECKey
        val privateKey = factory.generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(stored.privateKeyB64))) as ECKey
        val expected = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
        for (key in listOf(publicKey, privateKey)) {
            require(key.params.curve == expected.curve && key.params.generator == expected.generator &&
                key.params.order == expected.order && key.params.cofactor == expected.cofactor)
        }
        val identity = LocalIdentity(stored.deviceId, stored.publicKeyB64, stored.privateKeyB64)
        val challenge = "wisal-device-identity-pair-check".toByteArray(Charsets.UTF_8)
        check(DeviceIdentityCodec.verify(identity.publicKeyB64, challenge, DeviceIdentityCodec.sign(identity, challenge)))
        return identity
    }
}
