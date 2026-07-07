package com.spiritwisestudios.gpstracker.data.service

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioServiceImplTest {

    private val text = "First sentence here. Second sentence follows. Third one closes."

    @Test
    fun `position zero resumes from the start`() {
        assertEquals(text, AudioServiceImpl.resumeTextFrom(text, 0))
    }

    @Test
    fun `mid-sentence position resumes at that sentence's start`() {
        // Position inside "Second sentence follows."
        val position = text.indexOf("sentence follows")
        assertEquals(
            "Second sentence follows. Third one closes.",
            AudioServiceImpl.resumeTextFrom(text, position)
        )
    }

    @Test
    fun `position in first sentence restarts whole text`() {
        assertEquals(text, AudioServiceImpl.resumeTextFrom(text, 5))
    }

    @Test
    fun `position past the end returns last sentence rather than empty`() {
        val result = AudioServiceImpl.resumeTextFrom(text, text.length)
        assertEquals("Third one closes.", result)
    }
}
