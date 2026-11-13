package com.wifeassistant

import android.content.Context
import com.wifeassistant.data.SecureStore
import com.wifeassistant.data.crypto.AndroidSignalProvider
import com.wifeassistant.data.crypto.DeviceIdentityStore
import com.wifeassistant.data.crypto.PublicIdentity
import com.wifeassistant.data.crypto.SignalPeerBinding
import com.wifeassistant.data.crypto.SignalPeerIdentity
import com.wifeassistant.data.crypto.SignalPreKeyBundleDto
import com.wifeassistant.data.crypto.peerIdentity
import com.wifeassistant.data.pairing.DirectPreKeyMaintenance
import com.wifeassistant.data.pairing.DirectRelayClient
import com.wifeassistant.data.pairing.DirectSignalDelivery
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

@Serializable
internal data class DirectReviewPeer(val deviceId: String, val publicKeyB64: String,
    val registrationId: Int, val signalKeyB64: String, val binding: String) {
    val owner get() = PublicIdentity(deviceId, publicKeyB64)
    val signal get() = SignalPeerIdentity(deviceId, registrationId, signalKeyB64)

    fun verify() = SignalPeerBinding.verify(owner, signal, binding)

    fun fingerprint(): String = MessageDigest.getInstance("SHA-256").digest(
        "wisal-review-peer:1:$deviceId:$publicKeyB64:$registrationId:$signalKeyB64".toByteArray(Charsets.UTF_8),
    ).joinToString("") { "%02X".format(it.toInt() and 255) }

    fun toCode(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
        Json.encodeToString(serializer(), this).toByteArray(Charsets.UTF_8),
    )

    companion object {
        fun fromCode(code: String): DirectReviewPeer {
            require(code.length in 1..4096)
            return Json.decodeFromString(serializer(), String(Base64.getUrlDecoder().decode(code), Charsets.UTF_8))
                .also { it.verify() }
        }
    }
}

internal data class DirectReviewView(val code: String, val fingerprint: String,
    val peerFingerprint: String?, val received: List<String>, val queued: Int, val expired: Int,
    val canRestartInitialSession: Boolean)

internal class DirectReviewSession(private val context: Context) {
    private val relay by lazy { openRelay(context) }
    private val identity by lazy { DeviceIdentityStore.getForDirect(context, enroll = false) }
    private val provider by lazy { AndroidSignalProvider.open(context, identity.deviceId, isRegistered(context)) }
    private val queue by lazy { AndroidSignalProvider.deliveryQueue(context, identity.deviceId, isRegistered(context)) }
    private val delivery by lazy { DirectSignalDelivery(relay, queue) }

    private fun now() = System.currentTimeMillis() / 1000

    private fun peer() = SecureStore.getSecret(context, "directReviewPeer")?.let(DirectReviewPeer::fromCode)

    private fun localPeer(): DirectReviewPeer {
        val signal = provider.localPeerIdentity(identity.deviceId)
        return DirectReviewPeer(identity.deviceId, identity.publicKeyB64, signal.registrationId,
            signal.identityKeyB64, SignalPeerBinding.sign(identity, signal))
    }

    fun view(): DirectReviewView? {
        if (!isRegistered(context)) return null
        val local = localPeer()
        val verifiedPeer = peer()
        val received = queue.inbox().filter { it.senderDeviceId == verifiedPeer?.deviceId }
            .map { String(Base64.getDecoder().decode(it.plaintextB64), Charsets.UTF_8) }
        val pending = queue.pendingOutbox()
        val now = now()
        return DirectReviewView(local.toCode(), local.fingerprint(), verifiedPeer?.fingerprint(), received,
            pending.size, pending.count { it.expiresAtEpochSec <= now },
            pending.isEmpty() && verifiedPeer != null && initialDeadline()?.let { it <= now } == true)
    }

    fun resume(): DirectReviewView? {
        if (!isRegistered(context)) return null
        DirectReviewMaintenance.schedule(context)
        return view()
    }

    suspend fun register(enteredPin: String) {
        check(SecureStore.getSecret(context, REGISTRATION) != "revoked")
        val pin = enteredPin.trim()
        require(PIN_PATTERN.matches(pin))
        val savedPin = SecureStore.getSecret(context, RELAY_PIN)
        check(savedPin == null || savedPin == pin)
        val signer = DeviceIdentityStore.getForDirect(context, enroll = !isRegistered(context))
        DirectRelayClient(RELAY_ORIGIN, pin).registerDevice(signer).getOrThrow()
        if (savedPin == null) SecureStore.setSecret(context, RELAY_PIN, pin)
        DirectPreKeyMaintenance(provider, relay).runOnce(identity).getOrThrow()
        SecureStore.setSecret(context, REGISTRATION, "registered")
        AndroidSignalProvider.open(context, identity.deviceId, requireExistingCheckpoint = true)
        DirectReviewMaintenance.schedule(context)
    }

    fun confirmPeer(peer: DirectReviewPeer, verifiedFingerprint: String) {
        check(isRegistered(context))
        val fingerprint = verifiedFingerprint.filterNot(Char::isWhitespace).uppercase(java.util.Locale.ROOT)
        check(peer.fingerprint() == fingerprint && peer.deviceId != identity.deviceId)
        check(this.peer()?.fingerprint()?.let { it == fingerprint } != false)
        provider.trustPeer(peer.owner, peer.signal, peer.binding)
        SecureStore.setSecret(context, "directReviewPeer", peer.toCode())
    }

    suspend fun send(message: String) {
        check(isRegistered(context))
        val peer = checkNotNull(peer())
        if (!provider.hasSession(peer.deviceId)) {
            val claimedAt = now()
            val bundle = relay.claimPreKeys(identity, peer.deviceId, claimedAt).getOrThrow()
            check(bundle.peerIdentity() == peer.signal)
            // Persist the claim deadline before opening the session so an
            // interruption between session setup and the first send stays bounded.
            SecureStore.setSecret(context, INITIAL_DEADLINE, (claimedAt + 86_400).toString())
            provider.establishSession(peer.deviceId, Json.encodeToString(SignalPreKeyBundleDto.serializer(), bundle)
                .toByteArray(Charsets.UTF_8))
        }
        val now = now()
        queue.prepare(UUID.randomUUID().toString(), peer.deviceId, message.toByteArray(Charsets.UTF_8),
            now, now + 86_400, initialDeadline() ?: now)
    }

    suspend fun sync() {
        check(isRegistered(context))
        val peer = checkNotNull(peer())
        val received = delivery.receiveAndAcknowledge(identity, now(), peer.deviceId)
        delivery.flushOutbox(identity, now(), continuePastExpired = true).getOrThrow()
        DirectPreKeyMaintenance(provider, relay).runOnce(identity).getOrThrow()
        received.getOrThrow()
    }

    fun discardExpired() { queue.discardExpiredOutbox(now()) }

    private fun initialDeadline() = SecureStore.getSecret(context, INITIAL_DEADLINE)?.toLongOrNull()

    fun restartInitialSession() {
        val now = now()
        check(isRegistered(context) && initialDeadline()?.let { it <= now } == true)
        queue.restartExpiredInitialSession(checkNotNull(peer()).deviceId, now)
        SecureStore.setSecret(context, INITIAL_DEADLINE, "")
    }

    suspend fun revoke() {
        relay.revokeDevice(identity, now()).getOrThrow()
        DirectReviewMaintenance.cancel(context)
        SecureStore.setSecret(context, REGISTRATION, "revoked")
    }

    companion object {
        private const val REGISTRATION = "directReviewRegistration"
        private const val RELAY_PIN = "directReviewRelayPin"
        private const val INITIAL_DEADLINE = "directReviewInitialDeadline"
        private const val RELAY_ORIGIN = "https://localhost:8443"
        private val PIN_PATTERN = Regex("sha256/[A-Za-z0-9+/]{43}=")

        fun isRegistered(context: Context) = SecureStore.getSecret(context, REGISTRATION) == "registered"

        fun openRelay(context: Context): DirectRelayClient {
            val pin = checkNotNull(SecureStore.getSecret(context, RELAY_PIN))
            require(PIN_PATTERN.matches(pin))
            return DirectRelayClient(RELAY_ORIGIN, pin)
        }
    }
}
