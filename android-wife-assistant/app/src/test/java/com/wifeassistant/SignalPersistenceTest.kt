package com.wifeassistant

import com.wifeassistant.data.crypto.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.Executors

private fun bundleBytes(b: SignalPreKeyBundleDto) =
    Json.encodeToString(SignalPreKeyBundleDto.serializer(), b).toByteArray(Charsets.UTF_8)
private fun signing(id: String) = DeviceIdentityCodec.generate().copy(deviceId = id)
private fun trust(provider: LibsignalCryptoProvider, who: LocalIdentity, bundle: SignalPreKeyBundleDto) =
    provider.trustPeer(who.public, bundle, SignalPeerBinding.sign(who, bundle))

class SignalPersistenceTest {
    private class Disk {
        var value: String? = null; var writes = 0; var fail = false
        fun open() = LibsignalCryptoProvider(SignalStatePersistence({ value }, {
            if (fail) error("synthetic secret in provider error")
            value = it; writes++
        }))
    }

    @Test fun releaseFactoryRefusesEphemeralSignalStore() {
        assertTrue(runCatching { CryptoProviderFactory.create(CryptoBackend.SIGNAL, false) }.isFailure)
    }

    @Test fun bindingProofCoversDeviceRegistrationAndSignalIdentity() {
        val p = LibsignalCryptoProvider(LibsignalKeys.generateIdentity()); val owner = signing("B")
        val bundle = p.publishableBundle("B"); val sig = SignalPeerBinding.sign(owner, bundle)
        SignalPeerBinding.verify(owner.public, bundle, sig)
        for (tampered in listOf(bundle.copy(deviceId = "other"), bundle.copy(registrationId = bundle.registrationId + 1),
            bundle.copy(identityKeyB64 = "AQ=="))) {
            assertTrue(runCatching { SignalPeerBinding.verify(owner.public, tampered, sig) }.isFailure)
        }
    }

    @Test fun encryptedStateCannotBeReboundToAnotherDeviceIdentity() {
        var disk: String? = null
        fun open(owner: String) = LibsignalCryptoProvider(SignalStatePersistence({ disk }, { disk = it }, owner))
        open("A").publishableBundle("A")
        val stable = disk
        assertTrue(runCatching { open("B").hasSession("peer") }.isFailure)
        assertTrue(runCatching { open("A").publishableBundle("B", 2, 2, 2) }.isFailure)
        assertEquals(stable, disk)
    }

    @Test fun corruptStateNeverRotatesOrLeaksItsCause() {
        val disk = Disk(); disk.value = "{synthetic private corruption"
        val failure = runCatching { disk.open().hasSession("B") }.exceptionOrNull()!!
        assertNull(failure.cause); assertFalse(failure.toString().contains("synthetic private"))
        assertEquals(0, disk.writes); assertEquals("{synthetic private corruption", disk.value)
    }

    @Test fun failedCommitWithholdsBundleAndBlocksFurtherAccess() {
        val disk = Disk(); val p = disk.open(); disk.fail = true
        val failure = runCatching { p.publishableBundle("A") }.exceptionOrNull()!!
        assertFalse(failure.toString().contains("synthetic secret")); assertNull(failure.cause)
        disk.fail = false
        assertTrue(runCatching { p.hasSession("B") }.isFailure)
        assertNull(disk.value)
    }

    @Test fun concurrentProvidersCannotOverwriteFirstIdentityOrPreKeys() {
        val disk = Disk(); val pool = Executors.newFixedThreadPool(8)
        try {
            val results = pool.invokeAll(List(16) { Callable { runCatching { disk.open().publishableBundle("A") } } })
                .map { it.get() }
            assertEquals(1, results.count { it.isSuccess }); assertEquals(1, disk.writes)
        } finally { pool.shutdownNow() }
    }

    @Test fun missingPreviouslyLoadedStateDoesNotCreateReplacement() {
        val disk = Disk(); val p = disk.open(); p.publishableBundle("A"); disk.value = null
        assertTrue(runCatching { p.hasSession("B") }.isFailure); assertEquals(1, disk.writes)
    }

    @Test fun independentCheckpointRefusesAnOlderValidSnapshotAfterRestart() {
        var snapshot: String? = null
        var seal: String? = null
        fun open() = LibsignalCryptoProvider(SignalStatePersistence({ snapshot }, { snapshot = it }, "A",
            SignalSnapshotCheckpoint({ seal }, { seal = it })))
        assertEquals(1, open().nextPublishableBundle("A").oneTimePreKeyId)
        val older = snapshot
        assertEquals(2, open().nextPublishableBundle("A").oneTimePreKeyId)
        val latest = snapshot
        snapshot = older
        assertTrue(runCatching { open().nextPublishableBundle("A") }.isFailure)
        assertEquals(older, snapshot)
        snapshot = latest
        assertEquals(3, open().nextPublishableBundle("A").oneTimePreKeyId)
        seal = null
        assertTrue(runCatching { open().nextPublishableBundle("A") }.isFailure)
    }

    @Test fun unsealedWriteIsReconciledAfterRestartBeforeNewOutputIsReleased() {
        var snapshot: String? = null
        var seal: String? = null
        var failFinalWrite = false
        fun open() = LibsignalCryptoProvider(SignalStatePersistence({ snapshot }, { snapshot = it }, "A",
            SignalSnapshotCheckpoint({ seal }, { value ->
                val (previous, next) = value.split(':')
                if (failFinalWrite && previous == next) error("synthetic power interruption")
                seal = value
            })))
        assertEquals(1, open().nextPublishableBundle("A").oneTimePreKeyId)
        failFinalWrite = true
        val interrupted = open()
        assertTrue(runCatching { interrupted.nextPublishableBundle("A") }.isFailure)
        assertTrue(runCatching { interrupted.hasSession("B") }.isFailure)
        failFinalWrite = false
        assertFalse(open().hasSession("B"))
        assertEquals(3, open().nextPublishableBundle("A").oneTimePreKeyId)
    }

    @Test fun changedOrUnboundSignalIdentityIsRejectedWithoutStateMutation() {
        val disk = Disk(); val alice = disk.open(); val bob = LibsignalCryptoProvider(LibsignalKeys.generateIdentity())
        val bobKey = signing("B"); val bundle = bob.publishableBundle("B")
        assertTrue(runCatching { alice.establishSession("B", bundleBytes(bundle)) }.isFailure)
        trust(alice, bobKey, bundle); alice.establishSession("B", bundleBytes(bundle))
        val stable = disk.value
        val impostor = LibsignalCryptoProvider(LibsignalKeys.generateIdentity()).publishableBundle("B")
        assertTrue(runCatching { alice.trustPeer(bobKey.public, impostor, SignalPeerBinding.sign(signing("B"), impostor)) }.isFailure)
        assertTrue(runCatching { trust(disk.open(), bobKey, impostor) }.isFailure)
        assertEquals(stable, disk.value)
    }

    @Test fun failedRatchetCommitDoesNotReleaseCiphertextOrLoseDurableSession() {
        val disk = Disk(); val alice = disk.open(); val bob = LibsignalCryptoProvider(LibsignalKeys.generateIdentity())
        val bobKey = signing("B"); val b = bob.publishableBundle("B")
        trust(alice, bobKey, b); alice.establishSession("B", bundleBytes(b))
        val before = disk.value; disk.fail = true
        assertTrue(runCatching { alice.session("B").encrypt("not released".toByteArray()) }.isFailure)
        assertEquals(before, disk.value); disk.fail = false
        val ciphertext = disk.open().session("B").encrypt("durable".toByteArray())
        assertEquals("durable", String(bob.session("A").decrypt(ciphertext)))
    }

}
