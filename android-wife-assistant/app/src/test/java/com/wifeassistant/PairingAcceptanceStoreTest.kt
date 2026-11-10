package com.wifeassistant

import com.wifeassistant.data.crypto.DeviceIdentityCodec
import com.wifeassistant.data.pairing.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PairingAcceptanceStoreTest {
    private val now = 1_800_000_000L
    private val identity = DeviceIdentityCodec.generate()
    private fun invitation() = Pairing.create(identity.deviceId, identity.publicKeyB64, "Synthetic peer", now)

    @Test fun peerAndReplayGuardCommitOnceAndSurviveReopening() {
        var disk = PairingState()
        var commits = 0
        val store = PairingAcceptanceStore({ disk }, { disk = it; commits++; true })
        val inv = invitation()
        assertTrue(store.accept(inv, inv.token, now + 1) is PairingResult.Ok)
        assertEquals(1, commits)
        assertEquals(inv.inviterDeviceId, disk.peers.single().deviceId)
        assertEquals(setOf(inv.id), disk.acceptedInvitationIds)
        val reopened = PairingAcceptanceStore({ disk }, { fail("Replay must not write"); false })
        assertEquals(disk, reopened.snapshot())
        assertEquals("already accepted",
            (reopened.accept(inv, inv.token, now + 2) as PairingResult.Refused).reason)
    }

    @Test fun expiredAndWrongTokenCannotWrite() {
        val store = PairingAcceptanceStore({ PairingState() }, { fail("Refused invitation wrote"); false })
        val inv = invitation()
        assertTrue(store.accept(inv, inv.token, inv.expiresAtEpochSec) is PairingResult.Refused)
        assertTrue(store.accept(inv, "wrong", now + 1) is PairingResult.Refused)
    }

    @Test fun failedCommitLatchesEvenIfPreferencesChangedInMemory() {
        var memory = PairingState()
        val store = PairingAcceptanceStore({ memory }, { memory = it; false })
        val inv = invitation()
        assertThrows(IllegalStateException::class.java) { store.accept(inv, inv.token, now + 1) }
        assertThrows(IllegalStateException::class.java) { store.snapshot() }
        val next = invitation()
        assertThrows(IllegalStateException::class.java) { store.accept(next, next.token, now + 2) }
    }

    @Test fun unverifiedCommitCannotReportSuccess() {
        val store = PairingAcceptanceStore({ PairingState() }, { true })
        val inv = invitation()
        assertThrows(IllegalStateException::class.java) { store.accept(inv, inv.token, now + 1) }
        assertThrows(IllegalStateException::class.java) { store.snapshot() }
    }

    @Test fun throwingWriteBlocksFollowingReads() {
        val store = PairingAcceptanceStore({ PairingState() }, { throw IllegalStateException("Synthetic write failure") })
        val inv = invitation()
        assertThrows(IllegalStateException::class.java) { store.accept(inv, inv.token, now + 1) }
        assertThrows(IllegalStateException::class.java) { store.snapshot() }
    }

    @Test fun corruptReadCannotBeReplacedWithAnEmptyStore() {
        val store = PairingAcceptanceStore({ throw IllegalArgumentException("Synthetic corruption") },
            { fail("Corrupt state must not be overwritten"); false })
        val inv = invitation()
        assertThrows(IllegalArgumentException::class.java) { store.accept(inv, inv.token, now + 1) }
    }

    @Test fun sameDeviceCannotBeDuplicatedOrSilentlyChangeItsKey() {
        var disk = PairingState()
        var commits = 0
        val store = PairingAcceptanceStore({ disk }, { disk = it; commits++; true })
        val inv = invitation()
        store.accept(inv, inv.token, now + 1)
        val duplicate = invitation()
        assertEquals("already paired", (store.accept(duplicate, duplicate.token, now + 2) as PairingResult.Refused).reason)
        val changed = invitation().copy(inviterPublicKeyB64 = DeviceIdentityCodec.generate().publicKeyB64)
        assertEquals("identity changed", (store.accept(changed, changed.token, now + 2) as PairingResult.Refused).reason)
        assertEquals(1, commits)
        assertEquals(identity.publicKeyB64, store.snapshot().peers.single().publicKeyB64)
    }

    @Test fun concurrentAcceptanceCreatesOnePeerAndOneCommit() {
        var disk = PairingState()
        var commits = 0
        val store = PairingAcceptanceStore({ disk }, { disk = it; commits++; true })
        val inv = invitation()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val attempts = (1..8).map { executor.submit<PairingResult> {
                check(start.await(5, TimeUnit.SECONDS))
                store.accept(inv, inv.token, now + 1)
            } }
            start.countDown()
            val outcomes = attempts.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, outcomes.count { it is PairingResult.Ok })
            assertEquals(7, outcomes.count { it is PairingResult.Refused })
            assertEquals(1, commits)
            assertEquals(1, store.snapshot().peers.size)
        } finally { executor.shutdownNow() }
    }

    @Test fun existingPeerAndReplayHistoryArePreserved() {
        var disk = PairingState()
        val store = PairingAcceptanceStore({ disk }, { disk = it; true })
        val first = invitation()
        store.accept(first, first.token, now + 1)
        val secondIdentity = DeviceIdentityCodec.generate()
        val next = Pairing.create(secondIdentity.deviceId, secondIdentity.publicKeyB64, "Other synthetic peer", now)
        val reopened = PairingAcceptanceStore({ disk }, { disk = it; true })
        reopened.accept(next, next.token, now + 2)
        assertEquals(2, disk.peers.size)
        assertEquals(setOf(first.id, next.id), disk.acceptedInvitationIds)
    }

    @Test fun incompleteLegacyPairingStateIsNotOverwritten() {
        val inv = invitation()
        val incomplete = PairingState(listOf(PairedPeer(identity.deviceId, identity.publicKeyB64,
            "Synthetic peer", inv.id, now)), emptySet())
        val store = PairingAcceptanceStore({ incomplete }, { fail("Incomplete state must be preserved"); false })
        assertThrows(IllegalStateException::class.java) { store.snapshot() }
    }
}
