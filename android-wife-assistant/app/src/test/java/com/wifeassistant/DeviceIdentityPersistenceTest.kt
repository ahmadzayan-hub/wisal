package com.wifeassistant

import com.wifeassistant.data.crypto.DeviceIdentityCodec
import com.wifeassistant.data.crypto.DeviceIdentityPersistence
import com.wifeassistant.data.crypto.LocalIdentity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DeviceIdentityPersistenceTest {
    @Test fun descriptionsNeverIncludePrivateKeyMaterial() {
        val identity = DeviceIdentityCodec.generate()
        assertFalse(identity.toString().contains(identity.privateKeyB64))
        assertTrue(identity.toString().contains("[redacted]"))
    }
    private fun encode(identity: LocalIdentity) = JsonObject(mapOf(
        "deviceId" to JsonPrimitive(identity.deviceId),
        "publicKeyB64" to JsonPrimitive(identity.publicKeyB64),
        "privateKeyB64" to JsonPrimitive(identity.privateKeyB64),
    )).toString()

    @Test fun firstUsePersistsBeforeReturningAndRestartPreservesTheIdentity() {
        var stored: String? = null; var writes = 0
        val first = DeviceIdentityPersistence.getOrCreate({ stored }, { stored = it; writes++ })
        val next = DeviceIdentityPersistence.getOrCreate({ stored }, { error("unexpected write") })
        assertEquals(first.deviceId, next.deviceId)
        assertTrue(first.privateKeyB64 == next.privateKeyB64); assertEquals(1, writes)
    }
    @Test fun unavailableReadNeverCreatesOrOverwritesAnIdentity() {
        var writes = 0
        val result = runCatching { DeviceIdentityPersistence.getOrCreate({ error("synthetic-private-error") }, { writes++ }) }
        assertTrue(result.isFailure); assertEquals(0, writes)
        assertFalse(result.exceptionOrNull().toString().contains("synthetic-private-error"))
        assertNull(result.exceptionOrNull()?.cause)
    }
    @Test fun failedOrUnverifiedWriteCannotReturnAnEphemeralIdentity() {
        assertTrue(runCatching { DeviceIdentityPersistence.getOrCreate({ null }, { error("write failed") }) }.isFailure)
        assertTrue(runCatching { DeviceIdentityPersistence.getOrCreate({ null }, { }) }.isFailure)
    }
    @Test fun malformedStoredDataIsPreservedAndNeverSilentlyReplaced() {
        for (stored in listOf("", "{invalid", "{}", "null", "x".repeat(16_385))) {
            var writes = 0
            assertTrue(runCatching { DeviceIdentityPersistence.getOrCreate({ stored }, { writes++ }) }.isFailure)
            assertEquals(0, writes)
        }
    }
    @Test fun keyPairMismatchAndInvalidIdentifiersAreRefusedWithoutWriting() {
        val valid = DeviceIdentityCodec.generate(); val other = DeviceIdentityCodec.generate()
        val invalid = listOf(valid.copy(privateKeyB64 = other.privateKeyB64), valid.copy(deviceId = ""),
            valid.copy(deviceId = "ambiguous:id"), valid.copy(publicKeyB64 = "invalid"))
        for (identity in invalid) {
            var writes = 0
            assertTrue(runCatching { DeviceIdentityPersistence.getOrCreate({ encode(identity) }, { writes++ }) }.isFailure)
            assertEquals(0, writes)
        }
    }
    @Test fun aDifferentEcCurveIsNotAcceptedAsAP256Identity() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp384r1")) }.generateKeyPair()
        val identity = LocalIdentity("synthetic-p384", Base64.getEncoder().encodeToString(pair.public.encoded), Base64.getEncoder().encodeToString(pair.private.encoded))
        assertTrue(runCatching { DeviceIdentityPersistence.getOrCreate({ encode(identity) }, { error("unexpected write") }) }.isFailure)
    }
    @Test fun futureMetadataDoesNotRotateExistingValidKeys() {
        val identity = DeviceIdentityCodec.generate()
        val extended = JsonObject(Json.parseToJsonElement(encode(identity)).jsonObject + ("future" to JsonPrimitive(true))).toString()
        val result = DeviceIdentityPersistence.getOrCreate({ extended }, { error("unexpected write") })
        assertEquals(identity.deviceId, result.deviceId)
    }
    @Test fun concurrentFirstUseCreatesOnlyOneIdentity() {
        var stored: String? = null; var writes = 0
        val pool = Executors.newFixedThreadPool(4)
        try {
            val results = pool.invokeAll(List(8) { Callable {
                DeviceIdentityPersistence.getOrCreate({ stored }, { stored = it; writes++ }).deviceId
            } }).map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, results.distinct().size); assertEquals(1, writes)
        } finally { pool.shutdownNow() }
    }
}
