package com.wifeassistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.provider.Settings
import android.view.View
import android.os.Looper
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.wifeassistant.data.*
import com.wifeassistant.ui.*
import com.wifeassistant.ui.theme.WifeAssistantTheme
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class MotionUiTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var view: View
    private lateinit var inputMode: InputModeManager
    @After fun reset() {
        I18n.lang = "ar"
        Settings.Global.putFloat(ApplicationProvider.getApplicationContext<Context>().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }
    private fun show(content: @Composable () -> Unit) {
        I18n.lang = "en"
        rule.setContent { view = LocalView.current; inputMode = LocalInputModeManager.current; WifeAssistantTheme(darkTheme = false) { Surface(Modifier.fillMaxSize()) { Column(Modifier.fillMaxWidth().padding(20.dp)) { content() } } } }
    }
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    private fun keyboardMode() = rule.runOnIdle { assertTrue(inputMode.requestInputMode(InputMode.Keyboard)) }
    private fun capture(name: String) = rule.runOnIdle {
        val directory = File("build/wisal-screenshots/motion").apply { mkdirs() }
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun systemZeroDurationUsesImmediateStaticContent() {
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        var allowed = true
        show {
            allowed = rememberMotionAllowed()
            WisalReveal(allowed) { Text("Ready to edit") }
            WisalDisclosure(true, allowed) { Text("Language options") }
        }
        rule.runOnIdle { assertFalse(allowed) }
        rule.onNodeWithText("Ready to edit").assertIsDisplayed()
        rule.onNodeWithText("Language options").assertIsDisplayed()
        capture("static-equivalent")
    }
    @Test fun disablingSystemMotionKeepsFocusedDraftMounted() {
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        val focus = FocusRequester()
        var allowed = true
        var draft by mutableStateOf("Keep my words")
        show {
            allowed = rememberMotionAllowed()
            WisalReveal(allowed) { OutlinedTextField(draft, { draft = it }, modifier = Modifier.focusRequester(focus)) }
        }
        rule.runOnIdle { focus.requestFocus() }
        rule.onNode(hasSetTextAction()).assertIsFocused()
        rule.runOnIdle {
            Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
            resolver.notifyChange(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), null)
        }
        shadowOf(Looper.getMainLooper()).idle()
        rule.waitForIdle()
        rule.runOnIdle { assertFalse(allowed) }
        rule.onNode(hasSetTextAction()).assertIsFocused().assertTextContains("Keep my words")
    }
    @Test fun hiddenOptionsAreRemovedAndToggleFocusIsStable() {
        var expanded by mutableStateOf(false)
        var motion by mutableStateOf(true)
        val focus = FocusRequester()
        show {
            Button(onClick = { expanded = !expanded }, modifier = Modifier.focusRequester(focus)) { Text("Options") }
            WisalDisclosure(expanded, motion) { Text("Optional controls") }
        }
        keyboardMode()
        rule.runOnIdle { focus.requestFocus() }
        rule.onNodeWithText("Options").assertIsFocused()
        rule.runOnIdle { expanded = true }
        rule.onNodeWithText("Optional controls").assertExists()
        rule.onNodeWithText("Options").assertIsFocused()
        rule.runOnIdle { motion = false }
        rule.onNodeWithText("Options").assertIsFocused()
        rule.runOnIdle { expanded = false }
        rule.onNodeWithText("Optional controls").assertDoesNotExist()
        rule.onNodeWithText("Options").assertIsFocused()
    }
    @Test fun realStageChangesKeepCancelFocusedAndCancellationRemovesStatus() {
        var stage by mutableStateOf(DraftStage.WRITE)
        var loading by mutableStateOf(true)
        show { if (loading) DraftProgress(stage, false) { loading = false } }
        rule.onNodeWithText("Writing drafts").assertExists()
        keyboardMode()
        rule.onNodeWithTag("cancel-draft").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        rule.onNodeWithTag("cancel-draft").assertIsFocused()
        rule.runOnIdle { stage = DraftStage.REVIEW }
        rule.onNodeWithText("Writing drafts").assertDoesNotExist()
        rule.onNodeWithText("Reviewing meaning and privacy").assertExists()
        rule.onNodeWithTag("cancel-draft").assertIsFocused()
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)
        rule.onNodeWithTag("cancel-draft").performClick()
        rule.onNodeWithTag("draft-stage").assertDoesNotExist()
    }
    @Test fun copySuccessIsNotOptimisticAndShareIsNotSentConfirmation() {
        var fail = true
        var copies = 0
        var shares = 0
        var confirmations = 0
        var text by mutableStateOf("A fictional draft")
        show {
            Text("Fictional fixture · no provider call")
            SuggestionCard(index = 0, item = Suggestion(text, "neutral"), motionAllowed = false,
                onEdit = { text = it }, onConfirmContact = { confirmations++ }, sendLabel = "Review in WhatsApp", onChoose = {},
                onCopy = { copies++; if (fail) error("clipboard unavailable") }, onShare = { shares++ },
                onWhatsApp = {}, onGroup = {}, onRefine = {}, onRemind = {})
        }
        rule.onNodeWithText("Copy").performClick()
        rule.onNodeWithText("Copied").assertDoesNotExist()
        rule.onNodeWithText("Retry copy").assertExists()
        fail = false
        rule.onNodeWithText("Retry copy").performClick()
        rule.onNodeWithText("Copied").assertExists()
        assertEquals(2, copies)
        capture("copy-confirmed")
        rule.runOnIdle { text = "Edited fictional draft" }
        rule.onNodeWithText("Copy").assertExists()
        rule.onNodeWithText("Copied").assertDoesNotExist()
        rule.onNodeWithText("Share").performClick()
        assertEquals(1, shares); assertEquals(2, copies); assertEquals(0, confirmations)
        rule.onNodeWithText("Copied").assertDoesNotExist()
        rule.onNodeWithText("Sent").assertDoesNotExist()
    }
    @Test fun revealFinishesAndProducesActualTransitionFrames() {
        var visible by mutableStateOf(false)
        show {
            Text("Local templates · not AI output", style = MaterialTheme.typography.labelLarge)
            if (visible) WisalReveal(true) {
                SuggestionCard(index = 0, item = Suggestion("Alex, I hope you're doing well. I wanted to check in.", "neutral"),
                    motionAllowed = true, onEdit = {}, onConfirmContact = {}, sendLabel = "Review in WhatsApp", onChoose = {},
                    onCopy = {}, onShare = {}, onWhatsApp = {}, onGroup = {}, onRefine = {}, onRemind = {})
            }
        }
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { visible = true }
        rule.mainClock.advanceTimeByFrame()
        capture("reveal-000")
        capture("draft-entry-00")
        for (frame in 1..12) {
            rule.mainClock.advanceTimeByFrame()
            capture("draft-entry-${frame.toString().padStart(2, '0')}")
            if (frame == 5) capture("reveal-080")
        }
        rule.mainClock.advanceTimeBy(128)
        capture("reveal-320")
        rule.onNodeWithText("Draft 1").assertIsDisplayed()
        rule.mainClock.advanceTimeBy(1000)
        capture("reveal-settled")
        assertArrayEquals(File("build/wisal-screenshots/motion/reveal-320.png").readBytes(), File("build/wisal-screenshots/motion/reveal-settled.png").readBytes())
        rule.mainClock.autoAdvance = true
    }
    @Test fun arabicLargeTextStageAndCancelRemainReadable() {
        show {
            I18n.lang = "ar"
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl, LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                DraftProgress(DraftStage.REVIEW, false) {}
            }
        }
        rule.onNodeWithText("مراجعة المعنى والخصوصية").assertIsDisplayed()
        rule.onNodeWithText("إلغاء").assertIsDisplayed()
        capture("stage-ar-large-text")
    }
}
