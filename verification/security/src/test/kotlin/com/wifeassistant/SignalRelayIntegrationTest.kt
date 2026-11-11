package com.wifeassistant

import com.wifeassistant.data.crypto.*
import com.wifeassistant.data.pairing.DirectRelayClient
import com.wifeassistant.data.pairing.DirectSignalDelivery
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Path
import java.util.Base64

/** Real Android transport sources + real Signal native crypto + actual Node API
 * handlers over loopback. Synthetic clients, memory relay, no Android Keystore.
 */
class SignalRelayIntegrationTest {
    @Test(timeout = 30_000) fun offlineDeliveryReplyRestartAndRevocationThroughActualHttpHandlers() = runBlocking {
        val repository = System.getProperty("wisal.repository") ?: generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { java.nio.file.Files.exists(it.resolve("verification/security/RelayHarness.cjs")) }.toString()
        val child = ProcessBuilder("node", Path.of(repository, "verification/security/RelayHarness.cjs").toString())
            .redirectErrorStream(true).start()
        try {
            val reader = child.inputStream.bufferedReader()
            val ready = reader.readLine()
            check(ready?.startsWith("READY ") == true) { "Loopback relay failed to start" }
            val relay = DirectRelayClient("http://127.0.0.1:" + ready.substringAfter("READY "))
            val now = System.currentTimeMillis() / 1000
            val a = DeviceIdentityCodec.generate(); val b = DeviceIdentityCodec.generate()
            relay.registerDevice(a).getOrThrow(); relay.registerDevice(b).getOrThrow()
            var aDisk: String? = null; var bDisk: String? = null
            fun aState() = SignalStatePersistence({ aDisk }, { aDisk = it }, a.deviceId)
            fun bState() = SignalStatePersistence({ bDisk }, { bDisk = it }, b.deviceId)
            fun openA() = LibsignalCryptoProvider(aState())
            fun openB() = LibsignalCryptoProvider(bState())
            val aq = SignalMessageQueue(aState()); val bq = SignalMessageQueue(bState())
            val ad = DirectSignalDelivery(relay, aq); val bd = DirectSignalDelivery(relay, bq)
            val alice = openA(); val bob = openB()
            val ab = alice.publishableBundle(a.deviceId); val bb = bob.publishableBundle(b.deviceId)
            alice.trustPeer(b.public, bb, SignalPeerBinding.sign(b, bb))
            bob.trustPeer(a.public, ab, SignalPeerBinding.sign(a, ab))
            relay.publishPreKeys(a, ab, now).getOrThrow(); relay.publishPreKeys(b, bb, now).getOrThrow()
            val claimed = relay.claimPreKeys(a, b.deviceId, now).getOrThrow()
            assertTrue(relay.claimPreKeys(a, b.deviceId, now).isFailure)
            alice.establishSession(b.deviceId, Json.encodeToString(SignalPreKeyBundleDto.serializer(), claimed).toByteArray())
            val plain = "اختبار اصطناعي عبر HTTP | synthetic offline message"
            val outgoing = aq.prepare("first", b.deviceId, plain.toByteArray(Charsets.UTF_8), now, now + 600)
            val ciphertext = Base64.getDecoder().decode(outgoing.ciphertextB64)
            assertEquals(1, ad.flushOutbox(a, now).getOrThrow())
            // Simulate an ambiguous submit: same saved ciphertext under another relay id.
            relay.submitEnvelope(a, b.deviceId, outgoing.ciphertextB64, "SIGNAL", now + 600).getOrThrow()
            assertEquals(2, relay.fetchInbox(b, now).getOrThrow().size)
            assertEquals(2, bd.receiveAndAcknowledge(b, now).getOrThrow())
            val received = SignalMessageQueue(bState()).inbox().single()
            assertEquals(plain, String(Base64.getDecoder().decode(received.plaintextB64), Charsets.UTF_8))
            assertTrue(relay.fetchInbox(b, now).getOrThrow().isEmpty())
            assertTrue(bq.pendingAcknowledgements().isEmpty())
            bq.prepare("reply", a.deviceId, "reply after reload".toByteArray(), now, now + 600)
            assertEquals(1, bd.flushOutbox(b, now).getOrThrow())
            assertEquals(1, ad.receiveAndAcknowledge(a, now).getOrThrow())
            assertEquals("reply after reload", String(Base64.getDecoder().decode(aq.inbox().single().plaintextB64)))
            relay.revokeDevice(b, now).getOrThrow()
            assertTrue(relay.claimPreKeys(a, b.deviceId, now).isFailure)
            assertTrue(relay.submitEnvelope(a, b.deviceId, Base64.getEncoder().encodeToString(ciphertext), "SIGNAL", now + 600).isFailure)
            assertTrue(relay.fetchInbox(b, now).isFailure)
            assertFalse(openA().mayClaimE2ee)
        } finally { child.destroyForcibly(); child.waitFor() }
    }

    @Test(timeout = 30_000) fun ackResponseLossRecoversWithoutLosingOrDuplicatingTheMessage() = runBlocking {
        val root = System.getProperty("wisal.repository") ?: generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { java.nio.file.Files.exists(it.resolve("verification/security/RelayHarness.cjs")) }.toString()
        val builder = ProcessBuilder("node", Path.of(root, "verification/security/RelayHarness.cjs").toString())
            .redirectErrorStream(true)
        builder.environment()["WISAL_TEST_DROP_ACK_ONCE"] = "1"
        val process = builder.start()
        try {
            val ready = process.inputStream.bufferedReader().readLine()
            check(ready.startsWith("READY "))
            val relay = DirectRelayClient("http://127.0.0.1:" + ready.substringAfter("READY "))
            val a = DeviceIdentityCodec.generate(); val b = DeviceIdentityCodec.generate()
            val now = System.currentTimeMillis() / 1000
            relay.registerDevice(a).getOrThrow(); relay.registerDevice(b).getOrThrow()
            var saved: String? = null
            fun state() = SignalStatePersistence({ saved }, { saved = it }, b.deviceId)
            val bob = LibsignalCryptoProvider(state())
            val alice = LibsignalCryptoProvider(LibsignalKeys.generateIdentity())
            val ab = alice.publishableBundle(a.deviceId); val bb = bob.publishableBundle(b.deviceId)
            bob.trustPeer(a.public, ab, SignalPeerBinding.sign(a, ab))
            alice.establishSession(b.deviceId, Json.encodeToString(SignalPreKeyBundleDto.serializer(), bb).toByteArray())
            val ciphertext = alice.session(b.deviceId).encrypt("durable before ack".toByteArray())
            relay.submitEnvelope(a, b.deviceId, Base64.getEncoder().encodeToString(ciphertext), "SIGNAL", now + 600).getOrThrow()
            val first = DirectSignalDelivery(relay, SignalMessageQueue(state())).receiveAndAcknowledge(b, now)
            assertTrue(first.isFailure)
            assertEquals(1, SignalMessageQueue(state()).inbox().size)
            assertEquals(1, SignalMessageQueue(state()).pendingAcknowledgements().size)
            assertTrue(relay.fetchInbox(b, now).getOrThrow().isEmpty())
            // The old ACK now returns "not found"; only its local intent is removed.
            val retry = DirectSignalDelivery(relay, SignalMessageQueue(state())).receiveAndAcknowledge(b, now)
            assertTrue(retry.isSuccess)
            assertTrue(SignalMessageQueue(state()).pendingAcknowledgements().isEmpty())
            assertEquals("durable before ack", String(Base64.getDecoder().decode(SignalMessageQueue(state()).inbox().single().plaintextB64)))
        } finally { process.destroyForcibly(); process.waitFor() }
    }

}
