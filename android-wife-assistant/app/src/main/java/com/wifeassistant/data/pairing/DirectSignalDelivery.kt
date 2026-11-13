package com.wifeassistant.data.pairing

import com.wifeassistant.data.crypto.DeviceSigningIdentity
import com.wifeassistant.data.crypto.SignalMessageQueue
import kotlinx.coroutines.CancellationException
import java.util.Base64

/** Transport orchestration over durable queues. It never stores plaintext on
 * the relay or ACKs an envelope before the inbox/ratchet snapshot is committed.
 * UI activation and E2EE release acceptance are separate requirements.
 */
class DirectSignalDelivery(private val relay: DirectRelayClient, private val queue: SignalMessageQueue) {
    suspend fun flushOutbox(identity: DeviceSigningIdentity, nowEpochSec: Long,
        continuePastExpired: Boolean = false): Result<Int> = guarded {
        require(nowEpochSec > 0)
        queue.requireOwner(identity.deviceId)
        var submitted = 0
        for (message in queue.pendingOutbox()) {
            if (continuePastExpired && message.expiresAtEpochSec <= nowEpochSec) continue
            check(message.expiresAtEpochSec > nowEpochSec) { "Pending message expired; explicit user action is required." }
            val receipt = relay.submitEnvelope(identity, message.recipientDeviceId, message.ciphertextB64,
                "SIGNAL", message.expiresAtEpochSec).getOrThrow()
            queue.markSubmitted(message.id, receipt)
            submitted++
        }
        submitted
    }

    suspend fun receiveAndAcknowledge(identity: DeviceSigningIdentity, nowEpochSec: Long,
        verifiedSenderDeviceId: String? = null): Result<Int> = guarded {
        require(nowEpochSec > 0)
        queue.requireOwner(identity.deviceId)
        val inbox = relay.fetchInbox(identity, nowEpochSec).getOrThrow()
            .filter { verifiedSenderDeviceId == null || it.senderDeviceId == verifiedSenderDeviceId }
        var firstFailure: Exception? = null
        for (envelope in inbox) {
            try {
                check(envelope.backend == "SIGNAL") { "Unsupported message backend." }
                queue.accept(envelope.id, envelope.senderDeviceId, Base64.getDecoder().decode(envelope.ciphertextB64))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (verifiedSenderDeviceId == null) throw failure
                if (firstFailure == null) firstFailure = failure
            }
        }
        // ACK only records which were durably accepted, including previous polls.
        for (envelopeId in queue.pendingAcknowledgements()) {
            val result = relay.ackDelivery(identity, envelopeId)
            // A prior ACK can succeed before the local cleanup commit crashes.
            // Removing that pending intent cannot remove the saved inbox row.
            if (result.isFailure && result.exceptionOrNull()?.message != "not found") result.getOrThrow()
            queue.markAcknowledged(envelopeId)
        }
        firstFailure?.let { throw it }
        inbox.size
    }

    private suspend inline fun <T> guarded(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (failure: Exception) { Result.failure(failure) }
}
