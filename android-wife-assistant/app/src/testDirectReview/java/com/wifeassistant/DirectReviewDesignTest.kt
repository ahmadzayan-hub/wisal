package com.wifeassistant

import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.compose.setContent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import com.wifeassistant.ui.theme.WifeAssistantTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "ar-ldrtl-w360dp-h800dp-xhdpi")
class DirectReviewDesignTest {
    @get:Rule val rule = createAndroidComposeRule<DirectReviewActivity>()
    private lateinit var root: View
    private val actions = mutableListOf<DirectReviewAction>()
    private val code = "PUBLIC_PAIRING_CODE_FOR_PREVIEW_ONLY"
    private val fingerprint = "7B9D14E2F8A03C6D1C0572BA9AD458F7E24169AFB59E82C10E73DA466D28C9B4"
    private val peerFingerprint = "AE70FC128B591F02D4AA62828F67C3A71FD5B192197CE8F4B6DC0D68A3295E26"

    private fun show(
        stage: DirectReviewStage,
        draft: String = "",
        error: Int? = null,
        dark: Boolean = false,
        fontScale: Float = 1f,
    ) {
        rule.activity.runOnUiThread {
            rule.activity.setContent {
                root = LocalView.current
                var message by remember { mutableStateOf(draft) }
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    WifeAssistantTheme(darkTheme = dark) {
                        DirectReviewContent(
                            stage, false, error?.let { DirectReviewError(it, false) },
                            message, { message = it }, remember { SnackbarHostState() },
                            onAction = actions::add,
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun capture(name: String) = rule.runOnIdle {
        val directory = File("build/wisal-direct-design").apply { mkdirs() }
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun enrollmentShowsOnlyTheTestRelayAndRegistration() {
        show(DirectReviewStage.Enrollment)
        rule.onNodeWithText("بصمة المفتاح العام للخادم").assertIsDisplayed()
        rule.onNodeWithText("رمز ربط الجهاز الآخر").assertDoesNotExist()
        rule.onNodeWithText("مزامنة مع الخادم").assertDoesNotExist()
        capture("Enrollment")
        val samplePin = "sha256/" + "A".repeat(43) + "="
        rule.onNode(hasSetTextAction()).performTextInput(samplePin)
        rule.onNode(hasText("تسجيل الجهاز") and hasClickAction()).performClick()
        rule.runOnIdle { assertEquals(listOf(DirectReviewAction.Register(samplePin)), actions) }
    }

    @Test fun pairingRequiresTheOtherCodeAndFingerprint() {
        val view = DirectReviewView(code, fingerprint, null, emptyList(), 0, 0, false)
        show(DirectReviewStage.Registered(view))
        rule.onNodeWithText("7B9D14E2  F8A03C6D", substring = true).assertIsDisplayed()
        rule.onNodeWithText("0E73DA46  6D28C9B4", substring = true).assertIsDisplayed()
        rule.onNodeWithText(code).assertDoesNotExist()
        rule.onNodeWithText("مزامنة مع الخادم").assertDoesNotExist()
        capture("Pairing")
        rule.onNodeWithText("نسخ رمز الربط").performClick()
        rule.runOnIdle {
            val clipboard = root.context.getSystemService(ClipboardManager::class.java)
            assertEquals(code, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        }
        val fields = rule.onAllNodes(hasSetTextAction())
        fields[0].performTextInput(code)
        rule.onNodeWithText("تحقّق واقترن").performScrollTo().assertIsNotEnabled()
        fields[1].performTextInput(peerFingerprint)
        rule.onNodeWithText("تحقّق واقترن").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(listOf(DirectReviewAction.Pair(code, peerFingerprint)), actions) }
    }

    @Test fun messagingKeepsQueueAndSyncSeparateAndConfirmsRevocation() {
        val view = DirectReviewView(
            code, fingerprint, peerFingerprint,
            listOf("وصلت رسالة الاختبار.", "سأعيد تشغيل الجهاز ثم أجرّب المزامنة."),
            queued = 1, expired = 0, canRestartInitialSession = false,
        )
        show(DirectReviewStage.Registered(view), draft = "تمت إعادة التشغيل. سأعيد المزامنة الآن.")
        rule.onNodeWithText("تم التحقق من الجهاز الآخر").assertIsDisplayed()
        rule.onNodeWithText("رسالة واحدة بانتظار الإرسال").assertIsDisplayed()
        rule.onNodeWithText("رمز ربط الجهاز الآخر").assertDoesNotExist()
        capture("Messages")
        rule.onNodeWithText("إضافة لقائمة الإرسال").performClick()
        rule.onNodeWithText("مزامنة مع الخادم").performClick()
        rule.onNodeWithContentDescription("بيانات الجهاز").performClick()
        rule.onNodeWithText("إلغاء تسجيل الجهاز").performScrollTo().performClick()
        rule.onNodeWithText("إلغاء تسجيل هذا الجهاز؟").assertIsDisplayed()
        rule.runOnIdle { assertEquals(listOf(DirectReviewAction.Queue, DirectReviewAction.Sync), actions) }
        rule.onAllNodesWithText("إلغاء تسجيل الجهاز")[1].performClick()
        rule.runOnIdle { assertEquals(DirectReviewAction.Revoke, actions.last()) }
    }

    @Test
    @Config(sdk = [34], qualifiers = "en-ldltr-w360dp-h800dp-xhdpi")
    fun failureKeepsPairingAvailableInDarkMode() {
        val view = DirectReviewView(code, fingerprint, null, emptyList(), 0, 0, false)
        show(DirectReviewStage.Registered(view), error = R.string.direct_review_pair_failed, dark = true)
        rule.onNodeWithText("Pairing failed.", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Pair a device").assertIsDisplayed()
        rule.onNodeWithText("Verify and pair").performScrollTo().assertIsNotEnabled()
        capture("PairingDark")
    }

    @Test
    @Config(sdk = [34], qualifiers = "ar-ldrtl-w360dp-h350dp-xhdpi")
    fun shortWindowKeepsTheComposerAndSyncReachableAtLargerText() {
        val view = DirectReviewView(code, fingerprint, peerFingerprint, emptyList(), 0, 0, false)
        show(DirectReviewStage.Registered(view), fontScale = 1.5f)
        rule.onNode(hasSetTextAction()).performScrollTo()
            .performTextInput("أراجع ترتيب ظهور الرسائل.\nسأعيد تشغيل الجهاز.\nأعيد المزامنة بعد التشغيل.")
        rule.onNodeWithText("إضافة لقائمة الإرسال").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("مزامنة مع الخادم").performScrollTo().assertIsDisplayed()
    }
}
