package com.wifeassistant

import com.wifeassistant.data.crypto.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class SignalMessageQueueTest {
    private class Disk(val owner: String) {
        var value: String? = null; var fail = false
        fun state() = SignalStatePersistence({ value }, { check(!fail) { "synthetic write error" }; value = it }, owner)
        fun provider() = LibsignalCryptoProvider(state())
        fun queue() = SignalMessageQueue(state())
    }
    private class Fixture {
        val a = Disk("A"); val b = Disk("B")
        val now = 1_800_000_000L
        init {
            val ap = a.provider(); val bp = b.provider()
            val ab = ap.publishableBundle("A"); val bb = bp.publishableBundle("B")
            val ak = DeviceIdentityCodec.generate().copy(deviceId = "A")
            val bk = DeviceIdentityCodec.generate().copy(deviceId = "B")
            ap.trustPeer(bk.public, bb, SignalPeerBinding.sign(bk, bb))
            bp.trustPeer(ak.public, ab, SignalPeerBinding.sign(ak, ab))
            ap.establishSession("B", Json.encodeToString(SignalPreKeyBundleDto.serializer(), bb).toByteArray())
        }
        fun outgoing(id: String = "local", text: String = "synthetic message") =
            a.queue().prepare(id, "B", text.toByteArray(), now, now + 600)
        fun ciphertext(message: QueuedSignalMessage) = Base64.getDecoder().decode(message.ciphertextB64)
    }

    @Test fun retryAfterReloadUsesOneCiphertextAndOneDurableOutboxRow() {
        val f = Fixture(); val first = f.outgoing(); val disk = f.a.value
        val again = f.outgoing()
        assertEquals(first, again); assertEquals(disk, f.a.value)
        assertEquals(1, f.a.queue().pendingOutbox().size)
        val received = f.b.queue().accept("relay1", "A", f.ciphertext(first))
        assertEquals("synthetic message", String(Base64.getDecoder().decode(received.plaintextB64)))
        f.a.queue().markSubmitted(first.id, "relay1")
        assertTrue(f.a.queue().pendingOutbox().isEmpty())
        assertTrue(runCatching { f.a.queue().markSubmitted(first.id, "different") }.isFailure)
    }

    @Test fun initialMessageExpiresAtClaimDeadlineAndAnExpiredAttemptCannotAdvanceTheRatchet() {
        val f = Fixture()
        val first = f.a.queue().prepare("bounded", "B", "initial".toByteArray(), f.now, f.now + 600, f.now + 60)
        assertEquals(f.now + 60, first.expiresAtEpochSec)
        val saved = f.a.value
        assertTrue(runCatching {
            f.a.queue().prepare("too-late", "B", "expired".toByteArray(), f.now + 60, f.now + 660, f.now + 60)
        }.isFailure)
        assertEquals(saved, f.a.value)
        assertEquals(1, f.a.queue().discardExpiredOutbox(f.now + 60))
        f.a.queue().restartExpiredInitialSession("B", f.now + 60)
        assertFalse(f.a.provider().hasSession("B"))
    }

    @Test fun outboxIdCannotSilentlyChangePayloadRecipientOrExpiry() {
        val f = Fixture(); f.outgoing(); val saved = f.a.value
        assertTrue(runCatching { f.outgoing(text = "different") }.isFailure)
        assertTrue(runCatching { f.a.queue().prepare("local", "other", "synthetic message".toByteArray(), f.now, f.now + 600) }.isFailure)
        assertTrue(runCatching { f.a.queue().prepare("local", "B", "synthetic message".toByteArray(), f.now, f.now + 601) }.isFailure)
        assertEquals(saved, f.a.value)
    }

    @Test fun plaintextAndRatchetSurviveCrashBeforeUiDeliveryOrAck() {
        val f = Fixture(); val message = f.outgoing()
        val accepted = f.b.queue().accept("relay1", "A", f.ciphertext(message))
        // Discard all queue/provider objects before reading from a fresh instance.
        assertEquals(listOf(accepted), f.b.queue().inbox())
        assertEquals(listOf("relay1"), f.b.queue().pendingAcknowledgements())
        val reply = f.b.provider().session("A").encrypt("reply".toByteArray())
        assertEquals("reply", String(f.a.provider().session("B").decrypt(reply)))
        f.b.queue().markAcknowledged("relay1")
        assertTrue(f.b.queue().pendingAcknowledgements().isEmpty())
        assertEquals(listOf(accepted), f.b.queue().inbox())
    }

    @Test fun duplicateTransportEnvelopeDoesNotDuplicateInboxOrDecryptTwice() {
        val f = Fixture(); val ct = f.ciphertext(f.outgoing())
        val first = f.b.queue().accept("relay1", "A", ct)
        assertEquals(first, f.b.queue().accept("relay1", "A", ct))
        assertEquals(first, f.b.queue().accept("relay2", "A", ct))
        assertEquals(1, f.b.queue().inbox().size)
        assertEquals(setOf("relay1", "relay2"), f.b.queue().pendingAcknowledgements().toSet())
    }

    @Test fun failedInboxCommitDoesNotLoseKeysOrCreateAnAckIntent() {
        val f = Fixture(); val ct = f.ciphertext(f.outgoing()); val before = f.b.value
        val queue = f.b.queue(); f.b.fail = true
        assertTrue(runCatching { queue.accept("relay1", "A", ct) }.isFailure)
        assertEquals(before, f.b.value)
        assertTrue(runCatching { queue.pendingAcknowledgements() }.isFailure)
        f.b.fail = false
        assertTrue(f.b.queue().inbox().isEmpty())
        assertTrue(f.b.queue().pendingAcknowledgements().isEmpty())
        val recovered = f.b.queue().accept("relay1", "A", ct)
        assertEquals(listOf(recovered), f.b.queue().inbox())
        assertEquals(listOf("relay1"), f.b.queue().pendingAcknowledgements())
    }

    @Test fun failedOutboxCommitDoesNotReleaseCiphertextOrAdvanceDurableRatchet() {
        val f = Fixture(); val before = f.a.value; f.a.fail = true
        assertTrue(runCatching { f.outgoing() }.isFailure)
        assertEquals(before, f.a.value); f.a.fail = false
        assertTrue(f.a.queue().pendingOutbox().isEmpty())
        val recovered = f.outgoing()
        f.b.queue().accept("relay1", "A", f.ciphertext(recovered))
        assertEquals(1, f.b.queue().inbox().size)
    }

    @Test fun envelopeIdReuseWithDifferentCiphertextDoesNotAdvanceReceiver() {
        val f = Fixture(); val one = f.outgoing(); f.b.queue().accept("relay1", "A", f.ciphertext(one))
        val two = f.outgoing("two", "second"); val before = f.b.value
        assertTrue(runCatching { f.b.queue().accept("relay1", "A", f.ciphertext(two)) }.isFailure)
        assertEquals(before, f.b.value)
        assertEquals("second", String(Base64.getDecoder().decode(f.b.queue().accept("relay2", "A", f.ciphertext(two)).plaintextB64)))
    }

    @Test fun concurrentDuplicateDeliveriesHaveOneInboxRowAndAllAckIntents() {
        val f = Fixture(); val ct = f.ciphertext(f.outgoing()); val pool = Executors.newFixedThreadPool(4)
        try {
            val ids = pool.invokeAll(List(8) { i -> Callable { f.b.queue().accept("relay$i", "A", ct).id } }).map { it.get() }
            assertEquals(1, ids.distinct().size)
            assertEquals(1, f.b.queue().inbox().size)
            assertEquals(8, f.b.queue().pendingAcknowledgements().size)
        } finally { pool.shutdownNow() }
    }

    @Test fun malformedCiphertextCannotCreateInboxOrAckState() {
        val f = Fixture(); val before = f.b.value
        assertTrue(runCatching { f.b.queue().accept("relay1", "A", byteArrayOf(99, 1)) }.isFailure)
        assertEquals(before, f.b.value); assertTrue(f.b.queue().inbox().isEmpty())
        assertTrue(f.b.queue().pendingAcknowledgements().isEmpty())
    }

    @Test fun capacityRejectionDoesNotBlockReadsOfTheLastDurableState() {
        val f = Fixture(); val state = f.b.state(); val before = f.b.value
        assertTrue(runCatching { state.use { _, store ->
            (store as SignalDeliveryRecords).inbox["oversize"] = ReceivedSignalMessage("oversize", "A", "A".repeat(8 * 1024 * 1024))
        } }.isFailure)
        assertEquals(before, f.b.value)
        assertTrue(SignalMessageQueue(state).inbox().isEmpty())
    }

    @Test fun legacySnapshotReadDoesNotRequireAnUnrelatedMigrationWrite() {
        val f = Fixture()
        val current = Json.parseToJsonElement(checkNotNull(f.b.value)).jsonObject
        val legacy = JsonObject(current.filterKeys { it !in setOf("inbox", "outbox", "receipts", "pendingAcks") }).toString()
        var writes = 0
        val state = SignalStatePersistence({ legacy }, { writes++; error("read-only backend") }, "B")
        assertFalse(LibsignalCryptoProvider(state).hasSession("A"))
        assertTrue(SignalMessageQueue(state).inbox().isEmpty())
        assertEquals(0, writes)
    }

    @Test fun objectDescriptionsDoNotExposeMessageContents() {
        val f = Fixture(); val outgoing = f.outgoing()
        val received = f.b.queue().accept("relay1", "A", f.ciphertext(outgoing))
        assertFalse(outgoing.toString().contains(outgoing.ciphertextB64))
        assertFalse(received.toString().contains(received.plaintextB64))
    }
}
