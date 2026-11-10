package com.wifeassistant

import com.wifeassistant.data.crypto.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class SignalPreKeyAllocationTest {
    private class Disk {
        var value: String? = null
        var fail = false
        var writes = 0
        fun open(owner: String = "B") = LibsignalCryptoProvider(SignalStatePersistence({ value }, {
            if (fail) error("synthetic private storage error")
            value = it
            writes++
        }, owner))
    }

    @Test fun restartContinuesEachIndependentHighWaterMark() {
        val disk = Disk()
        val first = disk.open().publishableBundle("B", 7, 11, 19)
        val next = disk.open().nextPublishableBundle("B")
        assertEquals(8, next.signedPreKeyId)
        assertEquals(12, next.kyberPreKeyId)
        assertEquals(20, next.oneTimePreKeyId)
        assertEquals(first.identityKeyB64, next.identityKeyB64)
        assertEquals(first.registrationId, next.registrationId)
        assertEquals(21, disk.open().nextPublishableBundle("B").oneTimePreKeyId)
    }

    @Test fun concurrentReopenedProvidersAllocateDistinctCommittedBundles() {
        val disk = Disk()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val bundles = pool.invokeAll(List(8) { Callable { disk.open().nextPublishableBundle("B") } })
                .map { it.get() }
            assertEquals((1..8).toSet(), bundles.map { it.oneTimePreKeyId }.toSet())
            assertEquals((1..8).toSet(), bundles.map { it.signedPreKeyId }.toSet())
            assertEquals((1..8).toSet(), bundles.map { it.kyberPreKeyId }.toSet())
            assertEquals(1, bundles.map { it.identityKeyB64 }.toSet().size)
            assertEquals(8, disk.writes)
        } finally { pool.shutdownNow() }
    }

    @Test fun pendingCiphertextSurvivesReplenishmentAndConsumedIdIsNotReused() {
        val disk = Disk()
        val bobIdentity = DeviceIdentityCodec.generate().copy(deviceId = "B")
        val aliceIdentity = DeviceIdentityCodec.generate().copy(deviceId = "A")
        val alice = LibsignalCryptoProvider(LibsignalKeys.generateIdentity())
        val aliceBundle = alice.publishableBundle("A")
        val oldBundle = disk.open().nextPublishableBundle("B")
        alice.trustPeer(bobIdentity.public, oldBundle, SignalPeerBinding.sign(bobIdentity, oldBundle))
        alice.establishSession("B", Json.encodeToString(SignalPreKeyBundleDto.serializer(), oldBundle).toByteArray())
        val ciphertext = alice.session("B").encrypt("in flight".toByteArray())
        disk.open().nextPublishableBundle("B")
        val bob = disk.open()
        bob.trustPeer(aliceIdentity.public, aliceBundle, SignalPeerBinding.sign(aliceIdentity, aliceBundle))
        assertEquals("in flight", String(bob.session("A").decrypt(ciphertext)))
        assertEquals(3, disk.open().nextPublishableBundle("B").oneTimePreKeyId)
        val stable = disk.value
        assertTrue(runCatching { disk.open().publishableBundle("B", 4, 4, 1) }.isFailure)
        assertEquals(stable, disk.value)
    }

    @Test fun failedCommitWithholdsBundleAndBlocksTheProvider() {
        val disk = Disk()
        disk.open().nextPublishableBundle("B")
        val stable = disk.value
        val provider = disk.open()
        disk.fail = true
        val failure = runCatching { provider.nextPublishableBundle("B") }.exceptionOrNull()!!
        assertFalse(failure.toString().contains("synthetic private"))
        assertNull(failure.cause)
        assertEquals(stable, disk.value)
        disk.fail = false
        assertTrue(runCatching { provider.nextPublishableBundle("B") }.isFailure)
        assertEquals(2, disk.open().nextPublishableBundle("B").oneTimePreKeyId)
    }

    @Test fun consumingTheLastOneTimeKeyDoesNotResetItsCounter() {
        val disk = Disk()
        val bobIdentity = DeviceIdentityCodec.generate().copy(deviceId = "B")
        val aliceIdentity = DeviceIdentityCodec.generate().copy(deviceId = "A")
        val alice = LibsignalCryptoProvider(LibsignalKeys.generateIdentity())
        val aliceBundle = alice.publishableBundle("A")
        val bundle = disk.open().nextPublishableBundle("B")
        alice.trustPeer(bobIdentity.public, bundle, SignalPeerBinding.sign(bobIdentity, bundle))
        alice.establishSession("B", Json.encodeToString(SignalPreKeyBundleDto.serializer(), bundle).toByteArray())
        val bob = disk.open()
        bob.trustPeer(aliceIdentity.public, aliceBundle, SignalPeerBinding.sign(aliceIdentity, aliceBundle))
        assertEquals("last key", String(bob.session("A").decrypt(alice.session("B").encrypt("last key".toByteArray()))))
        assertTrue(Json.parseToJsonElement(disk.value!!).jsonObject.getValue("preKeys").jsonObject.isEmpty())
        assertEquals(2, disk.open().nextPublishableBundle("B").oneTimePreKeyId)
    }

    @Test fun exhaustionNeverWrapsOrPartiallyWrites() {
        for (ids in listOf(Triple(Int.MAX_VALUE, 1, 1), Triple(1, Int.MAX_VALUE, 1), Triple(1, 1, Int.MAX_VALUE))) {
            val disk = Disk()
            disk.open().publishableBundle("B", ids.first, ids.second, ids.third)
            val stable = disk.value
            val provider = disk.open()
            assertTrue(runCatching { provider.nextPublishableBundle("B") }.isFailure)
            assertEquals(stable, disk.value)
            assertEquals(1, disk.writes)
            assertFalse(provider.hasSession("A"))
        }
    }

    @Test fun wrongOwnerAndEphemeralStorageCannotAllocate() {
        val disk = Disk()
        assertTrue(runCatching { disk.open().nextPublishableBundle("other") }.isFailure)
        assertEquals(0, disk.writes)
        assertTrue(runCatching {
            LibsignalCryptoProvider(LibsignalKeys.generateIdentity()).nextPublishableBundle("B")
        }.isFailure)
    }
}
