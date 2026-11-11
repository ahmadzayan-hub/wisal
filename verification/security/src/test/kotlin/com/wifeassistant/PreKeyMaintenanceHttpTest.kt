package com.wifeassistant

import com.wifeassistant.data.crypto.*
import com.wifeassistant.data.pairing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Real libsignal native crypto, actual Android transport and actual Node API
 * handlers over loopback, with synthetic identities. No production service.
 */
class PreKeyMaintenanceHttpTest {
    private class RelayFixture(flag: String? = null) : AutoCloseable {
        private val root = System.getProperty("wisal.repository") ?: generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.exists(it.resolve("verification/security/RelayHarness.cjs")) }.toString()
        private val builder = ProcessBuilder("node", Path.of(root, "verification/security/RelayHarness.cjs").toString()).redirectErrorStream(true)
        private val child: Process
        val client: DirectRelayClient
        init {
            if (flag != null) builder.environment()[flag] = "1"
            child = builder.start()
            val ready = child.inputStream.bufferedReader().readLine()
            check(ready?.startsWith("READY ") == true)
            client = DirectRelayClient("http://127.0.0.1:" + ready.substringAfter("READY "))
        }
        override fun close() { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS) }
    }
    private class Disk(val owner: LocalIdentity) {
        var saved: String? = null
        var failAcceptance = false
        fun open() = LibsignalCryptoProvider(SignalStatePersistence({ saved }, {
            if (failAcceptance && it.contains("\"pendingPreKeyPublication\":null") &&
                !it.contains("\"acceptedPreKeyPublication\":null")) error("synthetic commit failure")
            saved = it
        }, owner.deviceId))
    }
    private fun now() = System.currentTimeMillis() / 1000

    @Test(timeout = 30_000) fun remoteClaimTriggersFreshIdsBeforeRecipientDecryptsAndOldCiphertextSurvives() = runBlocking {
        RelayFixture().use { fixture ->
            val relay = fixture.client
            val owner = DeviceIdentityCodec.generate(); val peer = DeviceIdentityCodec.generate()
            relay.registerDevice(owner).getOrThrow(); relay.registerDevice(peer).getOrThrow()
            val disk = Disk(owner); val bob = disk.open()
            val first = DirectPreKeyMaintenance(bob, relay).runOnce(owner).getOrThrow()
            assertEquals(PreKeyMaintenanceAction.PUBLISHED, first.action)
            assertTrue(first.oneTimePreKeyAvailable)
            val oldBundle = relay.claimPreKeys(peer, owner.deviceId, now()).getOrThrow()
            val alice = LibsignalCryptoProvider(LibsignalKeys.generateIdentity())
            val aliceBundle = alice.publishableBundle(peer.deviceId)
            bob.trustPeer(peer.public, aliceBundle, SignalPeerBinding.sign(peer, aliceBundle))
            alice.trustPeer(owner.public, oldBundle, SignalPeerBinding.sign(owner, oldBundle))
            alice.establishSession(owner.deviceId, Json.encodeToString(SignalPreKeyBundleDto.serializer(), oldBundle).toByteArray())
            val delayed = alice.session(owner.deviceId).encrypt("delayed before replenishment".toByteArray())
            // Reproduces why PR #20's LOCAL key count cannot measure relay stock.
            assertFalse(bob.replenishmentDecision(owner.deviceId).needsOneTimePreKeyReplenishment)
            val replacement = DirectPreKeyMaintenance(disk.open(), relay).runOnce(owner).getOrThrow()
            assertEquals(2, replacement.oneTimePreKeyId)
            assertEquals("delayed before replenishment", String(disk.open().session(peer.deviceId).decrypt(delayed)))
            assertEquals(PreKeyMaintenanceAction.CURRENT, DirectPreKeyMaintenance(disk.open(), relay).runOnce(owner).getOrThrow().action)
            assertTrue(relay.publishPreKeys(owner, oldBundle, now()).isFailure)
            assertEquals(2, relay.claimPreKeys(peer, owner.deviceId, now()).getOrThrow().oneTimePreKeyId)
            assertTrue(relay.claimPreKeys(peer, owner.deviceId, now()).isFailure)
            assertFalse(disk.open().mayClaimE2ee)
        }
    }

    @Test(timeout = 30_000) fun lostPublishResponseThenClaimReconcilesWithoutRevivingConsumedKey() = runBlocking {
        RelayFixture("WISAL_TEST_DROP_PUBLICATION_ONCE").use { fixture ->
            val relay = fixture.client; val owner = DeviceIdentityCodec.generate(); val peer = DeviceIdentityCodec.generate()
            relay.registerDevice(owner).getOrThrow(); relay.registerDevice(peer).getOrThrow()
            val disk = Disk(owner)
            assertTrue(DirectPreKeyMaintenance(disk.open(), relay).runOnce(owner).isFailure)
            val pending = disk.open().preKeyPublicationState(owner.deviceId).pending!!
            assertNull(disk.open().preKeyPublicationState(owner.deviceId).accepted)
            relay.claimPreKeys(peer, owner.deviceId, now()).getOrThrow()
            val recovered = DirectPreKeyMaintenance(disk.open(), relay).runOnce(owner).getOrThrow()
            assertEquals(PreKeyMaintenanceAction.RECONCILED, recovered.action)
            assertEquals(pending.bundle.oneTimePreKeyId, recovered.oneTimePreKeyId)
            assertFalse(recovered.oneTimePreKeyAvailable)
            assertTrue(relay.claimPreKeys(peer, owner.deviceId, now()).isFailure)
            assertEquals(2, DirectPreKeyMaintenance(disk.open(), relay).runOnce(owner).getOrThrow().oneTimePreKeyId)
        }
    }

    @Test(timeout = 30_000) fun unavailablePublicationRetriesExactDurableBundleAndFreshTimestamp() = runBlocking {
        RelayFixture("WISAL_TEST_REFUSE_PUBLICATION_ONCE").use { fixture ->
            val relay = fixture.client; val owner = DeviceIdentityCodec.generate()
            relay.registerDevice(owner).getOrThrow(); val disk = Disk(owner)
            assertTrue(DirectPreKeyMaintenance(disk.open(), relay).runOnce(owner).isFailure)
            val pending = disk.open().preKeyPublicationState(owner.deviceId).pending!!
            assertNull(relay.preKeyPublicationStatus(owner, now()).getOrThrow().bundleDigest)
            assertEquals(1, DirectPreKeyMaintenance(disk.open(), relay).runOnce(owner).getOrThrow().oneTimePreKeyId)
            assertEquals(pending, disk.open().preKeyPublicationState(owner.deviceId).accepted)
            assertEquals(pending.bundle.publicationDigest(), relay.preKeyPublicationStatus(owner, now()).getOrThrow().bundleDigest)
        }
    }

    @Test(timeout = 30_000) fun failedLocalAcceptanceCommitRequiresReopenAndStatusReconciliation() = runBlocking {
        RelayFixture().use { fixture ->
            val relay = fixture.client; val owner = DeviceIdentityCodec.generate()
            relay.registerDevice(owner).getOrThrow(); val disk = Disk(owner)
            val provider = disk.open(); disk.failAcceptance = true
            assertTrue(DirectPreKeyMaintenance(provider, relay).runOnce(owner).isFailure)
            disk.failAcceptance = false
            assertTrue(DirectPreKeyMaintenance(provider, relay).runOnce(owner).isFailure)
            val pending = disk.open().preKeyPublicationState(owner.deviceId).pending!!
            assertEquals(pending.bundle.publicationDigest(), relay.preKeyPublicationStatus(owner, now()).getOrThrow().bundleDigest)
            val result = DirectPreKeyMaintenance(disk.open(), relay).runOnce(owner).getOrThrow()
            assertEquals(PreKeyMaintenanceAction.RECONCILED, result.action)
            assertEquals(1, result.oneTimePreKeyId)
        }
    }

    @Test(timeout = 30_000) fun relayClockRefusalOccursBeforeNewPreKeysAndCorrectedClockCanRetry() = runBlocking {
        RelayFixture().use { fixture ->
            val relay = fixture.client; val owner = DeviceIdentityCodec.generate()
            relay.registerDevice(owner).getOrThrow(); val disk = Disk(owner)
            val skewed = DirectPreKeyMaintenance(disk.open(), relay, clock = { now() + 3_600 }).runOnce(owner)
            assertTrue(skewed.exceptionOrNull() is PreKeyClockException)
            assertNull(disk.open().preKeyPublicationState(owner.deviceId).pending)
            assertEquals(0, disk.open().preKeyPublicationState(owner.deviceId).lastSignedKeyRotationEpochSec)
            assertEquals(1, DirectPreKeyMaintenance(disk.open(), relay).runOnce(owner).getOrThrow().oneTimePreKeyId)
        }
    }

    @Test(timeout = 30_000) fun durablePendingSurvivesSeparateJvmExitBeforePublishing() {
        val directory = Files.createTempDirectory("WisalPreKeyRestart")
        try {
            for (phase in listOf("prepare", "resume")) {
                val child = ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-cp",
                    System.getProperty("wisal.childClasspath") ?: System.getProperty("java.class.path"),
                    "com.wifeassistant.PreKeyPublicationRestartHarness", phase, directory.toString())
                    .redirectErrorStream(true).start()
                val output = child.inputStream.bufferedReader().readText()
                assertEquals(output, 0, child.waitFor())
                assertTrue(output, output.contains("PASS $phase"))
            }
        } finally {
            Files.walk(directory).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
        }
    }
}

/** Plaintext FILE fixtures hold synthetic keys only. This does not test Android
 * Keystore, fsync semantics, backup restoration or hardware rollback resistance.
 */
object PreKeyPublicationRestartHarness {
    @JvmStatic fun main(args: Array<String>) {
        val directory = Path.of(args[1]); val snapshot = directory.resolve("SyntheticSnapshot.json")
        val provider = LibsignalCryptoProvider(SignalStatePersistence(
            { snapshot.takeIf { Files.exists(it) }?.let(Files::readString) },
            { Files.writeString(snapshot, it) }, "synthetic-owner"))
        val scope = "https://relay.example.test"
        if (args[0] == "prepare") {
            val pending = provider.preparePreKeyPublication("synthetic-owner", 1_800_000_000L, scope, "synthetic-public", null)
            Files.writeString(directory.resolve("ExpectedDigest.txt"), pending.bundle.publicationDigest())
        } else {
            val pending = provider.preKeyPublicationState("synthetic-owner").pending!!
            check(pending.bundle.publicationDigest() == Files.readString(directory.resolve("ExpectedDigest.txt")))
            check(provider.preparePreKeyPublication("synthetic-owner", 1_800_000_001L, scope, "synthetic-public", null) == pending)
            provider.acceptPreKeyPublication("synthetic-owner", pending)
            val next = provider.preparePreKeyPublication("synthetic-owner", 1_800_000_002L, scope, "synthetic-public", pending)
            check(next.bundle.oneTimePreKeyId == 2 && next.bundle.signedPreKeyId == 2 && next.bundle.kyberPreKeyId == 2)
            check(next.bundle.identityKeyB64 == pending.bundle.identityKeyB64)
        }
        println("PASS ${args[0]}: synthetic pending publication survived process lifecycle")
    }
}
