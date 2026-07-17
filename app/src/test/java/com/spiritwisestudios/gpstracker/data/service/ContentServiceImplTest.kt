package com.spiritwisestudios.gpstracker.data.service

import com.spiritwisestudios.gpstracker.domain.model.TourContent
import com.spiritwisestudios.gpstracker.domain.model.UserPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentServiceImplTest {

    private val fourSentences = "One is here. Two is there! Three is somewhere? Four ends it."

    @Test
    fun `brief keeps two sentences`() {
        assertEquals(
            "One is here. Two is there!",
            ContentServiceImpl.trimToDetailLevel(fourSentences, UserPreferences.DetailLevel.BRIEF)
        )
    }

    @Test
    fun `detailed keeps everything`() {
        assertEquals(
            fourSentences,
            ContentServiceImpl.trimToDetailLevel(fourSentences, UserPreferences.DetailLevel.DETAILED)
        )
    }

    @Test
    fun `short text is unchanged at any level`() {
        assertEquals(
            "Just one sentence.",
            ContentServiceImpl.trimToDetailLevel("Just one sentence.", UserPreferences.DetailLevel.BRIEF)
        )
    }

    @Test
    fun `cached content serves when it matches the tier's narration`() {
        // Premium (AI narration) keeps its AI scripts; standard keeps its
        // parsed articles
        assertTrue(
            ContentServiceImpl.cacheMatchesTier(
                TourContent.ContentSource.AI_GENERATED, aiNarration = true
            )
        )
        assertTrue(
            ContentServiceImpl.cacheMatchesTier(
                TourContent.ContentSource.THIRD_PARTY, aiNarration = false
            )
        )
    }

    @Test
    fun `a tier change regenerates the other tier's cached content`() {
        // Upgrading to premium replaces a parsed article with an AI script...
        assertFalse(
            ContentServiceImpl.cacheMatchesTier(
                TourContent.ContentSource.THIRD_PARTY, aiNarration = true
            )
        )
        // ...and downgrading replaces the AI script with the parsed article
        assertFalse(
            ContentServiceImpl.cacheMatchesTier(
                TourContent.ContentSource.AI_GENERATED, aiNarration = false
            )
        )
    }
}
