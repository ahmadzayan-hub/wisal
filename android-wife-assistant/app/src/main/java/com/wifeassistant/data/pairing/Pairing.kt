package com.wifeassistant.data.pairing

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID

// دعوات إقران Wisal Direct (ADR-002 §5.2): منتهية الصلاحية، تُستخدم مرة واحدة،
// وقابلة للإلغاء. منطق صرف — الوقت بيتحقن (nowEpochSec) فكل الحالات قابلة
// للاختبار الحتمي، والتكامل مع الـ relay بييجي في شريحة لاحقة.

@Serializable
data class PairingInvitation(
    val id: String,
    val token: String,             // سر أحادي الاستخدام — بيتقارن بوقت ثابت
    val inviterDeviceId: String,
    val inviterPublicKeyB64: String,
    val inviterName: String,       // الاسم اللي المدعو هيشوفه قبل القبول
    val createdAtEpochSec: Long,
    val expiresAtEpochSec: Long,
    val state: String = STATE_PENDING,
) {
    companion object {
        const val STATE_PENDING = "pending"
        const val STATE_ACCEPTED = "accepted"
        const val STATE_REJECTED = "rejected"
        const val STATE_REVOKED = "revoked"
    }
}

// طرف مقترن (بيانات عامة فقط) — بيتخزن محليًا بعد قبول دعوة.
@Serializable
data class PairedPeer(
    val deviceId: String,
    val publicKeyB64: String,
    val name: String,
    val invitationId: String,
    val pairedAtEpochSec: Long,
)

sealed class PairingResult {
    data class Ok(val invitation: PairingInvitation) : PairingResult()
    data class Refused(val reason: String) : PairingResult()
}

object Pairing {
    const val TTL_SECONDS: Long = 48 * 3600
    private const val PREFIX = "wisal://pair#"
    private const val MAX_PAYLOAD_LENGTH = 8192
    private const val MAX_EPOCH_SECOND = 253402300799L // Last second of year 9999; safe for the UI formatter.
    private val json = Json { ignoreUnknownKeys = true }
    private val p256: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
    }

    fun create(
        inviterDeviceId: String,
        inviterPublicKeyB64: String,
        inviterName: String,
        nowEpochSec: Long,
        ttlSeconds: Long = TTL_SECONDS,
    ): PairingInvitation {
        require(ttlSeconds in 1..TTL_SECONDS && nowEpochSec in 0..MAX_EPOCH_SECOND - ttlSeconds)
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return PairingInvitation(
            id = UUID.randomUUID().toString(),
            token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw),
            inviterDeviceId = inviterDeviceId,
            inviterPublicKeyB64 = inviterPublicKeyB64,
            inviterName = inviterName,
            createdAtEpochSec = nowEpochSec,
            expiresAtEpochSec = nowEpochSec + ttlSeconds,
        )
    }

    fun isExpired(inv: PairingInvitation, nowEpochSec: Long): Boolean =
        nowEpochSec >= inv.expiresAtEpochSec

    // القبول: مرة واحدة فقط، قبل الانتهاء، وبتوكن مطابق (مقارنة وقت ثابت).
    // أي إعادة استخدام (replay) بترفض لأن الحالة مش pending.
    fun accept(inv: PairingInvitation, presentedToken: String, nowEpochSec: Long): PairingResult {
        if (!validFields(inv) || nowEpochSec < inv.createdAtEpochSec)
            return PairingResult.Refused("invalid")
        if (inv.state != PairingInvitation.STATE_PENDING)
            return PairingResult.Refused("already ${inv.state}")
        if (isExpired(inv, nowEpochSec))
            return PairingResult.Refused("expired")
        if (presentedToken.length != inv.token.length || !constantTimeEquals(inv.token, presentedToken))
            return PairingResult.Refused("bad token")
        return PairingResult.Ok(inv.copy(state = PairingInvitation.STATE_ACCEPTED))
    }

    fun reject(inv: PairingInvitation): PairingInvitation =
        inv.copy(state = PairingInvitation.STATE_REJECTED)

    // الداعي يقدر يلغي دعوة معلّقة في أي وقت — بعدها القبول مستحيل.
    fun revoke(inv: PairingInvitation): PairingInvitation =
        inv.copy(state = PairingInvitation.STATE_REVOKED)

    // حمولة رابط/QR الدعوة. بتتبعت للمدعو — من غير أي بيانات عن أشخاص تانيين.
    fun payload(inv: PairingInvitation): String {
        val body = json.encodeToString(PairingInvitation.serializer(), inv)
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(body.toByteArray(Charsets.UTF_8))
    }

    fun parsePayload(payload: String): PairingInvitation? = runCatching {
        require(payload.startsWith(PREFIX) && payload.length <= MAX_PAYLOAD_LENGTH)
        val b64 = payload.removePrefix(PREFIX)
        json.decodeFromString(PairingInvitation.serializer(),
            String(Base64.getUrlDecoder().decode(b64), Charsets.UTF_8)).also { require(validFields(it)) }
    }.getOrNull()

    private fun validFields(inv: PairingInvitation): Boolean = runCatching {
        require(UUID.fromString(inv.id).toString() == inv.id)
        require(UUID.fromString(inv.inviterDeviceId).toString() == inv.inviterDeviceId)
        require(inv.inviterName.isNotBlank() && inv.inviterName.length <= 256 &&
            inv.inviterName.none { it.isISOControl() })
        require(inv.createdAtEpochSec in 0..MAX_EPOCH_SECOND &&
            inv.expiresAtEpochSec in 1..MAX_EPOCH_SECOND &&
            inv.expiresAtEpochSec > inv.createdAtEpochSec &&
            inv.expiresAtEpochSec - inv.createdAtEpochSec <= TTL_SECONDS)
        require(inv.state in setOf(PairingInvitation.STATE_PENDING, PairingInvitation.STATE_ACCEPTED,
            PairingInvitation.STATE_REJECTED, PairingInvitation.STATE_REVOKED))
        require(inv.token.length == 43)
        val token = Base64.getUrlDecoder().decode(inv.token)
        require(token.size == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(token) == inv.token)
        require(inv.inviterPublicKeyB64.length in 1..2048)
        val key = KeyFactory.getInstance("EC").generatePublic(
            X509EncodedKeySpec(Base64.getDecoder().decode(inv.inviterPublicKeyB64))) as ECPublicKey
        require(key.params.curve == p256.curve && key.params.generator == p256.generator &&
            key.params.order == p256.order && key.params.cofactor == p256.cofactor)
        require(Base64.getEncoder().encodeToString(key.encoded) == inv.inviterPublicKeyB64)
        true
    }.getOrDefault(false)

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}
