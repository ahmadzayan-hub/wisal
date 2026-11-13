package com.wifeassistant.data.crypto

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID

// هوية الجهاز لـ Wisal Direct (ADR-002 §5.2): معرّف مولّد محليًا + زوج مفاتيح
// توقيع EC P-256 — من غير رقم تليفون ولا إيميل ولا رفع لدفتر العناوين.
//
// Legacy identities and JVM fixtures can use encoded private keys. Android
// Direct enrollment uses a separate signer backed by Android Keystore.

data class PublicIdentity(val deviceId: String, val publicKeyB64: String)

interface DeviceSigningIdentity {
    val deviceId: String
    val publicKeyB64: String
    val public: PublicIdentity get() = PublicIdentity(deviceId, publicKeyB64)
    fun sign(data: ByteArray): ByteArray
}

data class LocalIdentity(
    override val deviceId: String,
    override val publicKeyB64: String,   // X.509/SPKI
    val privateKeyB64: String,  // PKCS#8 — يتخزن في التخزين المشفّر فقط
) : DeviceSigningIdentity {
    override fun sign(data: ByteArray) = DeviceIdentityCodec.sign(this, data)
    override fun toString() = "LocalIdentity(deviceId=$deviceId, privateKey=[redacted])"
}

// منطق صرف قابل للاختبار على الـ JVM — الربط بالتخزين الآمن بيتم عند الاستدعاء.
object DeviceIdentityCodec {
    private const val CURVE = "secp256r1"
    private const val SIG_ALG = "SHA256withECDSA"

    fun generate(): LocalIdentity {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec(CURVE), SecureRandom())
        val kp: KeyPair = kpg.generateKeyPair()
        return LocalIdentity(
            deviceId = UUID.randomUUID().toString(),
            publicKeyB64 = Base64.getEncoder().encodeToString(kp.public.encoded),
            privateKeyB64 = Base64.getEncoder().encodeToString(kp.private.encoded),
        )
    }

    fun sign(identity: LocalIdentity, data: ByteArray): ByteArray {
        val kf = KeyFactory.getInstance("EC")
        val priv = kf.generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(identity.privateKeyB64)))
        return sign(priv, data)
    }

    internal fun sign(priv: PrivateKey, data: ByteArray): ByteArray {
        return Signature.getInstance(SIG_ALG).run {
            initSign(priv)
            update(data)
            sign()
        }
    }

    fun verify(publicKeyB64: String, data: ByteArray, signature: ByteArray): Boolean = runCatching {
        val kf = KeyFactory.getInstance("EC")
        val pub = kf.generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyB64)))
        Signature.getInstance(SIG_ALG).run {
            initVerify(pub)
            update(data)
            verify(signature)
        }
    }.getOrDefault(false)
}
