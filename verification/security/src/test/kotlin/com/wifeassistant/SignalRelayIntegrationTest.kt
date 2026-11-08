package com.wifeassistant

import com.wifeassistant.data.crypto.*
import com.wifeassistant.data.pairing.DirectRelayClient
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
            fun openA() = LibsignalCryptoProvider(SignalStatePersistence({ aDisk }, { aDisk = it }))
            fun openB() = LibsignalCryptoProvider(SignalStatePersistence({ bDisk }, { bDisk = it }))
            val alice = openA(); val bob = openB()
            val ab = alice.publishableBundle(a.deviceId); val bb = bob.publishableBundle(b.deviceId)
            alice.trustPeer(b.public, bb, SignalPeerBinding.sign(b, bb))
            bob.trustPeer(a.public, ab, SignalPeerBinding.sign(a, ab))
            relay.publishPreKeys(a, ab, now).getOrThrow(); relay.publishPreKeys(b, bb, now).getOrThrow()
            val claimed = relay.claimPreKeys(a, b.deviceId, now).getOrThrow()
            assertTrue(relay.claimPreKeys(a, b.deviceId, now).isFailure)
            alice.establishSession(b.deviceId, Json.encodeToString(SignalPreKeyBundleDto.serializer(), claimed).toByteArray())
            val plain = "اختبار اصطناعي عبر HTTP | synthetic offline message"
            val ciphertext = alice.session(b.deviceId).encrypt(plain.toByteArray(Charsets.UTF_8))
            val envelopeId = relay.submitEnvelope(a, b.deviceId, Base64.getEncoder().encodeToString(ciphertext), "SIGNAL", now + 600).getOrThrow()
            // Bob was offline during submit. Recreate the client from durable prekeys.
            val inbox = relay.fetchInbox(b, now).getOrThrow(); assertEquals(1, inbox.size)
            assertEquals(envelopeId, inbox.single().id)
            assertFalse(inbox.single().ciphertextB64.contains(plain))
            val received = openB().session(a.deviceId).decrypt(Base64.getDecoder().decode(inbox.single().ciphertextB64))
            assertEquals(plain, String(received, Charsets.UTF_8))
            relay.ackDelivery(b, envelopeId).getOrThrow()
            assertTrue(relay.fetchInbox(b, now).getOrThrow().isEmpty())
            val reply = openB().session(a.deviceId).encrypt("reply after reload".toByteArray())
            relay.submitEnvelope(b, a.deviceId, Base64.getEncoder().encodeToString(reply), "SIGNAL", now + 600).getOrThrow()
            val back = relay.fetchInbox(a, now).getOrThrow().single()
            assertEquals("reply after reload", String(openA().session(b.deviceId).decrypt(Base64.getDecoder().decode(back.ciphertextB64))))
            relay.ackDelivery(a, back.id).getOrThrow()
            relay.revokeDevice(b, now).getOrThrow()
            assertTrue(relay.claimPreKeys(a, b.deviceId, now).isFailure)
            assertTrue(relay.submitEnvelope(a, b.deviceId, Base64.getEncoder().encodeToString(ciphertext), "SIGNAL", now + 600).isFailure)
            assertTrue(relay.fetchInbox(b, now).isFailure)
            assertFalse(openA().mayClaimE2ee)
        } finally { child.destroyForcibly(); child.waitFor() }
    }
}
