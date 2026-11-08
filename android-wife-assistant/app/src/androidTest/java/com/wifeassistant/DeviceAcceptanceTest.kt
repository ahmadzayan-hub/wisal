package com.wifeassistant

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wifeassistant.data.SecureStore
import com.wifeassistant.data.crypto.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** Synthetic secrets only, in an isolated application UID. Each phase is a new
 * instrumentation process; never point these tests at com.wisal.app. */
@RunWith(AndroidJUnit4::class)
class DeviceAcceptanceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun runRequestedPhase() {
        check(context.packageName == "com.wisal.app.acceptance")
        when (InstrumentationRegistry.getArguments().getString("phase")) {
            "prepare" -> prepare()
            "resume" -> resume()
            "verify" -> verify()
            "keyLoss" -> keyLoss()
            else -> error("Choose prepare, resume, verify or keyLoss explicitly")
        }
    }

    private fun peerState() = SignalStatePersistence(
        { SecureStore.getSecret(context, "AcceptancePeerState") },
        { SecureStore.setSecret(context, "AcceptancePeerState", it) }, "AcceptancePeer",
    )

    private fun prepare() {
        // Re-running preparation would reset the reference and hide identity loss.
        check(SecureStore.getSecret(context, "AcceptanceOwnerPublic") == null)
        val workers = Executors.newFixedThreadPool(4)
        val owner = try {
            val identities = workers.invokeAll(List(12) { Callable { DeviceIdentityStore.getOrCreate(context) } }).map { it.get() }
            assertTrue(identities.all { it.public == identities.first().public })
            identities.first()
        } finally { workers.shutdownNow() }
        val saved = checkNotNull(SecureStore.getSecret(context, "deviceIdentity"))
        try {
            SecureStore.setSecret(context, "deviceIdentity", "synthetic malformed identity")
            assertTrue(runCatching { DeviceIdentityStore.getOrCreate(context) }.isFailure)
            assertEquals("synthetic malformed identity", SecureStore.getSecret(context, "deviceIdentity"))
        } finally { SecureStore.setSecret(context, "deviceIdentity", saved) }
        assertEquals(owner.public, DeviceIdentityStore.getOrCreate(context).public)
        val preferences = context.getSharedPreferences("wisal_secure", Context.MODE_PRIVATE).all.toString()
        assertFalse(preferences.contains(owner.privateKeyB64))
        assertFalse(preferences.contains(saved))
        assertTrue(context.getSharedPreferences("wife_assistant_settings", Context.MODE_PRIVATE).all.isEmpty())

        val a = AndroidSignalProvider.get(context)
        val b = LibsignalCryptoProvider(peerState())
        val peer = DeviceIdentityCodec.generate().copy(deviceId = "AcceptancePeer")
        val ab = a.publishableBundle(owner.deviceId)
        val bb = b.publishableBundle(peer.deviceId)
        a.trustPeer(peer.public, bb, SignalPeerBinding.sign(peer, bb))
        b.trustPeer(owner.public, ab, SignalPeerBinding.sign(owner, ab))
        a.establishSession(peer.deviceId, Json.encodeToString(SignalPreKeyBundleDto.serializer(), bb).toByteArray())
        val now = System.currentTimeMillis() / 1000
        AndroidSignalProvider.deliveryQueue(context).prepare("AcceptanceMessage", peer.deviceId,
            "synthetic Android lifecycle message".toByteArray(), now, now + 86_400)
        SecureStore.setSecret(context, "AcceptanceOwnerPublic", owner.deviceId + ":" + owner.publicKeyB64)
        assertFalse(a.mayClaimE2ee)
    }

    private fun assertOwner() {
        val owner = DeviceIdentityStore.getOrCreate(context)
        assertEquals(checkNotNull(SecureStore.getSecret(context, "AcceptanceOwnerPublic")),
            owner.deviceId + ":" + owner.publicKeyB64)
    }

    private fun resume() {
        assertOwner()
        val owner = DeviceIdentityStore.getOrCreate(context)
        val a = AndroidSignalProvider.get(context)
        assertTrue(a.hasSession("AcceptancePeer"))
        val outbox = AndroidSignalProvider.deliveryQueue(context).pendingOutbox().single()
        val b = SignalMessageQueue(peerState())
        val received = b.accept("AcceptanceRelayMessage", owner.deviceId, Base64.getDecoder().decode(outbox.ciphertextB64))
        assertEquals("synthetic Android lifecycle message", String(Base64.getDecoder().decode(received.plaintextB64)))
        val now = System.currentTimeMillis() / 1000
        val reply = b.prepare("AcceptanceReply", owner.deviceId, "synthetic reply".toByteArray(), now, now + 600)
        AndroidSignalProvider.deliveryQueue(context).accept("AcceptanceRelayReply", "AcceptancePeer",
            Base64.getDecoder().decode(reply.ciphertextB64))
        assertFalse(a.mayClaimE2ee)
    }

    private fun verify() {
        assertOwner()
        val queue = AndroidSignalProvider.deliveryQueue(context)
        assertEquals("synthetic reply", String(Base64.getDecoder().decode(queue.inbox().single().plaintextB64)))
        assertEquals(listOf("AcceptanceRelayReply"), queue.pendingAcknowledgements())
        assertEquals(1, queue.pendingOutbox().size)
        assertEquals(1, SignalMessageQueue(peerState()).inbox().size)
        assertTrue(LibsignalCryptoProvider(peerState()).hasSession(DeviceIdentityStore.getOrCreate(context).deviceId))
    }

    private fun keyLoss() {
        assertOwner()
        val queue = AndroidSignalProvider.deliveryQueue(context)
        queue.inbox()
        val preferences = context.getSharedPreferences("wisal_secure", Context.MODE_PRIVATE)
        val before = HashMap(preferences.all)
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "_androidx_security_master_key_"
        check(keys.containsAlias(alias))
        // Only the isolated acceptance UID can lose its key. Run this phase last.
        keys.deleteEntry(alias)
        assertTrue(runCatching { DeviceIdentityStore.getOrCreate(context) }.isFailure)
        assertTrue(runCatching { queue.inbox() }.isFailure)
        assertEquals(before, preferences.all)
        assertTrue(context.getSharedPreferences("wife_assistant_settings", Context.MODE_PRIVATE).all.isEmpty())
    }
}
