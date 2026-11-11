package com.wifeassistant.data.pairing

import com.wifeassistant.data.crypto.LocalIdentity
import com.wifeassistant.data.crypto.SignalMessageQueue
import kotlinx.coroutines.CancellationException
import java.util.Base64

/** Transport orchestration over durable queues. It never stores plaintext on
 * the relay or ACKs an envelope before the inbox/ratchet snapshot is committed.
 * UI activation and E2EE release acceptance are separate requirements.
 */
class DirectSignalDelivery(private val relay: DirectRelayClient, private val queue: SignalMessageQueue) {
    suspend fun flushOutbox(identity: LocalIdentity, nowEpochSec: Long): Result<Int> = guarded {
        require(nowEpochSec > 0)
        queue.requireOwner(identity.deviceId)
        var submitted = 0
        for (message in queue.pendingOutbox()) {
            check(message.expiresAtEpochSec > nowEpochSec) { "Pending message expired; explicit user action is required." }
            val receipt = relay.submitEnvelope(identity, message.recipientDeviceId, message.ciphertextB64,
                "SIGNAL", message.expiresAtEpochSec).getOrThrow()
            queue.markSubmitted(message.id, receipt)
            submitted++
        }
        submitted
    }

    suspend fun receiveAndAcknowledge(identity: LocalIdentity, nowEpochSec: Long): Result<Int> = guarded {
        require(nowEpochSec > 0)
        queue.requireOwner(identity.deviceId)
        val inbox = relay.fetchInbox(identity, nowEpochSec).getOrThrow()
        for (envelope in inbox) {
            check(envelope.backend == "SIGNAL") { "Unsupported message backend." }
            queue.accept(envelope.id, envelope.senderDeviceId, Base64.getDecoder().decode(envelope.ciphertextB64))
        }
        // ACK only records which were durably accepted, including previous polls.
        for (envelopeId in queue.pendingAcknowledgements()) {
            val result = relay.ackDelivery(identity, envelopeId)
            // A prior ACK can succeed before the local cleanup commit crashes.
            // Removing that pending intent cannot remove the saved inbox row.
            if (result.isFailure && result.exceptionOrNull()?.message != "not found") result.getOrThrow()
            queue.markAcknowledged(envelopeId)
        }
        inbox.size
    }

    private suspend inline fun <T> guarded(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (failure: Exception) { Result.failure(failure) }
}
