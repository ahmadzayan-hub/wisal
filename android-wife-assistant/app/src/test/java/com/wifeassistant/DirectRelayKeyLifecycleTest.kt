package com.wifeassistant

import com.wifeassistant.data.crypto.DeviceIdentityCodec
import com.wifeassistant.data.crypto.LocalIdentity
import com.wifeassistant.data.crypto.SignalPreKeyBundleDto
import com.wifeassistant.data.pairing.DirectRelayClient
import com.wifeassistant.data.pairing.DirectRelayProofs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Synthetic public wire fields; this suite tests transport, not a Signal handshake. */
class DirectRelayKeyLifecycleTest {
    private lateinit var server: MockWebServer
    private val now = 1_800_000_000L
    private fun bundle(id: String) = SignalPreKeyBundleDto(id, 1, "AQ==", 2, "Ag==", "Aw==", 3, "BA==", "BQ==", 4, "Bg==")
    private fun client() = DirectRelayClient(server.url("/").toString())
    private fun response(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setBody(body)
    private fun verify(identity: LocalIdentity, body: String, expected: String) {
        val payload = Json.parseToJsonElement(body).jsonObject
        val signature = payload.getValue("signatureB64").jsonPrimitive.content
        assertFalse(body.contains(identity.privateKeyB64))
        assertTrue(DeviceIdentityCodec.verify(identity.publicKeyB64, expected.toByteArray(Charsets.UTF_8), Base64.getDecoder().decode(signature)))
    }
    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    @Test fun proofFormatsMatchTheNodeRelayContract() {
        assertEquals("wisal-direct-prekeys:1800000000:devA:1:AQ==:2:Ag==:Aw==:3:BA==:BQ==:4:Bg==", DirectRelayProofs.publishPreKeys(bundle("devA"), now))
        assertEquals("claim-prekeys:devA:devB:1800000000", DirectRelayProofs.claimPreKeys("devA", "devB", now))
        assertEquals("revoke:devA:1800000000", DirectRelayProofs.revoke("devA", now))
    }
    @Test fun publishesTheWholeBundleWithTheServerProofAndChecksReceipt() = runBlocking {
        val identity = DeviceIdentityCodec.generate()
        server.enqueue(response("""{"ok":true,"deviceId":"${identity.deviceId}","oneTimePreKeyId":4}"""))
        assertTrue(client().publishPreKeys(identity, bundle(identity.deviceId), now).isSuccess)
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method); assertEquals("/api/prekeys", request.path)
        val body = request.body.readUtf8()
        verify(identity, body, "wisal-direct-prekeys:$now:${identity.deviceId}:1:AQ==:2:Ag==:Aw==:3:BA==:BQ==:4:Bg==")
        assertTrue(body.contains("\"bundle\"")); assertTrue(body.contains("\"timestamp\":$now"))
    }
    @Test fun claimsAKeyForExactlyTheRequestedDevice() = runBlocking {
        val identity = DeviceIdentityCodec.generate()
        server.enqueue(response("""{"ok":true,"bundle":${Json.encodeToString(SignalPreKeyBundleDto.serializer(), bundle("peer"))}}"""))
        assertEquals(bundle("peer"), client().claimPreKeys(identity, "peer", now).getOrThrow())
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method); assertEquals("/api/prekeys-claim", request.path)
        verify(identity, request.body.readUtf8(), "claim-prekeys:${identity.deviceId}:peer:$now")
    }
    @Test fun revokesOnlyAfterAnExplicitMatchingReceipt() = runBlocking {
        val identity = DeviceIdentityCodec.generate()
        server.enqueue(response("""{"ok":true,"deviceId":"${identity.deviceId}","revoked":true}"""))
        assertTrue(client().revokeDevice(identity, now).isSuccess)
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/api/revoke", request.path)
        verify(identity, request.body.readUtf8(), "revoke:${identity.deviceId}:$now")
    }
    @Test fun rejectsInvalidPublicationBeforeAnyRequest() = runBlocking {
        val identity = DeviceIdentityCodec.generate()
        assertTrue(client().publishPreKeys(identity, bundle("someone-else"), now).isFailure)
        assertTrue(client().publishPreKeys(identity, bundle(identity.deviceId).copy(oneTimePreKeyId = 0), now).isFailure)
        assertTrue(client().publishPreKeys(identity, bundle(identity.deviceId).copy(identityKeyB64 = "not base64"), now).isFailure)
        assertTrue(client().publishPreKeys(identity, bundle(identity.deviceId), 0).isFailure)
        assertEquals(0, server.requestCount)
    }
    @Test fun rejectsMissingSelfAndMalformedClaimTargetsWithoutNetwork() = runBlocking {
        val identity = DeviceIdentityCodec.generate()
        for (target in listOf("", identity.deviceId, "target:ambiguous")) {
            assertTrue(client().claimPreKeys(identity, target, now).isFailure)
        }
        assertTrue(client().claimPreKeys(identity, "peer", -1).isFailure)
        assertTrue(client().revokeDevice(identity, 0).isFailure)
        assertEquals(0, server.requestCount)
    }
    @Test fun missingConfigurationFailsAllNewOperations() = runBlocking {
        val identity = DeviceIdentityCodec.generate(); val client = DirectRelayClient("")
        assertTrue(client.publishPreKeys(identity, bundle(identity.deviceId), now).isFailure)
        assertTrue(client.claimPreKeys(identity, "peer", now).isFailure)
        assertTrue(client.revokeDevice(identity, now).isFailure)
    }
    @Test fun rejectsAbsentWrongDeviceAndMalformedClaimedBundles() = runBlocking {
        val identity = DeviceIdentityCodec.generate()
        val bodies = listOf(
            """{"ok":true}""",
            """{"ok":true,"bundle":${Json.encodeToString(SignalPreKeyBundleDto.serializer(), bundle("wrong"))}}""",
            """{"ok":true,"bundle":${Json.encodeToString(SignalPreKeyBundleDto.serializer(), bundle("peer").copy(oneTimePreKeyId = 0))}}""",
            """{"ok":true,"bundle":{"deviceId":"peer"}}""",
        )
        for (body in bodies) { server.enqueue(response(body)); assertTrue(client().claimPreKeys(identity, "peer", now).isFailure) }
    }
    @Test fun doesNotAcceptBareOkAsPublicationOrRevocationReceipt() = runBlocking {
        val identity = DeviceIdentityCodec.generate()
        server.enqueue(response("""{"ok":true}""")); assertTrue(client().publishPreKeys(identity, bundle(identity.deviceId), now).isFailure)
        server.enqueue(response("""{"ok":true,"deviceId":"wrong","oneTimePreKeyId":4}""")); assertTrue(client().publishPreKeys(identity, bundle(identity.deviceId), now).isFailure)
        server.enqueue(response("""{"ok":true,"deviceId":"${identity.deviceId}","revoked":false}""")); assertTrue(client().revokeDevice(identity, now).isFailure)
        server.enqueue(response("""{"ok":true,"deviceId":"wrong","revoked":true}""")); assertTrue(client().revokeDevice(identity, now).isFailure)
    }
    @Test fun propagatesExhaustedKeyRefusalAndMalformedServerResponsesWithoutRetry() = runBlocking {
        val identity = DeviceIdentityCodec.generate()
        server.enqueue(response("""{"ok":false,"reason":"no one-time pre-key available"}""", 400))
        assertEquals("no one-time pre-key available", client().claimPreKeys(identity, "peer", now).exceptionOrNull()?.message)
        server.enqueue(response("not JSON", 503))
        assertTrue(client().claimPreKeys(identity, "peer", now).isFailure)
        assertEquals(2, server.requestCount)
    }
    @Test fun refusesRedirectsWithoutForwardingTheSignedClaim() = runBlocking {
        val identity = DeviceIdentityCodec.generate()
        server.enqueue(response("""{"ok":true}""", 307).addHeader("Location", server.url("/unexpected")))
        assertTrue(client().claimPreKeys(identity, "peer", now).isFailure)
        assertEquals(1, server.requestCount)
    }
}
