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

class SignalProcessRestartTest {
    @Test(timeout = 30_000) fun sessionDeliveryAndReplayStateSurviveSeparateJvmProcesses() {
        val dir = Files.createTempDirectory("WisalSyntheticSignal")
        try {
            for (phase in listOf("prepare", "resume", "recoverUi")) {
                val child = ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-cp",
                    System.getProperty("wisal.childClasspath") ?: System.getProperty("java.class.path"), "com.wifeassistant.SignalRestartHarness", phase, dir.toString())
                    .redirectErrorStream(true).start()
                val output = child.inputStream.bufferedReader().readText()
                assertEquals(output, 0, child.waitFor())
                assertTrue(output, output.contains("PASS $phase"))
            }
        } finally {
            // Exact test-owned directory; contains synthetic keys only.
            Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
        }
    }
}

/** File snapshots here contain synthetic test keys only. Android uses SecureStore,
 * never this plaintext test fixture. Separate subprocesses prove JVM lifecycle, not Keystore.
 */
object SignalRestartHarness {
    @JvmStatic fun main(args: Array<String>) {
        val dir = Path.of(args[1])
        fun file(name: String) = dir.resolve(name)
        fun state(name: String) = SignalStatePersistence(
            { file(name).takeIf { Files.exists(it) }?.let { Files.readString(it) } },
            { Files.writeString(file(name), it) },
        )
        fun open(name: String) = LibsignalCryptoProvider(state(name))
        val a = open("A.json"); val b = open("B.json")
        if (args[0] == "prepare") {
            val ab = a.publishableBundle("A"); val bb = b.publishableBundle("B")
            trust(a, signing("B"), bb); trust(b, signing("A"), ab)
            a.establishSession("B", bundleBytes(bb))
            check(String(b.session("A").decrypt(a.session("B").encrypt("initial".toByteArray()))) == "initial")
            check(String(a.session("B").decrypt(b.session("A").encrypt("reply".toByteArray()))) == "reply")
            val queued = SignalMessageQueue(state("A.json")).prepare("queued", "B", "after restart".toByteArray(), 1_800_000_000L, 1_800_000_600L)
            Files.write(file("Pending.bin"), java.util.Base64.getDecoder().decode(queued.ciphertextB64))
            for (name in listOf("A", "B")) {
                val identity = Json.parseToJsonElement(Files.readString(file("$name.json"))).jsonObject.getValue("identity").jsonPrimitive.content
                Files.writeString(file("$name.identity"), identity)
            }
        } else if (args[0] == "resume") {
            check(a.hasSession("B") && b.hasSession("A"))
            val pending = Files.readAllBytes(file("Pending.bin"))
            val received = SignalMessageQueue(state("B.json")).accept("relay-pending", "A", pending)
            check(String(java.util.Base64.getDecoder().decode(received.plaintextB64)) == "after restart")
            check(String(a.session("B").decrypt(b.session("A").encrypt("resumed".toByteArray()))) == "resumed")
            check(runCatching { open("B.json").session("A").decrypt(pending) }.isFailure)
            // Highest ids must survive consumption/recreation too.
            check(runCatching { open("B.json").publishableBundle("B", 2, 2, 1) }.isFailure)
            for (name in listOf("A", "B")) {
                val identity = Json.parseToJsonElement(Files.readString(file("$name.json"))).jsonObject.getValue("identity").jsonPrimitive.content
                check(identity == Files.readString(file("$name.identity")))
            }
        } else {
            val queue = SignalMessageQueue(state("B.json"))
            check(queue.inbox().size == 1)
            check(String(java.util.Base64.getDecoder().decode(queue.inbox().single().plaintextB64)) == "after restart")
            check(queue.pendingAcknowledgements() == listOf("relay-pending"))
            val same = queue.accept("retry-envelope", "A", Files.readAllBytes(file("Pending.bin")))
            check(same.id == queue.inbox().single().id && queue.inbox().size == 1)
            check(SignalMessageQueue(state("A.json")).pendingOutbox().single().id == "queued")
        }
        println("PASS ${args[0]}: synthetic Signal process lifecycle")
    }
}
