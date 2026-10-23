package com.wifeassistant

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.wifeassistant.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReviewedGenerationRoboTest {
    private fun setup(enabled: Boolean): Pair<Settings, SuggestionEngine> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = Settings(context)
        settings.groqEnabled = enabled
        settings.recipients = listOf(Recipient("fictional-review", "Alex", "colleague", notes = "PRIVATE SAVED NOTE MUST NOT BE USED", language = "en"))
        settings.selectedRecipientId = "fictional-review"
        return settings to SuggestionEngine(Store(context), GroqClient(settings), settings)
    }
    @Test fun disabledModeIsExplicitLocalTemplateNotAgentSuccess() = runBlocking {
        val (_, engine) = setup(false)
        val stages = mutableListOf<DraftStage>()
        val result = engine.generateReviewed("manual", intentId = "follow_up", context = "Ask about the draft.", onStage = stages::add)
        assertTrue(result.offline); assertEquals(2, result.items.size); assertTrue(stages.isEmpty())
        assertNull(result.reviewNote)
        assertFalse(result.items.any { it.text.contains("PRIVATE SAVED NOTE") })
    }
    @Test fun clarifierWorksWithoutReadingOrSavingAKey() = runBlocking {
        val (_, engine) = setup(true)
        val result = engine.generateReviewed("manual", intentId = "request", context = "")
        assertTrue(result.items.isEmpty()); assertNotNull(result.reviewNote); assertFalse(result.offline)
    }
    @Test fun secretInSourceDoesNotBecomeProviderInputOrDraft() = runBlocking {
        val (_, engine) = setup(true)
        val source = "api_key=gsk_fictionalnotasecret12345"
        val result = engine.generateReviewed("manual", intentId = "request", context = source)
        assertTrue(result.items.isEmpty()); assertFalse(result.reviewNote.orEmpty().contains(source))
    }
}
