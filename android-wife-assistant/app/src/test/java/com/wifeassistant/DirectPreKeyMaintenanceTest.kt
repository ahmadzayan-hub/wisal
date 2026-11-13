package com.wifeassistant

import com.wifeassistant.data.crypto.*
import com.wifeassistant.data.pairing.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Native libsignal and the actual snapshot store with a controllable transport.
 * These synthetic fixtures do not exercise Android Keystore, devices or a live relay.
 */
class DirectPreKeyMaintenanceTest {
    private class Disk {
        @Volatile var value: String? = null
        var writes = 0
        var failStage = false
        var failAcceptance = false
        var persistThenFailAcceptance = false
        var dropStageWrite = false

        fun open(owner: String = "B") = LibsignalCryptoProvider(SignalStatePersistence({ value }, { encoded ->
            val state = Json.parseToJsonElement(encoded).jsonObject
            val staged = state["pendingPreKeyPublication"] != JsonNull
            val accepted = state["acceptedPreKeyPublication"] != JsonNull && !staged
            if (staged && failStage) throw IOException("synthetic private storage failure")
            if (accepted && failAcceptance) throw IOException("synthetic acceptance failure")
            if (!(staged && dropStageWrite)) { value = encoded; writes++ }
            if (accepted && persistThenFailAcceptance) throw IOException("synthetic ambiguous acceptance")
        }, owner))

        fun highestId() = Json.parseToJsonElement(value!!).jsonObject.getValue("highestPreKeyId").jsonPrimitive.int
    }

    private class Relay : PreKeyPublicationTransport {
        var scope = "https://isolated.invalid"
        var bundle: SignalPreKeyBundleDto? = null
        var available = false
        var publicationTime = 0L
        var statusCalls = 0
        var publishCalls = 0
        val attempts = mutableListOf<SignalPreKeyBundleDto>()
        var statusFailure: Exception? = null
        var publishFailureBefore: Exception? = null
        var publishFailureAfter: Exception? = null
        var beforePublish: ((SignalPreKeyBundleDto) -> Unit)? = null
        var afterPublish: (suspend () -> Unit)? = null
        var beforeStatus: (suspend (Int) -> Unit)? = null

        override fun publicationScope() = scope

        override suspend fun publishPreKeys(identity: DeviceSigningIdentity, bundle: SignalPreKeyBundleDto,
            nowEpochSec: Long): Result<Unit> = runCatching {
            publishCalls++
            attempts += bundle
            beforePublish?.invoke(bundle)
            publishFailureBefore?.let { throw it }
            val current = this.bundle
            if (current != null && bundle.oneTimePreKeyId <= current.oneTimePreKeyId) {
                check(available && bundle == current) { "one-time pre-key id must be higher" }
            } else {
                this.bundle = bundle
                available = true
                publicationTime = nowEpochSec
            }
            afterPublish?.invoke()
            publishFailureAfter?.let { throw it }
        }

        override suspend fun preKeyPublicationStatus(identity: DeviceSigningIdentity,
            nowEpochSec: Long): Result<RelayPreKeyStatus> = runCatching {
            statusCalls++
            beforeStatus?.invoke(statusCalls)
            statusFailure?.let { throw it }
            RelayPreKeyStatus(bundle?.publicationDigest(), bundle?.oneTimePreKeyId,
                bundle != null && available, if (bundle == null) null else publicationTime, nowEpochSec)
        }
    }

    private class Fixture {
        val disk = Disk()
        val provider = disk.open()
        val identity = DeviceIdentityCodec.generate().copy(deviceId = "B")
        val relay = Relay()
        var now = 1_800_000_000L
        fun coordinator(p: LibsignalCryptoProvider = provider, interval: Long = 100) =
            DirectPreKeyMaintenance(p, relay, { now }, interval)
        fun state() = disk.open().preKeyPublicationState("B")
    }

    @Test fun firstPublicationCommitsPrivateKeysAndIntentBeforeAnyPublishAndThenAcceptance() = runBlocking {
        val f = Fixture()
        f.relay.beforePublish = { bundle ->
            val state = f.state()
            assertEquals(bundle, state.pending!!.bundle)
            assertNull(state.accepted)
            val snapshot = Json.parseToJsonElement(f.disk.value!!).jsonObject
            assertTrue(snapshot.getValue("preKeys").jsonObject.containsKey(bundle.oneTimePreKeyId.toString()))
            assertTrue(snapshot.getValue("signedKeys").jsonObject.containsKey(bundle.signedPreKeyId.toString()))
            assertTrue(snapshot.getValue("kyberKeys").jsonObject.containsKey(bundle.kyberPreKeyId.toString()))
        }
        val result = f.coordinator().runOnce(f.identity).getOrThrow()
        assertEquals(PreKeyMaintenanceAction.PUBLISHED, result.action)
        assertEquals(1, result.oneTimePreKeyId)
        assertTrue(result.oneTimePreKeyAvailable)
        assertNull(f.state().pending)
        assertEquals(f.relay.bundle, f.state().accepted!!.bundle)
        assertEquals(1, f.relay.publishCalls)
        assertFalse(f.provider.mayClaimE2ee)
    }

    @Test fun preflightNetworkFailureCreatesNoPreKeysAndDoesNotPublish() = runBlocking {
        val f = Fixture()
        f.relay.statusFailure = IOException("synthetic disconnected")
        assertTrue(f.coordinator().runOnce(f.identity).isFailure)
        assertEquals(0, f.disk.highestId())
        assertNull(f.state().pending)
        assertNull(f.state().accepted)
        assertEquals(0, f.relay.publishCalls)
    }

    @Test fun failureBeforeRelayAcceptanceRetriesTheIdenticalDurableBundleAfterReopening() = runBlocking {
        val f = Fixture()
        f.relay.publishFailureBefore = IOException("synthetic connection failure")
        assertTrue(f.coordinator().runOnce(f.identity).isFailure)
        val pending = f.state().pending!!
        assertNull(f.relay.bundle)
        f.relay.publishFailureBefore = null
        f.now += 20
        val result = f.coordinator(f.disk.open()).runOnce(f.identity).getOrThrow()
        assertEquals(PreKeyMaintenanceAction.PUBLISHED, result.action)
        assertEquals(listOf(pending.bundle, pending.bundle), f.relay.attempts)
        assertEquals(1, f.disk.highestId())
        assertEquals(pending, f.state().accepted)
    }

    @Test fun lostPublishResponseReconcilesWithoutResendingOrAllocatingAnotherKey() = runBlocking {
        val f = Fixture()
        f.relay.publishFailureAfter = IOException("synthetic lost response")
        assertTrue(f.coordinator().runOnce(f.identity).isFailure)
        val pending = f.state().pending!!
        f.relay.publishFailureAfter = null
        val result = f.coordinator(f.disk.open()).runOnce(f.identity).getOrThrow()
        assertEquals(PreKeyMaintenanceAction.RECONCILED, result.action)
        assertEquals(1, f.relay.publishCalls)
        assertEquals(pending, f.state().accepted)
        assertEquals(1, f.disk.highestId())
    }

    @Test fun lostResponseThenClaimReconcilesConsumedReceiptAndOnlyNextCallReplenishes() = runBlocking {
        val f = Fixture()
        f.relay.publishFailureAfter = IOException("synthetic lost response")
        assertTrue(f.coordinator().runOnce(f.identity).isFailure)
        val old = f.state().pending!!.bundle
        f.relay.available = false // A peer claimed it, without any message reaching this device.
        f.relay.publishFailureAfter = null
        val reconciled = f.coordinator(f.disk.open()).runOnce(f.identity).getOrThrow()
        assertEquals(PreKeyMaintenanceAction.RECONCILED, reconciled.action)
        assertFalse(reconciled.oneTimePreKeyAvailable)
        assertEquals(1, f.relay.publishCalls)
        assertEquals(old, f.relay.bundle)
        val next = f.coordinator(f.disk.open()).runOnce(f.identity).getOrThrow()
        assertEquals(2, next.oneTimePreKeyId)
        assertEquals(2, f.relay.publishCalls)
        assertEquals(listOf(1, 2), f.relay.attempts.map { it.oneTimePreKeyId })
    }

    @Test fun failedStatusAfterHttpSuccessKeepsIntentForReconciliation() = runBlocking {
        val f = Fixture()
        f.relay.beforeStatus = { call -> if (call == 2) throw IOException("synthetic status response loss") }
        assertTrue(f.coordinator().runOnce(f.identity).isFailure)
        assertNotNull(f.state().pending)
        assertNull(f.state().accepted)
        val result = f.coordinator(f.disk.open()).runOnce(f.identity).getOrThrow()
        assertEquals(PreKeyMaintenanceAction.RECONCILED, result.action)
        assertEquals(1, f.relay.publishCalls)
    }

    @Test fun stageCommitFailurePreventsPublicationAndBlocksThatProvider() = runBlocking {
        val f = Fixture()
        f.disk.failStage = true
        val failed = f.coordinator().runOnce(f.identity)
        assertTrue(failed.isFailure)
        assertFalse(failed.exceptionOrNull().toString().contains("synthetic private"))
        assertEquals(0, f.relay.publishCalls)
        assertEquals(0, f.disk.highestId())
        f.disk.failStage = false
        val before = f.relay.statusCalls
        assertTrue(f.coordinator().runOnce(f.identity).isFailure)
        assertEquals(before, f.relay.statusCalls)
        assertEquals(1, f.coordinator(f.disk.open()).runOnce(f.identity).getOrThrow().oneTimePreKeyId)
    }

    @Test fun unverifiedStageReadbackNeverReleasesBundleToRelay() = runBlocking {
        val f = Fixture()
        f.disk.dropStageWrite = true
        assertTrue(f.coordinator().runOnce(f.identity).isFailure)
        assertEquals(0, f.disk.highestId())
        assertEquals(0, f.relay.publishCalls)
        f.disk.dropStageWrite = false
        assertTrue(f.coordinator().runOnce(f.identity).isFailure)
    }

    @Test fun failedAcceptanceCommitLeavesPendingAndReopenedCoordinatorReconciles() = runBlocking {
        val f = Fixture()
        f.disk.failAcceptance = true
        assertTrue(f.coordinator().runOnce(f.identity).isFailure)
        assertNotNull(f.state().pending)
        assertNull(f.state().accepted)
        f.disk.failAcceptance = false
        val calls = f.relay.statusCalls
        assertTrue(f.coordinator().runOnce(f.identity).isFailure)
        assertEquals(calls, f.relay.statusCalls)
        assertEquals(PreKeyMaintenanceAction.RECONCILED,
            f.coordinator(f.disk.open()).runOnce(f.identity).getOrThrow().action)
        assertEquals(1, f.relay.publishCalls)
        assertEquals(1, f.disk.highestId())
    }

    @Test fun ambiguousAcceptanceThatReachedDiskIsNotRepublishedAfterRestart() = runBlocking {
        val f = Fixture()
        f.disk.persistThenFailAcceptance = true
        assertTrue(f.coordinator().runOnce(f.identity).isFailure)
        assertNull(f.state().pending)
        assertNotNull(f.state().accepted)
        f.disk.persistThenFailAcceptance = false
        assertTrue(f.coordinator().runOnce(f.identity).isFailure)
        assertEquals(PreKeyMaintenanceAction.CURRENT,
            f.coordinator(f.disk.open()).runOnce(f.identity).getOrThrow().action)
        assertEquals(1, f.relay.publishCalls)
    }

    @Test fun concurrentReopenedCoordinatorsPublishOnceAndAllObserveSameAcceptedKey() = runBlocking {
        val f = Fixture()
        val results = coroutineScope {
            (1..8).map { async(Dispatchers.Default) {
                f.coordinator(f.disk.open()).runOnce(f.identity).getOrThrow()
            } }.awaitAll()
        }
        assertEquals(1, results.count { it.action == PreKeyMaintenanceAction.PUBLISHED })
        assertEquals(7, results.count { it.action == PreKeyMaintenanceAction.CURRENT })
        assertEquals(setOf(1), results.map { it.oneTimePreKeyId }.toSet())
        assertEquals(1, f.relay.publishCalls)
        assertEquals(1, f.disk.highestId())
    }

    @Test fun aPendingBundleCannotMoveToAnotherRelayOrPublisherKey() = runBlocking {
        val f = Fixture()
        f.relay.publishFailureBefore = IOException("synthetic offline")
        assertTrue(f.coordinator().runOnce(f.identity).isFailure)
        val stable = f.disk.value
        val calls = f.relay.statusCalls
        f.relay.scope = "https://another.invalid"
        assertTrue(f.coordinator(f.disk.open()).runOnce(f.identity).isFailure)
        assertEquals(calls, f.relay.statusCalls)
        f.relay.scope = "https://isolated.invalid"
        val changed = DeviceIdentityCodec.generate().copy(deviceId = "B")
        assertTrue(f.coordinator(f.disk.open()).runOnce(changed).isFailure)
        assertEquals(calls, f.relay.statusCalls)
        assertEquals(stable, f.disk.value)
    }

    @Test fun wrongOwnerAndEphemeralProviderFailBeforeAnyRelayRequest() = runBlocking {
        val f = Fixture()
        assertTrue(f.coordinator().runOnce(f.identity.copy(deviceId = "A")).isFailure)
        assertTrue(f.coordinator(LibsignalCryptoProvider(LibsignalKeys.generateIdentity()))
            .runOnce(f.identity).isFailure)
        assertEquals(0, f.relay.statusCalls)
        assertEquals(0, f.relay.publishCalls)
        assertNull(f.disk.value)
    }

    @Test fun backwardClockPausesBeforeNetworkAndPreservesPendingIntent() = runBlocking {
        val f = Fixture()
        f.relay.publishFailureBefore = IOException("synthetic offline")
        assertTrue(f.coordinator().runOnce(f.identity).isFailure)
        val pending = f.state().pending
        val calls = f.relay.statusCalls
        f.now--
        assertTrue(f.coordinator(f.disk.open()).runOnce(f.identity).exceptionOrNull() is PreKeyClockException)
        assertEquals(calls, f.relay.statusCalls)
        assertEquals(pending, f.state().pending)
        assertEquals(1, f.disk.highestId())
    }

    @Test fun relayClockRefusalAndUnsafeClockDoNotGeneratePreKeys() = runBlocking {
        val f = Fixture()
        f.relay.statusFailure = RelayRequestException(400, "stale timestamp")
        assertTrue(f.coordinator().runOnce(f.identity).exceptionOrNull() is PreKeyClockException)
        assertEquals(0, f.disk.highestId())
        f.now = Long.MAX_VALUE
        val calls = f.relay.statusCalls
        assertTrue(f.coordinator(f.disk.open()).runOnce(f.identity).exceptionOrNull() is PreKeyClockException)
        assertEquals(calls, f.relay.statusCalls)
        assertEquals(0, f.relay.publishCalls)
    }

    @Test fun forwardClockJumpAfterPreflightCannotPoisonPersistedRotationTime() = runBlocking {
        val f = Fixture()
        f.relay.beforeStatus = { if (it == 1) f.now += 86_400 }
        assertTrue(f.coordinator().runOnce(f.identity).exceptionOrNull() is PreKeyClockException)
        assertEquals(0, f.disk.highestId())
        assertEquals(0, f.relay.publishCalls)
        assertNull(f.state().pending)
        f.now -= 86_400
        assertEquals(1, f.coordinator().runOnce(f.identity).getOrThrow().oneTimePreKeyId)
    }

    @Test fun clockFailureAfterRemotePublicationRetainsIntentAndReportsUncertainty() = runBlocking {
        val f = Fixture()
        f.relay.afterPublish = { f.now += 86_400 }
        val failed = f.coordinator().runOnce(f.identity)
        assertTrue(failed.exceptionOrNull() is PreKeyClockException)
        assertFalse(failed.exceptionOrNull()!!.message!!.contains("no keys were replaced"))
        val pending = f.state().pending!!
        assertEquals(pending.bundle, f.relay.bundle)
        assertNull(f.state().accepted)
        f.now -= 86_400
        f.relay.afterPublish = null
        assertEquals(PreKeyMaintenanceAction.RECONCILED, f.coordinator(f.disk.open()).runOnce(f.identity).getOrThrow().action)
        assertEquals(1, f.relay.publishCalls)
    }

    @Test fun clockMovingBackwardAfterStagingKeepsBundleWithoutPublishing() = runBlocking {
        val f = Fixture()
        val coordinator = DirectPreKeyMaintenance(f.provider, f.relay, {
            val staged = f.disk.value?.let {
                Json.parseToJsonElement(it).jsonObject["pendingPreKeyPublication"] != JsonNull
            } == true
            if (staged) f.now - 1 else f.now
        }, 100)
        assertTrue(coordinator.runOnce(f.identity).exceptionOrNull() is PreKeyClockException)
        assertNotNull(f.state().pending)
        assertEquals(1, f.disk.highestId())
        assertEquals(0, f.relay.publishCalls)
    }

    @Test fun cancellationAfterRelayAcceptanceLeavesDurableIntentAndReleasesMutex() = runBlocking {
        val f = Fixture()
        val published = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        f.relay.afterPublish = { published.complete(Unit); hold.await() }
        val task = launch { f.coordinator().runOnce(f.identity) }
        withTimeout(10_000) { published.await() }
        task.cancelAndJoin()
        assertNotNull(f.state().pending)
        assertNull(f.state().accepted)
        f.relay.afterPublish = null
        val result = withTimeout(10_000) { f.coordinator(f.disk.open()).runOnce(f.identity).getOrThrow() }
        assertEquals(PreKeyMaintenanceAction.RECONCILED, result.action)
        assertEquals(1, f.relay.publishCalls)
    }

    @Test fun relayConsumptionReplenishesEvenWhileLocalPrivateKeyCountRemainsOne() = runBlocking {
        val f = Fixture()
        f.coordinator().runOnce(f.identity).getOrThrow()
        f.relay.available = false
        assertFalse(f.provider.replenishmentDecision("B", f.now).needsOneTimePreKeyReplenishment)
        val result = f.coordinator().runOnce(f.identity).getOrThrow()
        assertEquals(2, result.oneTimePreKeyId)
        val snapshot = Json.parseToJsonElement(f.disk.value!!).jsonObject
        assertEquals(setOf("1", "2"), snapshot.getValue("preKeys").jsonObject.keys)
    }

    @Test fun currentAcceptedBundleRotatesExactlyAtConfiguredAgeWithoutPruningOldKeys() = runBlocking {
        val f = Fixture()
        f.coordinator().runOnce(f.identity).getOrThrow()
        f.now += 99
        assertEquals(PreKeyMaintenanceAction.CURRENT, f.coordinator().runOnce(f.identity).getOrThrow().action)
        f.now++
        assertEquals(2, f.coordinator().runOnce(f.identity).getOrThrow().oneTimePreKeyId)
        val snapshot = Json.parseToJsonElement(f.disk.value!!).jsonObject
        for (key in listOf("preKeys", "signedKeys", "kyberKeys")) {
            assertEquals(setOf("1", "2"), snapshot.getValue(key).jsonObject.keys)
        }
    }

    @Test fun unknownLegacyRemoteBundleIsNotAdoptedOrOverwritten() = runBlocking {
        val f = Fixture()
        val legacy = f.provider.nextPublishableBundle("B", f.now)
        f.relay.bundle = legacy
        f.relay.available = true
        f.relay.publicationTime = f.now
        val stable = f.disk.value
        assertTrue(f.coordinator().runOnce(f.identity).exceptionOrNull() is PreKeyPublicationConflictException)
        assertEquals(stable, f.disk.value)
        assertEquals(0, f.relay.publishCalls)
        assertNull(f.state().accepted)
    }

    @Test fun missingOrMismatchedAcceptedRelayStateCannotTriggerBlindReplacement() = runBlocking {
        val f = Fixture()
        f.coordinator().runOnce(f.identity).getOrThrow()
        val accepted = f.relay.bundle!!
        val stable = f.disk.value
        f.relay.bundle = null
        f.relay.available = false
        assertTrue(f.coordinator().runOnce(f.identity).exceptionOrNull() is PreKeyPublicationConflictException)
        f.relay.bundle = accepted.copy(signedPreKeyId = accepted.signedPreKeyId + 1)
        assertTrue(f.coordinator().runOnce(f.identity).exceptionOrNull() is PreKeyPublicationConflictException)
        assertEquals(1, f.relay.publishCalls)
        assertEquals(stable, f.disk.value)
    }

    @Test fun unknownRemoteStateDoesNotCausePendingBundleResendOrAcknowledgement() = runBlocking {
        val f = Fixture()
        f.relay.publishFailureBefore = IOException("synthetic offline")
        assertTrue(f.coordinator().runOnce(f.identity).isFailure)
        val pending = f.state().pending!!
        f.relay.bundle = pending.bundle.copy(oneTimePreKeyId = pending.bundle.oneTimePreKeyId + 10)
        f.relay.available = true
        f.relay.publicationTime = f.now
        f.relay.publishFailureBefore = null
        assertTrue(f.coordinator(f.disk.open()).runOnce(f.identity).exceptionOrNull() is PreKeyPublicationConflictException)
        assertEquals(pending, f.state().pending)
        assertNull(f.state().accepted)
        assertEquals(1, f.relay.publishCalls)
        assertEquals(1, f.disk.highestId())
    }

    @Test fun knownPriorAcceptedPublicationPermitsResumingNewPendingPublication() = runBlocking {
        val f = Fixture()
        f.coordinator().runOnce(f.identity).getOrThrow()
        f.relay.available = false
        f.relay.publishFailureBefore = IOException("synthetic offline on second key")
        assertTrue(f.coordinator().runOnce(f.identity).isFailure)
        assertEquals(1, f.relay.bundle!!.oneTimePreKeyId)
        assertEquals(2, f.state().pending!!.bundle.oneTimePreKeyId)
        f.relay.publishFailureBefore = null
        assertEquals(2, f.coordinator(f.disk.open()).runOnce(f.identity).getOrThrow().oneTimePreKeyId)
        assertEquals(listOf(1, 2, 2), f.relay.attempts.map { it.oneTimePreKeyId })
        assertEquals(2, f.disk.highestId())
    }

    @Test fun retainedOldPrivateMaterialDecryptsDelayedMessageAfterRelayReplenishment() = runBlocking {
        val f = Fixture()
        f.coordinator().runOnce(f.identity).getOrThrow()
        val old = f.relay.bundle!!
        val aliceIdentity = DeviceIdentityCodec.generate().copy(deviceId = "A")
        val alice = LibsignalCryptoProvider(LibsignalKeys.generateIdentity())
        val aliceBundle = alice.publishableBundle("A")
        alice.trustPeer(f.identity.public, old, SignalPeerBinding.sign(f.identity, old))
        alice.establishSession("B", Json.encodeToString(SignalPreKeyBundleDto.serializer(), old).toByteArray())
        val delayed = alice.session("B").encrypt("synthetic delayed message".toByteArray())
        f.relay.available = false
        f.coordinator().runOnce(f.identity).getOrThrow()
        val reopened = f.disk.open()
        reopened.trustPeer(aliceIdentity.public, aliceBundle, SignalPeerBinding.sign(aliceIdentity, aliceBundle))
        assertEquals("synthetic delayed message", String(reopened.session("A").decrypt(delayed)))
        f.relay.available = false
        assertEquals(3, f.coordinator(f.disk.open()).runOnce(f.identity).getOrThrow().oneTimePreKeyId)
        assertEquals(listOf(1, 2, 3), f.relay.attempts.map { it.oneTimePreKeyId })
    }

    @Test fun supersededTripletSurvivesUntilDeadlineThenRetiresAcrossRestartWithoutReusingIds() = runBlocking {
        val f = Fixture()
        f.coordinator(interval = Long.MAX_VALUE).runOnce(f.identity).getOrThrow()
        f.relay.available = false
        f.coordinator(interval = Long.MAX_VALUE).runOnce(f.identity).getOrThrow()
        val deadline = f.now + SUPERSEDED_PRE_KEY_RETENTION_SECONDS

        f.now = deadline - 1
        assertEquals(PreKeyMaintenanceAction.CURRENT, f.coordinator(f.disk.open(), Long.MAX_VALUE)
            .runOnce(f.identity).getOrThrow().action)
        val before = Json.parseToJsonElement(f.disk.value!!).jsonObject
        for (key in listOf("preKeys", "signedKeys", "kyberKeys")) {
            assertEquals(setOf("1", "2"), before.getValue(key).jsonObject.keys)
        }

        f.now = deadline
        assertEquals(PreKeyMaintenanceAction.CURRENT, f.coordinator(f.disk.open(), Long.MAX_VALUE)
            .runOnce(f.identity).getOrThrow().action)
        val after = Json.parseToJsonElement(f.disk.value!!).jsonObject
        for (key in listOf("preKeys", "signedKeys", "kyberKeys")) {
            assertEquals(setOf("2"), after.getValue(key).jsonObject.keys)
        }
        f.relay.available = false
        assertEquals(3, f.coordinator(f.disk.open()).runOnce(f.identity).getOrThrow().oneTimePreKeyId)
    }
}
