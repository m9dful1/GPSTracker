package com.spiritwisestudios.gpstracker.data.service

import com.spiritwisestudios.gpstracker.domain.model.UserPreferences
import org.junit.Assert.assertEquals
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
}
