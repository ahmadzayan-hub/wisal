package com.wifeassistant

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.wifeassistant.data.Settings
import com.wifeassistant.data.crypto.DeviceIdentityCodec
import com.wifeassistant.data.pairing.Pairing
import com.wifeassistant.ui.InviteAcceptScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// شاشة قبول الدعوة: قبول بيسجل الطرف محليًا، الدعوة المنتهية بترفض،
// وإعادة فتح دعوة مقبولة بتتقفل (منع replay على مستوى الجهاز).
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class InviteAcceptRoboTest {
    @get:Rule val rule = createComposeRule()

    private val now = 1_800_000_000L
    private val identity = DeviceIdentityCodec.generate()
    private fun inv(ttl: Long = Pairing.TTL_SECONDS) =
        Pairing.create(identity.deviceId, identity.publicKeyB64, "نور", nowEpochSec = now, ttlSeconds = ttl)

    @Before fun clearSyntheticPreferences() {
        ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences("wife_assistant_settings", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test fun acceptStoresPeerAndMarksInvitationUsed() {
        val settings = Settings(ApplicationProvider.getApplicationContext())
        val i = inv()
        rule.setContent { InviteAcceptScreen(invitation = i, onClose = {}, clock = { now + 60 }) }
        rule.onNodeWithText("قبول الدعوة").performClick()
        rule.onNodeWithText("تم القبول ✅").assertExists()

        val peers = settings.pairedPeers
        assertEquals(1, peers.size)
        assertEquals(identity.deviceId, peers[0].deviceId)
        assertEquals("نور", peers[0].name)
        assertTrue(i.id in settings.acceptedInvitationIds)
    }

    @Test fun expiredInvitationShowsExpiredState() {
        val i = inv(ttl = 100)
        rule.setContent { InviteAcceptScreen(invitation = i, onClose = {}, clock = { now + 101 }) }
        rule.onNodeWithText("الدعوة انتهت صلاحيتها أو مش صالحة. اطلب دعوة جديدة.").assertExists()
    }

    @Test fun alreadyAcceptedInvitationIsRefusedOnReopen() {
        val settings = Settings(ApplicationProvider.getApplicationContext())
        val i = inv()
        settings.acceptInvitation(i, now + 1)
        rule.setContent { InviteAcceptScreen(invitation = i, onClose = {}, clock = { now + 60 }) }
        rule.onNodeWithText("الدعوة دي اتقبلت قبل كده على الجهاز ده.").assertExists()
    }

    @Test fun deepLinkPayloadRoundTripsThroughParse() {
        // نفس المسار اللي MainActivity بيستخدمه مع dataString.
        val i = inv()
        val parsed = Pairing.parsePayload(Pairing.payload(i))
        assertEquals(i, parsed)
    }

    @Test fun invitationExpiringWhileScreenIsOpenCannotBeAccepted() {
        val i = inv(ttl = 100)
        var clickTime = now + 1
        rule.setContent { InviteAcceptScreen(invitation = i, onClose = {}, clock = { clickTime }) }
        rule.waitForIdle()
        clickTime = now + 100
        rule.onNodeWithText("قبول الدعوة").performClick()
        rule.onNodeWithText("الدعوة انتهت صلاحيتها أو مش صالحة. اطلب دعوة جديدة.").assertExists()
        val settings = Settings(ApplicationProvider.getApplicationContext())
        assertTrue(settings.pairedPeers.isEmpty())
        assertTrue(settings.acceptedInvitationIds.isEmpty())
    }

    @Test fun corruptSavedStateShowsRecoveryInsteadOfAcceptance() {
        ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences("wife_assistant_settings", android.content.Context.MODE_PRIVATE)
            .edit().putString("pairedPeers", "{invalid").commit()
        val i = inv()
        rule.setContent { InviteAcceptScreen(invitation = i, onClose = {}, clock = { now + 1 }) }
        rule.onNodeWithText("تعذّر تأكيد حفظ الإقران. لا تمسح بيانات التطبيق؛ أعد تشغيل وصال وحاول مرة أخرى.").assertExists()
    }

    @Test fun changedKeyIsRefusedAndTheExistingPeerIsPreserved() {
        val settings = Settings(ApplicationProvider.getApplicationContext())
        settings.acceptInvitation(inv(), now + 1)
        val changed = inv().copy(inviterPublicKeyB64 = DeviceIdentityCodec.generate().publicKeyB64)
        rule.setContent { InviteAcceptScreen(invitation = changed, onClose = {}, clock = { now + 2 }) }
        rule.onNodeWithText("قبول الدعوة").performClick()
        rule.onNodeWithText("مفتاح الجهاز مختلف عن الإقران المحفوظ. لم يتم استبداله. تحقق من الطرف الآخر قبل إعادة الإقران.").assertExists()
        assertEquals(identity.publicKeyB64, settings.pairedPeers.single().publicKeyB64)
    }
}
