package com.wifeassistant

import com.wifeassistant.data.*
import org.junit.Assert.*
import org.junit.Test

class InclusiveComposerTest {
    @Test fun unknownRelationshipsAreNotRomantic() {
        assertEquals("other", Relations.byId("unknown").id)
        listOf("friend", "colleague", "client").forEach { assertEquals(it, Relations.byId(it).id) }
    }
    @Test fun englishTemplatesAreActuallyEnglish() {
        val recipient = Recipient("fictional-1", "Alex", "colleague", language = "en")
        val texts = FallbackBank.two(recipient, Intents.byId("follow_up"))
        assertEquals(2, texts.size)
        texts.forEach { assertFalse(it.text.any { c -> c.code in 0x0600..0x06FF }) }
    }
    @Test fun professionalTemplatesDoNotDefaultToRomance() {
        val recipient = Recipient("fictional-2", "سلمى", "client", language = "ar")
        listOf(null, "thanks", "apology", "follow_up").forEach { intent ->
            FallbackBank.two(recipient, Intents.byId(intent)).forEach {
                assertFalse(it.text.contains("بحبك"))
                assertFalse(it.text.contains("قلبي"))
            }
        }
    }
    @Test fun currentCatalogDoesNotContainRetiredModels() {
        assertTrue(AppConstants.MODELS.none { it.first in AppConstants.RETIRED_MODELS })
        assertTrue(AppConstants.MODELS.any { it.first == AppConstants.DEFAULT_MODEL })
    }
}
