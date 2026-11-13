package com.wifeassistant

import com.wifeassistant.data.crypto.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class PreKeyReplenishmentPolicyTest {
    private val weekSeconds = PreKeyReplenishmentPolicy.DEFAULT_SIGNED_KEY_ROTATION_INTERVAL_SECONDS

    // ---- Pure policy logic (no persistence/storage involved) ----

    @Test fun neverPublishedNeedsBothRegardlessOfTimeOrCount() {
        val policy = PreKeyReplenishmentPolicy()
        val state = PreKeyReplenishmentState(hasPublishedBundle = false,
            lastSignedKeyRotationEpochSec = 0, oneTimePreKeyCount = 50)
        val decision = policy.evaluate(state, nowEpochSec = 0)
        assertTrue(decision.needsSignedKeyRotation)
        assertTrue(decision.needsOneTimePreKeyReplenishment)
        assertTrue(decision.needsReplenishment)
    }

    @Test fun freshlyRotatedWithKeysInStockNeedsNothing() {
        val policy = PreKeyReplenishmentPolicy()
        val state = PreKeyReplenishmentState(hasPublishedBundle = true,
            lastSignedKeyRotationEpochSec = 1_000, oneTimePreKeyCount = 5)
        val decision = policy.evaluate(state, nowEpochSec = 1_000 + weekSeconds - 1)
        assertFalse(decision.needsSignedKeyRotation)
        assertFalse(decision.needsOneTimePreKeyReplenishment)
        assertFalse(decision.needsReplenishment)
    }

    @Test fun signedKeyRotationIsDueExactlyAtTheIntervalBoundary() {
        val policy = PreKeyReplenishmentPolicy(signedKeyRotationIntervalSeconds = 100)
        val state = PreKeyReplenishmentState(hasPublishedBundle = true,
            lastSignedKeyRotationEpochSec = 1_000, oneTimePreKeyCount = 10)
        assertFalse(policy.evaluate(state, nowEpochSec = 1_099).needsSignedKeyRotation)
        assertTrue(policy.evaluate(state, nowEpochSec = 1_100).needsSignedKeyRotation)
        assertTrue(policy.evaluate(state, nowEpochSec = 1_101).needsSignedKeyRotation)
        // One-time pre-key floor is independent of the elapsed-time check.
        assertFalse(policy.evaluate(state, nowEpochSec = 1_100).needsOneTimePreKeyReplenishment)
    }

    @Test fun oneTimePreKeyReplenishmentIsDueExactlyAtTheFloorBoundary() {
        val policy = PreKeyReplenishmentPolicy(oneTimePreKeyFloor = 3)
        val state = PreKeyReplenishmentState(hasPublishedBundle = true,
            lastSignedKeyRotationEpochSec = 1_000, oneTimePreKeyCount = 3)
        assertFalse(policy.evaluate(state, nowEpochSec = 1_000).needsOneTimePreKeyReplenishment)
        assertFalse(policy.evaluate(state.copy(oneTimePreKeyCount = 4), nowEpochSec = 1_000).needsOneTimePreKeyReplenishment)
        assertTrue(policy.evaluate(state.copy(oneTimePreKeyCount = 2), nowEpochSec = 1_000).needsOneTimePreKeyReplenishment)
        assertTrue(policy.evaluate(state.copy(oneTimePreKeyCount = 0), nowEpochSec = 1_000).needsOneTimePreKeyReplenishment)
    }

    @Test fun zeroFloorNeverRequiresOneTimeReplenishmentOnCountAlone() {
        val policy = PreKeyReplenishmentPolicy(oneTimePreKeyFloor = 0)
        val state = PreKeyReplenishmentState(hasPublishedBundle = true,
            lastSignedKeyRotationEpochSec = 1_000, oneTimePreKeyCount = 0)
        assertFalse(policy.evaluate(state, nowEpochSec = 1_000).needsOneTimePreKeyReplenishment)
    }

    @Test fun clockAtOrBeforeLastRotationIsNotYetDue() {
        val policy = PreKeyReplenishmentPolicy(signedKeyRotationIntervalSeconds = 100)
        val state = PreKeyReplenishmentState(hasPublishedBundle = true,
            lastSignedKeyRotationEpochSec = 1_000, oneTimePreKeyCount = 10)
        // Clock reported as equal to, or earlier than, the last rotation
        // (e.g. a misbehaving caller) must never be treated as "overdue".
        assertFalse(policy.evaluate(state, nowEpochSec = 1_000).needsSignedKeyRotation)
        assertFalse(policy.evaluate(state, nowEpochSec = 500).needsSignedKeyRotation)
    }

    @Test fun invalidConfigurationIsRejected() {
        assertTrue(runCatching { PreKeyReplenishmentPolicy(signedKeyRotationIntervalSeconds = 0) }.isFailure)
        assertTrue(runCatching { PreKeyReplenishmentPolicy(signedKeyRotationIntervalSeconds = -1) }.isFailure)
        assertTrue(runCatching { PreKeyReplenishmentPolicy(oneTimePreKeyFloor = -1) }.isFailure)
    }

    @Test fun negativeNowIsRejected() {
        val policy = PreKeyReplenishmentPolicy()
        val state = PreKeyReplenishmentState(hasPublishedBundle = true,
            lastSignedKeyRotationEpochSec = 0, oneTimePreKeyCount = 10)
        assertTrue(runCatching { policy.evaluate(state, nowEpochSec = -1) }.isFailure)
    }

    // ---- Wired through durable persistence + LibsignalCryptoProvider ----

    private class Disk {
        var value: String? = null
        fun open(owner: String = "B") = LibsignalCryptoProvider(SignalStatePersistence({ value }, { value = it }, owner))
    }

    @Test fun providerReportsBothDueBeforeAnyBundleExists() {
        val disk = Disk()
        val decision = disk.open().replenishmentDecision("B", nowEpochSec = 1_000)
        assertTrue(decision.needsSignedKeyRotation)
        assertTrue(decision.needsOneTimePreKeyReplenishment)
    }

    @Test fun providerStampsRotationTimeWhenABundleIsGenerated() {
        val disk = Disk()
        disk.open().nextPublishableBundle("B", nowEpochSec = 1_000)

        val justAfter = disk.open().replenishmentDecision("B", nowEpochSec = 1_050,
            policy = PreKeyReplenishmentPolicy(signedKeyRotationIntervalSeconds = 100))
        assertFalse(justAfter.needsSignedKeyRotation)
        assertFalse(justAfter.needsOneTimePreKeyReplenishment)

        val wellAfter = disk.open().replenishmentDecision("B", nowEpochSec = 1_000 + 100,
            policy = PreKeyReplenishmentPolicy(signedKeyRotationIntervalSeconds = 100))
        assertTrue(wellAfter.needsSignedKeyRotation)
    }

    @Test fun generatingAFreshBundleResetsTheRotationClock() {
        val disk = Disk()
        val policy = PreKeyReplenishmentPolicy(signedKeyRotationIntervalSeconds = 100)
        disk.open().nextPublishableBundle("B", nowEpochSec = 1_000)
        assertTrue(disk.open().replenishmentDecision("B", nowEpochSec = 1_200, policy = policy).needsSignedKeyRotation)

        disk.open().nextPublishableBundle("B", nowEpochSec = 1_200)
        assertFalse(disk.open().replenishmentDecision("B", nowEpochSec = 1_210, policy = policy).needsSignedKeyRotation)
    }

    @Test fun oneTimePreKeyCountReflectsLocallyStoredUnconsumedKeys() {
        val disk = Disk()
        val policy = PreKeyReplenishmentPolicy(oneTimePreKeyFloor = 1)
        val bobIdentity = DeviceIdentityCodec.generate().copy(deviceId = "B")
        val aliceIdentity = DeviceIdentityCodec.generate().copy(deviceId = "A")
        val alice = LibsignalCryptoProvider(LibsignalKeys.generateIdentity())
        val aliceBundle = alice.publishableBundle("A")
        val bundle = disk.open().nextPublishableBundle("B", nowEpochSec = 1_000)

        // Exactly one one-time pre-key stored locally and not yet consumed:
        // at the floor, so no replenishment is reported as due yet.
        assertFalse(disk.open().replenishmentDecision("B", nowEpochSec = 1_000, policy = policy)
            .needsOneTimePreKeyReplenishment)

        alice.trustPeer(bobIdentity.public, bundle, SignalPeerBinding.sign(bobIdentity, bundle))
        alice.establishSession("B", Json.encodeToString(SignalPreKeyBundleDto.serializer(), bundle).toByteArray())
        val bob = disk.open()
        bob.trustPeer(aliceIdentity.public, aliceBundle, SignalPeerBinding.sign(aliceIdentity, aliceBundle))
        // Decrypting the incoming PreKey message consumes Bob's one-time
        // pre-key locally, dropping the count below the floor of 1.
        bob.session("A").decrypt(alice.session("B").encrypt("hi".toByteArray()))

        assertTrue(disk.open().replenishmentDecision("B", nowEpochSec = 1_000, policy = policy)
            .needsOneTimePreKeyReplenishment)
    }
}
