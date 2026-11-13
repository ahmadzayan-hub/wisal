package com.wifeassistant

import com.wifeassistant.data.crypto.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PreKeyPublicationPersistenceTest {
    private val owner = DeviceIdentityCodec.generate()
    private val scope = "https://relay.example.test"
    private class Disk {
        var text: String? = null
        var writes = 0
        var failure = ""
        fun open(owner: String) = LibsignalCryptoProvider(SignalStatePersistence({ text }, {
            writes++
            if (failure == "before") error("synthetic private disk detail")
            if (failure != "no-op") text = it
            if (failure == "after") error("synthetic private disk detail")
        }, owner))
    }
    private fun prepare(provider: LibsignalCryptoProvider, accepted: PreparedPreKeyPublication? = null, time: Long = 1_800_000_000L) =
        provider.preparePreKeyPublication(owner.deviceId, time, scope, owner.publicKeyB64, accepted)

    @Test fun keysCountersAndPendingIntentShareOneVerifiedWriteAndSurviveReopen() {
        val disk = Disk()
        val pending = prepare(disk.open(owner.deviceId))
        assertEquals(1, disk.writes)
        val reopened = disk.open(owner.deviceId).preKeyPublicationState(owner.deviceId)
        assertEquals(pending, reopened.pending)
        assertNull(reopened.accepted)
        assertTrue(reopened.pendingOneTimeKeyPresent)
        val snapshot = Json.parseToJsonElement(disk.text!!).jsonObject
        for (field in listOf("preKeys", "signedKeys", "kyberKeys")) assertEquals(1, snapshot.getValue(field).jsonObject.size)
        for (field in listOf("highestPreKeyId", "highestSignedId", "highestKyberId")) assertEquals(1, snapshot.getValue(field).jsonPrimitive.int)
        assertFalse(disk.open(owner.deviceId).mayClaimE2ee)
    }

    @Test fun retriesReturnExactPersistedBundleWithoutGeneratingOrWritingAgain() {
        val disk = Disk()
        val first = prepare(disk.open(owner.deviceId))
        repeat(4) { assertEquals(first, prepare(disk.open(owner.deviceId), time = 1_800_000_100L + it)) }
        assertEquals(1, disk.writes)
        assertTrue(runCatching { disk.open(owner.deviceId).nextPublishableBundle(owner.deviceId) }.isFailure)
    }

    @Test fun acceptedJournalAndNewPendingUseIndependentDurableHighWaterMarks() {
        val disk = Disk()
        val provider = disk.open(owner.deviceId)
        provider.publishableBundle(owner.deviceId, 7, 11, 19)
        val first = prepare(provider)
        assertEquals(Triple(8, 12, 20), Triple(first.bundle.signedPreKeyId, first.bundle.kyberPreKeyId, first.bundle.oneTimePreKeyId))
        provider.acceptPreKeyPublication(owner.deviceId, first)
        val second = prepare(disk.open(owner.deviceId), first, 1_800_000_100L)
        assertEquals(21, second.bundle.oneTimePreKeyId)
        assertEquals(first, disk.open(owner.deviceId).preKeyPublicationState(owner.deviceId).accepted)
        assertEquals(second, disk.open(owner.deviceId).preKeyPublicationState(owner.deviceId).pending)
    }

    @Test fun beforeWriteAndSilentNoOpFailuresReleaseNoBundleAndBlockSameProvider() {
        for (mode in listOf("before", "no-op")) {
            val disk = Disk(); val provider = disk.open(owner.deviceId)
            disk.failure = mode
            val failure = runCatching { prepare(provider) }.exceptionOrNull()!!
            assertFalse(failure.toString().contains("private disk detail"))
            assertNull(failure.cause)
            assertNull(disk.text)
            disk.failure = ""
            assertTrue(runCatching { prepare(provider) }.isFailure)
            assertEquals(1, prepare(disk.open(owner.deviceId)).bundle.oneTimePreKeyId)
        }
    }

    @Test fun ambiguousWritePreservesExactPendingOnRestartWithoutReusingAnId() {
        val disk = Disk(); val provider = disk.open(owner.deviceId)
        disk.failure = "after"
        assertTrue(runCatching { prepare(provider) }.isFailure)
        disk.failure = ""
        assertTrue(runCatching { prepare(provider) }.isFailure)
        val recovered = disk.open(owner.deviceId)
        val pending = recovered.preKeyPublicationState(owner.deviceId).pending!!
        assertEquals(pending, prepare(recovered))
        recovered.acceptPreKeyPublication(owner.deviceId, pending)
        assertEquals(2, prepare(recovered, pending, 1_800_000_001L).bundle.oneTimePreKeyId)
    }

    @Test fun acknowledgementWriteFailureDoesNotPretendAcceptance() {
        val disk = Disk(); val provider = disk.open(owner.deviceId)
        val pending = prepare(provider)
        val stable = disk.text
        disk.failure = "before"
        assertTrue(runCatching { provider.acceptPreKeyPublication(owner.deviceId, pending) }.isFailure)
        assertEquals(stable, disk.text)
        disk.failure = ""
        assertTrue(runCatching { provider.acceptPreKeyPublication(owner.deviceId, pending) }.isFailure)
        val reopened = disk.open(owner.deviceId)
        assertEquals(pending, reopened.preKeyPublicationState(owner.deviceId).pending)
        reopened.acceptPreKeyPublication(owner.deviceId, pending)
        assertNull(reopened.preKeyPublicationState(owner.deviceId).pending)
        assertEquals(pending, reopened.preKeyPublicationState(owner.deviceId).accepted)
    }

    @Test fun mismatchedReceiptScopeOwnerAndEphemeralProviderCannotChangeJournal() {
        val disk = Disk(); val provider = disk.open(owner.deviceId)
        val pending = prepare(provider)
        val stable = disk.text
        assertTrue(runCatching { provider.acceptPreKeyPublication(owner.deviceId, pending.copy(relayScope = "https://different.test")) }.isFailure)
        assertTrue(runCatching { provider.preparePreKeyPublication(owner.deviceId, 1_800_000_001L, "https://different.test", owner.publicKeyB64, null) }.isFailure)
        assertTrue(runCatching { provider.preKeyPublicationState("wrong") }.isFailure)
        assertEquals(stable, disk.text)
        assertTrue(runCatching { prepare(LibsignalCryptoProvider(LibsignalKeys.generateIdentity())) }.isFailure)
    }

    @Test fun corruptedPublicIntentCannotOutliveItsPrivateKeyOrSignature() {
        for (field in listOf("oneTimePreKeyPublicB64", "signedPreKeyPublicB64", "signedPreKeySignatureB64", "kyberPreKeyPublicB64", "kyberPreKeySignatureB64", "identityKeyB64")) {
            val disk = Disk(); prepare(disk.open(owner.deviceId))
            val root = Json.parseToJsonElement(disk.text!!).jsonObject
            val intent = root.getValue("pendingPreKeyPublication").jsonObject
            val bundle = intent.getValue("bundle").jsonObject
            disk.text = JsonObject(root + ("pendingPreKeyPublication" to JsonObject(intent +
                ("bundle" to JsonObject(bundle + (field to JsonPrimitive("AQ=="))))))).toString()
            val corrupted = disk.text
            assertTrue(field, runCatching { disk.open(owner.deviceId).preKeyPublicationState(owner.deviceId) }.isFailure)
            assertEquals(corrupted, disk.text)
        }
    }

    @Test fun exhaustedCountersAndClockRollbackNeverPartiallyStage() {
        for (ids in listOf(Triple(Int.MAX_VALUE, 1, 1), Triple(1, Int.MAX_VALUE, 1), Triple(1, 1, Int.MAX_VALUE))) {
            val disk = Disk(); val provider = disk.open(owner.deviceId)
            provider.publishableBundle(owner.deviceId, ids.first, ids.second, ids.third)
            val stable = disk.text
            assertTrue(runCatching { prepare(provider) }.isFailure)
            assertEquals(stable, disk.text)
        }
        val policy = PreKeyReplenishmentPolicy()
        assertTrue(policy.evaluate(PreKeyReplenishmentState(true, 1_800_000_000L, 1), 1_700_000_000L).clockAnomaly)
    }
}
