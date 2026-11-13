package com.wifeassistant

import com.wifeassistant.data.crypto.DeviceIdentityCodec
import com.wifeassistant.data.pairing.Pairing
import com.wifeassistant.data.pairing.PairingResult
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class PairingValidationTest {
    private val now = 1_800_000_000L
    private val identity = DeviceIdentityCodec.generate()
    private fun invitation() = Pairing.create(identity.deviceId, identity.publicKeyB64, "Synthetic peer", now)

    @Test fun validGeneratedInvitationStillRoundTrips() {
        val inv = invitation()
        assertEquals(inv, Pairing.parsePayload(Pairing.payload(inv)))
        assertTrue(Pairing.accept(inv, inv.token, now + 1) is PairingResult.Ok)
    }

    @Test fun onlyTheExactPairingSchemeIsAccepted() {
        val encoded = Pairing.payload(invitation()).removePrefix("wisal://pair#")
        assertNull(Pairing.parsePayload(encoded))
        assertNull(Pairing.parsePayload("https://example.test/#" + encoded))
    }

    @Test fun malformedOrOversizedIdentityIsRejectedAtImportAndAccept() {
        val inv = invitation()
        for (bad in listOf(inv.copy(inviterPublicKeyB64 = "not-a-key"),
            inv.copy(inviterName = "x".repeat(20_000)), inv.copy(token = "short"),
            inv.copy(inviterDeviceId = ""), inv.copy(id = ""))) {
            assertNull(Pairing.parsePayload(Pairing.payload(bad)))
            assertTrue(Pairing.accept(bad, bad.token, now + 1) is PairingResult.Refused)
        }
    }

    @Test fun otherEcCurvesAreNotAcceptedAsP256() {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp384r1"))
        val bad = invitation().copy(inviterPublicKeyB64 =
            Base64.getEncoder().encodeToString(generator.generateKeyPair().public.encoded))
        assertNull(Pairing.parsePayload(Pairing.payload(bad)))
    }

    @Test fun futureCreationAndInvalidLifetimesAreRefused() {
        val inv = invitation()
        assertTrue(Pairing.accept(inv, inv.token, now - 1) is PairingResult.Refused)
        for (bad in listOf(inv.copy(expiresAtEpochSec = now),
            inv.copy(expiresAtEpochSec = now + Pairing.TTL_SECONDS + 1),
            inv.copy(createdAtEpochSec = -1), inv.copy(expiresAtEpochSec = Long.MAX_VALUE))) {
            assertNull(Pairing.parsePayload(Pairing.payload(bad)))
            assertTrue(Pairing.accept(bad, bad.token, now + 1) is PairingResult.Refused)
        }
    }

    @Test fun expiredAtClickIsRefusedEvenIfPreviouslyValid() {
        val inv = invitation()
        assertTrue(Pairing.accept(inv, inv.token, now + 1) is PairingResult.Ok)
        assertTrue(Pairing.accept(inv, inv.token, inv.expiresAtEpochSec) is PairingResult.Refused)
    }
}
