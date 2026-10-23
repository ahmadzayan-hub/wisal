package com.wifeassistant

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.wifeassistant.data.*
import com.wifeassistant.ui.HomeScreen
import com.wifeassistant.ui.HomeViewModel
import com.wifeassistant.ui.theme.WifeAssistantTheme
import java.io.File
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real Compose render captures with synthetic adults only. Not device screenshots. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class ComposerFlowTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var contentView: View
    @After fun resetLanguage() { I18n.lang = "ar" }

    private fun show(language: String): HomeViewModel {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val settings = Settings(app)
        settings.aiNoticeAck = true
        settings.groqEnabled = false
        settings.recipients = listOf(
            Recipient("demo-alex", if (language == "ar") "سلمى" else "Alex", "colleague", language = language),
            Recipient("demo-sam", if (language == "ar") "نور" else "Sam", "friend", language = language),
        )
        settings.selectedRecipientId = "demo-alex"
        I18n.lang = language
        val vm = HomeViewModel(app)
        rule.setContent {
            contentView = LocalView.current
            CompositionLocalProvider(LocalLayoutDirection provides if (language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                WifeAssistantTheme(darkTheme = false) {
                    HomeScreen(vm, {}, {}, {}, {})
                }
            }
        }
        rule.waitForIdle()
        return vm
    }

    private fun capture(name: String) {
        val directory = File("build/wisal-screenshots").apply { mkdirs() }
        rule.runOnIdle {
            val bitmap = Bitmap.createBitmap(contentView.width, contentView.height, Bitmap.Config.ARGB_8888)
            contentView.draw(Canvas(bitmap))
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun englishComposerAndEditableLocalDraft() {
        val vm = show("en")
        rule.onNodeWithText("Clear words. Better connections.").assertExists()
        capture("composer-en")
        rule.onNodeWithText("Suggest two drafts").performScrollTo().performClick()
        rule.waitUntil(5000) { vm.state.value.items.size == 2 }
        rule.onNodeWithText("Local templates · not AI output").performScrollTo().assertExists()
        val input = rule.onAllNodes(hasSetTextAction())[1]
        input.performScrollTo().performTextReplacement("Hello Alex, thanks for reviewing the draft.")
        rule.runOnIdle { assertEquals("Hello Alex, thanks for reviewing the draft.", vm.state.value.items[0].text) }
        rule.onAllNodesWithText("Share")[0].performScrollTo()
        capture("draft-en")
        val store = Store(ApplicationProvider.getApplicationContext())
        assertNull(store.daysSinceContact("demo-alex"))
        rule.runOnIdle { vm.choose(0) }
        assertNull(store.daysSinceContact("demo-alex"))
        rule.runOnIdle { vm.selectRecipient("demo-sam") }
        rule.runOnIdle { assertTrue(vm.state.value.items.isEmpty()) }
    }

    @Test fun arabicComposerUsesRtlLayout() {
        show("ar")
        rule.onNodeWithText("كلام أوضح. تواصل أقرب.").assertExists()
        capture("composer-ar")
    }
}
