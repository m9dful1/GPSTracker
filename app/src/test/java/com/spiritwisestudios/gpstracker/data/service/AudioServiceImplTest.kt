package com.spiritwisestudios.gpstracker.data.service

import android.speech.tts.TextToSpeech
import com.spiritwisestudios.gpstracker.domain.service.AudioService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    // --- progressFraction ---

    @Test
    fun `progress fraction tracks position through the text`() {
        assertEquals(0f, AudioServiceImpl.progressFraction(0, 100))
        assertEquals(0.5f, AudioServiceImpl.progressFraction(50, 100))
        assertEquals(1f, AudioServiceImpl.progressFraction(100, 100))
    }

    @Test
    fun `progress fraction is clamped to valid range`() {
        assertEquals(1f, AudioServiceImpl.progressFraction(150, 100))
        assertEquals(0f, AudioServiceImpl.progressFraction(-5, 100))
    }

    @Test
    fun `empty text yields zero progress instead of dividing by zero`() {
        assertEquals(0f, AudioServiceImpl.progressFraction(10, 0))
    }

    // --- languageUsable ---

    @Test
    fun `an available language is usable`() {
        assertTrue(AudioServiceImpl.languageUsable(TextToSpeech.LANG_AVAILABLE))
        assertTrue(AudioServiceImpl.languageUsable(TextToSpeech.LANG_COUNTRY_AVAILABLE))
        assertTrue(AudioServiceImpl.languageUsable(TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE))
    }

    @Test
    fun `a language with no voice data is not usable`() {
        // This was the silent-tour case: it resumed false, left the engine
        // holding a connection it never used, and said nothing to the user.
        assertFalse(AudioServiceImpl.languageUsable(TextToSpeech.LANG_MISSING_DATA))
        assertFalse(AudioServiceImpl.languageUsable(TextToSpeech.LANG_NOT_SUPPORTED))
    }

    @Test
    fun `an engine error is not usable`() {
        assertFalse(AudioServiceImpl.languageUsable(TextToSpeech.ERROR))
    }

    @Test
    fun `no engine to ask is not usable`() {
        assertFalse(AudioServiceImpl.languageUsable(null))
    }

    // --- what the UI is told ---

    @Test
    fun `the guide can speak in its own voice or the default one`() {
        assertTrue(AudioService.VoiceAvailability.READY.canSpeak)
        assertTrue(AudioService.VoiceAvailability.USING_DEFAULT_VOICE.canSpeak)
    }

    @Test
    fun `no voice data and no engine both mean silence`() {
        assertFalse(AudioService.VoiceAvailability.MISSING_VOICE_DATA.canSpeak)
        assertFalse(AudioService.VoiceAvailability.ENGINE_UNAVAILABLE.canSpeak)
        assertFalse(AudioService.VoiceAvailability.UNKNOWN.canSpeak)
    }
}
