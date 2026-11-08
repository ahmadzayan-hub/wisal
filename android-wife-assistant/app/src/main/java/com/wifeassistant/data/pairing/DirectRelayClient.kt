package com.wifeassistant.data.pairing

import com.wifeassistant.data.crypto.DeviceIdentityCodec
import com.wifeassistant.data.crypto.LocalIdentity
import com.wifeassistant.data.crypto.SignalPreKeyBundleDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import java.util.concurrent.TimeUnit

// عميل wisal-direct-relay (ADR-002 §5.3): تسجيل جهاز، إيداع/سحب/تأكيد مغلف
// مشفّر. كل طلب موقّع بالمفتاح الخاص للجهاز — مفيش مفتاح API مشترك.
//
// مهم: العميل ده نقل بس، عمره ما بيشفّر حاجة. الـ ciphertextB64 اللي بيتبعت
// جاي من CryptoProvider الحالي (DEMO_ONLY في التطوير) — النقل مايغيّرش
// وصف مستوى الأمان. لو baseUrl فاضي (الـ relay لسه مش منشور)، isConfigured()
// بترجع false بدل ما يحاول يتصل بحاجة مش موجودة.
class DirectRelayClient(private val baseUrl: String) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // Claim consumes a one-time key. Never retry it or forward signed bodies
        // to a redirect target without an explicit new caller operation.
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    fun isConfigured(): Boolean = baseUrl.isNotBlank() && baseUrl.toHttpUrlOrNull() != null

    suspend fun registerDevice(identity: LocalIdentity): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext Result.failure(IllegalStateException("relay not configured"))
        val sig = signB64(identity, DirectRelayProofs.register(identity.deviceId))
        val body = json.encodeToString(
            RegisterRequest.serializer(),
            RegisterRequest(identity.deviceId, identity.publicKeyB64, sig),
        )
        post(RegisterResponse.serializer(), "$baseUrl/api/devices", body).map { }
    }

    suspend fun submitEnvelope(
        identity: LocalIdentity,
        recipientDeviceId: String,
        ciphertextB64: String,
        backend: String,
        expiresAtEpochSec: Long,
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext Result.failure(IllegalStateException("relay not configured"))
        val proof = DirectRelayProofs.submit(identity.deviceId, recipientDeviceId, ciphertextB64, expiresAtEpochSec)
        val body = json.encodeToString(
            SubmitRequest.serializer(),
            SubmitRequest(identity.deviceId, recipientDeviceId, ciphertextB64, backend, expiresAtEpochSec, signB64(identity, proof)),
        )
        post(SubmitResponse.serializer(), "$baseUrl/api/envelopes", body).mapCatching {
            it.id ?: throw RuntimeException("missing envelope id in response")
        }
    }

    suspend fun fetchInbox(identity: LocalIdentity, nowEpochSec: Long): Result<List<RelayInboxItem>> = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext Result.failure(IllegalStateException("relay not configured"))
        val proof = DirectRelayProofs.fetch(identity.deviceId, nowEpochSec)
        val sig = java.net.URLEncoder.encode(signB64(identity, proof), "UTF-8")
        val url = "$baseUrl/api/inbox?deviceId=${identity.deviceId}&timestamp=$nowEpochSec&signatureB64=$sig"
        runCatching {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                val payload = json.decodeFromString(InboxResponse.serializer(), resp.body?.string().orEmpty())
                if (!resp.isSuccessful || payload.ok != true) throw RuntimeException(payload.reason ?: "HTTP ${resp.code}")
                payload.items.orEmpty()
            }
        }
    }

    suspend fun ackDelivery(identity: LocalIdentity, envelopeId: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext Result.failure(IllegalStateException("relay not configured"))
        val sig = signB64(identity, DirectRelayProofs.ack(envelopeId))
        val body = json.encodeToString(AckRequest.serializer(), AckRequest(identity.deviceId, envelopeId, sig))
        post(AckResponse.serializer(), "$baseUrl/api/ack", body).map { }
    }

    suspend fun publishPreKeys(identity: LocalIdentity, bundle: SignalPreKeyBundleDto, nowEpochSec: Long): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext Result.failure(IllegalStateException("relay not configured"))
        runCatching {
            require(nowEpochSec > 0 && bundle.deviceId == identity.deviceId && bundle.hasValidWireFields()) { "invalid pre-key publication" }
            val signature = signB64(identity, DirectRelayProofs.publishPreKeys(bundle, nowEpochSec))
            val body = json.encodeToString(PublishPreKeysRequest.serializer(), PublishPreKeysRequest(bundle, nowEpochSec, signature))
            val response = post(PublishPreKeysResponse.serializer(), "${baseUrl.trimEnd('/')}/api/prekeys", body).getOrThrow()
            check(response.deviceId == identity.deviceId && response.oneTimePreKeyId == bundle.oneTimePreKeyId) { "mismatched publication receipt" }
        }
    }

    suspend fun claimPreKeys(identity: LocalIdentity, targetDeviceId: String, nowEpochSec: Long): Result<SignalPreKeyBundleDto> = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext Result.failure(IllegalStateException("relay not configured"))
        runCatching {
            require(nowEpochSec > 0 && targetDeviceId.isNotBlank() && !targetDeviceId.contains(':') && targetDeviceId != identity.deviceId) { "invalid pre-key target" }
            val signature = signB64(identity, DirectRelayProofs.claimPreKeys(identity.deviceId, targetDeviceId, nowEpochSec))
            val body = json.encodeToString(ClaimPreKeysRequest.serializer(), ClaimPreKeysRequest(identity.deviceId, targetDeviceId, nowEpochSec, signature))
            val response = post(ClaimPreKeysResponse.serializer(), "${baseUrl.trimEnd('/')}/api/prekeys-claim", body).getOrThrow()
            val bundle = response.bundle ?: error("missing pre-key bundle")
            check(bundle.deviceId == targetDeviceId && bundle.hasValidWireFields()) { "invalid claimed pre-key bundle" }
            bundle
        }
    }

    suspend fun revokeDevice(identity: LocalIdentity, nowEpochSec: Long): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext Result.failure(IllegalStateException("relay not configured"))
        runCatching {
            require(nowEpochSec > 0) { "invalid revocation timestamp" }
            val signature = signB64(identity, DirectRelayProofs.revoke(identity.deviceId, nowEpochSec))
            val body = json.encodeToString(RevokeRequest.serializer(), RevokeRequest(identity.deviceId, nowEpochSec, signature))
            val response = post(RevokeResponse.serializer(), "${baseUrl.trimEnd('/')}/api/revoke", body).getOrThrow()
            check(response.deviceId == identity.deviceId && response.revoked == true) { "missing revocation receipt" }
        }
    }

    private fun signB64(identity: LocalIdentity, proof: String): String =
        Base64.getEncoder().encodeToString(DeviceIdentityCodec.sign(identity, proof.toByteArray()))

    // POST مشترك: بيبعت الجسم ويفكّ الرد بالسكيمة المُمرّرة، وبيفشل بوضوح لو
    // ok=false أو الـ HTTP مش ناجح — مفيش نجاح صامت لطلب مرفوض.
    private fun <T : RelayOkResponse> post(serializer: kotlinx.serialization.KSerializer<T>, url: String, bodyJson: String): Result<T> = runCatching {
        val body = bodyJson.toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(url).post(body).build()
        client.newCall(req).execute().use { resp ->
            val payload = json.decodeFromString(serializer, resp.body?.string().orEmpty())
            if (!resp.isSuccessful || payload.ok != true) throw RuntimeException(payload.reason ?: "HTTP ${resp.code}")
            payload
        }
    }
}

interface RelayOkResponse { val ok: Boolean?; val reason: String? }

@Serializable
data class RelayInboxItem(
    val id: String,
    val senderDeviceId: String,
    val ciphertextB64: String,
    val backend: String,
    val createdAtEpochSec: Long,
)

@Serializable
private data class RegisterRequest(val deviceId: String, val publicKeyB64: String, val signatureB64: String)

@Serializable
private data class RegisterResponse(override val ok: Boolean? = null, override val reason: String? = null) : RelayOkResponse

@Serializable
private data class SubmitRequest(
    val senderDeviceId: String,
    val recipientDeviceId: String,
    val ciphertextB64: String,
    val backend: String,
    val expiresAtEpochSec: Long,
    val signatureB64: String,
)

@Serializable
private data class SubmitResponse(override val ok: Boolean? = null, override val reason: String? = null, val id: String? = null) : RelayOkResponse

@Serializable
private data class AckRequest(val deviceId: String, val envelopeId: String, val signatureB64: String)

@Serializable
private data class AckResponse(override val ok: Boolean? = null, override val reason: String? = null) : RelayOkResponse

@Serializable
private data class InboxResponse(val ok: Boolean? = null, val reason: String? = null, val items: List<RelayInboxItem>? = null)

@Serializable
private data class PublishPreKeysRequest(val bundle: SignalPreKeyBundleDto, val timestamp: Long, val signatureB64: String)
@Serializable
private data class PublishPreKeysResponse(override val ok: Boolean? = null, override val reason: String? = null, val deviceId: String? = null, val oneTimePreKeyId: Int? = null) : RelayOkResponse
@Serializable
private data class ClaimPreKeysRequest(val requesterDeviceId: String, val targetDeviceId: String, val timestamp: Long, val signatureB64: String)
@Serializable
private data class ClaimPreKeysResponse(override val ok: Boolean? = null, override val reason: String? = null, val bundle: SignalPreKeyBundleDto? = null) : RelayOkResponse
@Serializable
private data class RevokeRequest(val deviceId: String, val timestamp: Long, val signatureB64: String)
@Serializable
private data class RevokeResponse(override val ok: Boolean? = null, override val reason: String? = null, val deviceId: String? = null, val revoked: Boolean? = null) : RelayOkResponse
