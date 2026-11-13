package com.wifeassistant

import com.wifeassistant.data.crypto.DeviceIdentityCodec
import com.wifeassistant.data.pairing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.util.Base64
import java.util.concurrent.TimeUnit

class PreKeyPublicationTransportTest {
    private lateinit var server: MockWebServer
    private val identity = DeviceIdentityCodec.generate()
    private val now = 1_800_000_000L
    private fun client() = DirectRelayClient(server.url("/").newBuilder().host("127.0.0.1").build().toString())
    @Before fun setUp() { server = MockWebServer(); server.start(InetAddress.getByName("127.0.0.1"), 0) }
    @After fun tearDown() { server.shutdown() }

    private fun respond(change: (MutableMap<String, JsonElement>) -> Unit = {}) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = Json.parseToJsonElement(request.body.clone().readUtf8()).jsonObject
                val fields = mutableMapOf<String, JsonElement>(
                    "ok" to JsonPrimitive(true), "deviceId" to JsonPrimitive(identity.deviceId),
                    "requestNonce" to body.getValue("requestNonce"), "serverTimeEpochSec" to JsonPrimitive(now),
                    "bundleDigest" to JsonPrimitive("a".repeat(64)), "oneTimePreKeyId" to JsonPrimitive(1),
                    "oneTimePreKeyAvailable" to JsonPrimitive(true), "publishedAtEpochSec" to JsonPrimitive(now - 1))
                change(fields)
                return MockResponse().setBody(JsonObject(fields).toString())
            }
        }
    }

    @Test fun signedStatusBindsOwnDeviceTimestampAndFreshNonceWithoutPrivateMaterial() = runBlocking {
        respond()
        assertTrue(client().preKeyPublicationStatus(identity, now).getOrThrow().oneTimePreKeyAvailable)
        val first = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("POST", first.method); assertEquals("/api/prekeys-status", first.path)
        val body = first.body.readUtf8()
        val payload = Json.parseToJsonElement(body).jsonObject
        val nonce = payload.getValue("requestNonce").jsonPrimitive.content
        assertTrue(Regex("^[0-9a-f]{32}$").matches(nonce))
        val proof = "prekeys-status:${identity.deviceId}:$now:$nonce"
        assertEquals(proof, DirectRelayProofs.preKeyPublicationStatus(identity.deviceId, now, nonce))
        assertTrue(DeviceIdentityCodec.verify(identity.publicKeyB64, proof.toByteArray(),
            Base64.getDecoder().decode(payload.getValue("signatureB64").jsonPrimitive.content)))
        assertFalse(body.contains(identity.privateKeyB64))
        client().preKeyPublicationStatus(identity, now).getOrThrow()
        val again = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertNotEquals(nonce, again.getValue("requestNonce").jsonPrimitive.content)
    }

    @Test fun emptyAndConsumedStatusesAreNonConsumingExplicitObservations() = runBlocking {
        respond {
            it["bundleDigest"] = JsonNull; it["oneTimePreKeyId"] = JsonNull
            it["publishedAtEpochSec"] = JsonNull; it["oneTimePreKeyAvailable"] = JsonPrimitive(false)
        }
        val empty = client().preKeyPublicationStatus(identity, now).getOrThrow()
        assertNull(empty.bundleDigest); assertFalse(empty.oneTimePreKeyAvailable)
        respond { it["oneTimePreKeyAvailable"] = JsonPrimitive(false) }
        val consumed = client().preKeyPublicationStatus(identity, now).getOrThrow()
        assertEquals("a".repeat(64), consumed.bundleDigest); assertFalse(consumed.oneTimePreKeyAvailable)
    }

    @Test fun rejectsWrongDeviceNonceMalformedHashAndInconsistentStatus() = runBlocking {
        val mutations: List<(MutableMap<String, JsonElement>) -> Unit> = listOf(
            { it["deviceId"] = JsonPrimitive("other") },
            { it["requestNonce"] = JsonPrimitive("0".repeat(32)) },
            { it["bundleDigest"] = JsonPrimitive("bad digest") },
            { it["bundleDigest"] = JsonNull },
            { it["oneTimePreKeyId"] = JsonPrimitive(0) },
            { it["publishedAtEpochSec"] = JsonPrimitive(now + 1) },
            { it["publishedAtEpochSec"] = JsonNull },
            { it.remove("bundleDigest") },
            { it.remove("oneTimePreKeyAvailable") },
            { it.remove("requestNonce") },
        )
        for (mutation in mutations) {
            respond(mutation)
            assertTrue(client().preKeyPublicationStatus(identity, now).isFailure)
        }
        assertEquals(mutations.size, server.requestCount)
    }

    @Test fun staleRequestAndInconsistentServerClockDoNotClaimSuccess() = runBlocking {
        respond { it["serverTimeEpochSec"] = JsonPrimitive(now + 301) }
        assertTrue(client().preKeyPublicationStatus(identity, now).exceptionOrNull() is PreKeyClockException)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(400)
                .setBody("""{"ok":false,"reason":"stale timestamp"}""")
        }
        // Non-success responses are decoded generically before a success schema
        // can establish any publication evidence.
        assertEquals("stale timestamp", (client().preKeyPublicationStatus(identity, now).exceptionOrNull() as RelayRequestException).refusalReason)
    }

    @Test fun unsafeEndpointsAndInvalidClockFailBeforeNetwork() = runBlocking {
        for (url in listOf("", "http://relay.example.test", "https://user:pass@relay.example.test",
            "https://relay.example.test?x=1", "https://relay.example.test#x")) {
            assertTrue(DirectRelayClient(url).preKeyPublicationStatus(identity, now).isFailure)
        }
        for (time in listOf(0L, -1L, Long.MAX_VALUE)) assertTrue(client().preKeyPublicationStatus(identity, time).isFailure)
        assertEquals(0, server.requestCount)
        assertEquals("https://relay.example.test/path", DirectRelayClient("https://RELAY.example.test:443/path/").publicationScope())
    }

    @Test fun redirectAndDisconnectedResponseAreNotSilentlyRetried() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(307).addHeader("Location", server.url("/wrong"))
            .setBody("""{"ok":true}"""))
        assertTrue(client().preKeyPublicationStatus(identity, now).isFailure)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertTrue(client().preKeyPublicationStatus(identity, now).isFailure)
        assertEquals(2, server.requestCount)
    }
}
