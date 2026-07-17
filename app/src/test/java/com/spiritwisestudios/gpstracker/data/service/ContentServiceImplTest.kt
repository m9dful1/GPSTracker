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
    fun `pronunciation asides are cut before speech`() {
        // The classic Wikipedia opener: IPA and translations in parentheses
        // that a TTS engine would read letter by letter
        assertEquals(
            "San Francisco is a commercial and cultural hub.",
            ContentServiceImpl.cleanForSpeech(
                "San Francisco (/ˌsæn frənˈsɪskoʊ/; Spanish for 'Saint Francis') " +
                    "is a commercial and cultural hub."
            )
        )
    }

    @Test
    fun `nested parentheticals unwrap fully`() {
        assertEquals(
            "The fort guarded the bay.",
            ContentServiceImpl.cleanForSpeech(
                "The fort (completed in 1861 (during the Civil War)) guarded the bay."
            )
        )
    }

    @Test
    fun `reference markers and stray whitespace are healed`() {
        assertEquals(
            "The bridge opened in 1937. It is painted orange.",
            ContentServiceImpl.cleanForSpeech(
                "The bridge opened in 1937.[1]  It is  painted orange .[citation needed]"
            )
        )
    }

    @Test
    fun `clean text passes through unchanged`() {
        assertEquals(
            "A plain sentence stays as it is.",
            ContentServiceImpl.cleanForSpeech("A plain sentence stays as it is.")
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
