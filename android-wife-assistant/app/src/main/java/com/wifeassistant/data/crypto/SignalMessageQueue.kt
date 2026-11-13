package com.wifeassistant.data.crypto

import kotlinx.serialization.Serializable
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import java.security.MessageDigest
import java.util.Base64

@Serializable
data class QueuedSignalMessage(
    val id: String,
    val recipientDeviceId: String,
    val ciphertextB64: String,
    val expiresAtEpochSec: Long,
    val requestDigest: String,
    val relayEnvelopeId: String? = null,
) {
    override fun toString() = "QueuedSignalMessage(id=$id, payload=redacted)"
}

@Serializable
data class ReceivedSignalMessage(val id: String, val senderDeviceId: String, val plaintextB64: String) {
    override fun toString() = "ReceivedSignalMessage(id=$id, payload=redacted)"
}

internal interface SignalDeliveryRecords {
    val outbox: MutableMap<String, QueuedSignalMessage>
    val inbox: MutableMap<String, ReceivedSignalMessage>
    val receipts: MutableMap<String, String>
    val pendingAcks: MutableMap<String, String>
}

/** App-delivery state and ratchet mutations commit in the SAME encrypted snapshot.
 * Retrying an outbox id reuses its saved ciphertext; it never re-encrypts. Inbox
 * rows use a sender-bound ciphertext digest so an unknown-submit retry under a
 * second relay envelope id cannot display the same message twice.
 * This core is not an Android UI integration or a claim of E2EE acceptance.
 */
class SignalMessageQueue(private val persistence: SignalStatePersistence) {
    private fun peer(id: String): SignalProtocolAddress {
        require(id.isNotBlank() && id.length <= 200 && !id.contains(':'))
        return SignalProtocolAddress(id, LIBSIGNAL_DEVICE_INDEX)
    }
    internal fun requireOwner(deviceId: String) = persistence.requireOwner(deviceId)
    private fun requireId(id: String) { require(id.isNotBlank() && id.length <= 512) }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun records(store: org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore) = store as SignalDeliveryRecords

    fun prepare(id: String, recipientDeviceId: String, plaintext: ByteArray,
        nowEpochSec: Long, expiresAtEpochSec: Long, preKeyDeadlineEpochSec: Long? = null): QueuedSignalMessage {
        requireId(id); val address = peer(recipientDeviceId)
        require(plaintext.size in 1..65_536)
        require(nowEpochSec > 0 && expiresAtEpochSec > nowEpochSec &&
            expiresAtEpochSec - nowEpochSec <= 14 * 24 * 3600)
        val immutablePlaintext = plaintext.copyOf()
        val encodedPlain = Base64.getEncoder().encodeToString(immutablePlaintext)
        val request = "$recipientDeviceId:$expiresAtEpochSec:$encodedPlain"
        val requestDigest = digest((if (preKeyDeadlineEpochSec == null) request else "$request:$preKeyDeadlineEpochSec")
            .toByteArray(Charsets.UTF_8))
        return persistence.use { _, store ->
            val state = records(store)
            state.outbox[id]?.let {
                check(it.requestDigest == requestDigest) { "Outbox id is already bound to another message." }
                return@use it
            }
            check(state.outbox.size < 1000) { "Outbox capacity reached." }
            val ciphertext = SignalWireCodec.encrypt(SessionCipher(store, address), immutablePlaintext)
            val deadline = if (preKeyDeadlineEpochSec != null && SignalWireCodec.isPreKeyMessage(ciphertext)) {
                check(preKeyDeadlineEpochSec > nowEpochSec) { "Initial session expired before delivery." }
                minOf(expiresAtEpochSec, preKeyDeadlineEpochSec)
            } else expiresAtEpochSec
            QueuedSignalMessage(id, recipientDeviceId, Base64.getEncoder().encodeToString(ciphertext),
                deadline, requestDigest).also { state.outbox[id] = it }
        }
    }

    fun pendingOutbox(): List<QueuedSignalMessage> = persistence.use { _, store ->
        records(store).outbox.values.filter { it.relayEnvelopeId == null }
    }

    fun discardExpiredOutbox(nowEpochSec: Long): Int {
        require(nowEpochSec > 0)
        return persistence.use { _, store ->
            val outbox = records(store).outbox
            val previousSize = outbox.size
            outbox.values.removeAll { it.relayEnvelopeId == null && it.expiresAtEpochSec <= nowEpochSec }
            previousSize - outbox.size
        }
    }

    internal fun restartExpiredInitialSession(deviceId: String, nowEpochSec: Long) {
        require(nowEpochSec > 0)
        val address = peer(deviceId)
        persistence.use { _, store ->
            check(records(store).outbox.values.none {
                it.recipientDeviceId == deviceId && (it.relayEnvelopeId == null || it.expiresAtEpochSec > nowEpochSec)
            }) {
                "Earlier messages must expire or be handled before starting a new session."
            }
            if (!store.containsSession(address)) return@use
            // This working copy is either discarded or the session is deleted;
            // the probe never advances a durable ratchet or leaves the device.
            val probe = SignalWireCodec.encrypt(SessionCipher(store, address), byteArrayOf(0))
            check(SignalWireCodec.isPreKeyMessage(probe)) { "This session has already exchanged messages." }
            store.deleteSession(address)
        }
    }

    fun markSubmitted(id: String, relayEnvelopeId: String) {
        requireId(id); requireId(relayEnvelopeId)
        persistence.use { _, store ->
            val state = records(store); val item = state.outbox[id] ?: error("Unknown outbox id.")
            check(item.relayEnvelopeId == null || item.relayEnvelopeId == relayEnvelopeId)
            state.outbox[id] = item.copy(relayEnvelopeId = relayEnvelopeId)
        }
    }

    fun accept(relayEnvelopeId: String, senderDeviceId: String, ciphertext: ByteArray): ReceivedSignalMessage {
        requireId(relayEnvelopeId); val address = peer(senderDeviceId)
        require(ciphertext.size in 1..131_072)
        val immutableCiphertext = ciphertext.copyOf()
        val sender = senderDeviceId.toByteArray(Charsets.UTF_8)
        val id = digest(java.nio.ByteBuffer.allocate(4 + sender.size + immutableCiphertext.size)
            .putInt(sender.size).put(sender).put(immutableCiphertext).array())
        return persistence.use { _, store ->
            val state = records(store)
            check(state.receipts[relayEnvelopeId]?.let { it == id } != false) { "Envelope id changed its contents." }
            check(state.receipts.containsKey(relayEnvelopeId) || state.receipts.size < 10_000) { "Receipt capacity reached." }
            val message = state.inbox[id] ?: run {
                check(state.inbox.size < 1000) { "Inbox capacity reached." }
                val plaintext = SignalWireCodec.decrypt(SessionCipher(store, address), immutableCiphertext)
                require(plaintext.size in 1..65_536) { "Message payload exceeds the supported size." }
                ReceivedSignalMessage(id, senderDeviceId, Base64.getEncoder().encodeToString(plaintext))
                    .also { state.inbox[id] = it }
            }
            // These changes and native pre-key/ratchet changes commit together.
            state.receipts[relayEnvelopeId] = id
            state.pendingAcks[relayEnvelopeId] = id
            message
        }
    }

    fun inbox(): List<ReceivedSignalMessage> = persistence.use { _, store -> records(store).inbox.values.toList() }
    fun pendingAcknowledgements(): List<String> = persistence.use { _, store -> records(store).pendingAcks.keys.toList() }

    /** Call only AFTER a successful relay ACK. The durable inbox row is retained. */
    fun markAcknowledged(relayEnvelopeId: String) {
        requireId(relayEnvelopeId)
        persistence.use { _, store -> records(store).pendingAcks.remove(relayEnvelopeId) }
    }
}
