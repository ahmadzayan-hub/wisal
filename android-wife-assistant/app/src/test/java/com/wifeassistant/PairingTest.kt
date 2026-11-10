package com.wifeassistant

import com.wifeassistant.data.crypto.DeviceIdentityCodec
import com.wifeassistant.data.pairing.Pairing
import com.wifeassistant.data.pairing.PairingInvitation
import com.wifeassistant.data.pairing.PairingResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// دعوات الإقران: انتهاء صلاحية، استخدام أحادي (منع replay)، إلغاء — بوقت محقون.
class PairingTest {
    private val now = 1_800_000_000L
    private val identity = DeviceIdentityCodec.generate()
    private fun inv(ttl: Long = Pairing.TTL_SECONDS) =
        Pairing.create(identity.deviceId, identity.publicKeyB64, "أحمد", nowEpochSec = now, ttlSeconds = ttl)

    @Test fun acceptWithinTtlSucceedsOnce() {
        val i = inv()
        val r = Pairing.accept(i, i.token, now + 60)
        assertTrue(r is PairingResult.Ok)
        assertEquals(PairingInvitation.STATE_ACCEPTED, (r as PairingResult.Ok).invitation.state)
    }

    @Test fun expiredInvitationIsRefused() {
        val i = inv(ttl = 100)
        val r = Pairing.accept(i, i.token, now + 101)
        assertTrue(r is PairingResult.Refused)
        assertEquals("expired", (r as PairingResult.Refused).reason)
    }

    @Test fun replayIsRefusedAfterAcceptance() {
        val i = inv()
        val first = Pairing.accept(i, i.token, now + 10) as PairingResult.Ok
        // إعادة تقديم نفس التوكن على الدعوة المقبولة = replay مرفوض.
        val second = Pairing.accept(first.invitation, i.token, now + 20)
        assertTrue(second is PairingResult.Refused)
    }

    @Test fun wrongTokenIsRefused() {
        val i = inv()
        assertTrue(Pairing.accept(i, i.token + "x", now + 10) is PairingResult.Refused)
    }

    @Test fun revokedInvitationCannotBeAccepted() {
        val i = Pairing.revoke(inv())
        assertTrue(Pairing.accept(i, i.token, now + 10) is PairingResult.Refused)
    }

    @Test fun tokensAreUniqueAndUrlSafe() {
        val a = inv(); val b = inv()
        assertNotEquals(a.token, b.token)
        assertTrue(a.token.matches(Regex("[A-Za-z0-9_-]{40,}")))
    }

    @Test fun payloadRoundTripsAndRejectsGarbage() {
        val i = inv()
        val parsed = Pairing.parsePayload(Pairing.payload(i))
        assertEquals(i, parsed)
        assertNull(Pairing.parsePayload("wisal://pair#ليس-بيلود"))
    }

    @Test fun identitySignsAndVerifies() {
        val id = DeviceIdentityCodec.generate()
        val data = "wisal-pairing-proof".toByteArray()
        val sig = DeviceIdentityCodec.sign(id, data)
        assertTrue(DeviceIdentityCodec.verify(id.publicKeyB64, data, sig))
        // توقيع متلاعب فيه أو مفتاح مختلف = رفض.
        assertFalse(DeviceIdentityCodec.verify(id.publicKeyB64, "tampered".toByteArray(), sig))
        val other = DeviceIdentityCodec.generate()
        assertFalse(DeviceIdentityCodec.verify(other.publicKeyB64, data, sig))
    }

    @Test fun identityHasNoPhoneOrEmailFields() {
        // الهوية = معرّف + مفاتيح بس (§5.2: مفيش رقم ولا إيميل إجباري).
        val id = DeviceIdentityCodec.generate()
        assertTrue(id.deviceId.isNotBlank())
        assertTrue(id.publicKeyB64.isNotBlank())
        assertEquals(id.deviceId, id.public.deviceId)
    }
}
