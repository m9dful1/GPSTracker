package com.spiritwisestudios.gpstracker.domain.service

import com.spiritwisestudios.gpstracker.domain.model.TourContent
import com.spiritwisestudios.gpstracker.domain.model.UserPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Service interface for text-to-speech and audio management functionality.
 */
interface AudioService {

    /**
     * Progress through the current narration as a fraction of [0, 1].
     * Resets when a narration starts and jumps to 1 on completion.
     * Word-level granularity needs API 26+ (onRangeStart); older devices
     * only see the 0 and 1 endpoints. Navigation prompts don't move it.
     */
    val speechProgress: StateFlow<Float>

    /**
     * Whether the guide can actually speak, and in which voice. The single
     * source of truth: a tour with no working voice is silent, and silence
     * with no explanation is indistinguishable from a broken app.
     */
    val voiceAvailability: StateFlow<VoiceAvailability>

    /**
     * Initialize the text-to-speech engine.
     *
     * @param userPreferences User preferences for voice settings
     * @return True if the engine can speak (see [voiceAvailability] for what
     *   it will sound like, and why not, when it can't)
     */
    suspend fun initialize(userPreferences: UserPreferences): Boolean
    
    /**
     * Speak the provided content.
     * 
     * @param content The tour content to speak
     * @return Flow emitting the current speaking status
     */
    fun speak(content: TourContent): Flow<SpeakingStatus>
    
    /**
     * Speak the provided text.
     *
     * @param text The text to speak
     * @return Flow emitting the current speaking status
     */
    fun speak(text: String): Flow<SpeakingStatus>

    /**
     * Speak a high-priority prompt (e.g., a navigation instruction).
     * Any ongoing narration is paused and automatically resumed from the
     * start of the interrupted sentence once the prompt finishes.
     *
     * @param text The prompt to speak
     * @return Flow emitting the prompt's speaking status
     */
    fun speakPriority(text: String): Flow<SpeakingStatus>

    /**
     * Pause the current speech.
     * 
     * @return True if successfully paused
     */
    fun pause(): Boolean
    
    /**
     * Resume paused speech.
     * 
     * @return True if successfully resumed
     */
    fun resume(): Boolean
    
    /**
     * Stop the current speech.
     */
    fun stop()
    
    /**
     * Check if the service is currently speaking.
     * 
     * @return True if speaking
     */
    fun isSpeaking(): Boolean
    
    /**
     * Update voice settings.
     * 
     * @param preferences User preferences with new voice settings
     */
    fun updateVoiceSettings(preferences: UserPreferences)
    
    /**
     * Release resources when no longer needed.
     */
    fun shutdown()
    
    /**
     * Status of the speaking operation.
     */
    enum class SpeakingStatus {
        STARTED,
        IN_PROGRESS,
        PAUSED,
        COMPLETED,
        ERROR
    }

    /**
     * Whether there is a usable voice, and what the user should know about it.
     */
    enum class VoiceAvailability {
        /** Nothing has tried to speak yet. */
        UNKNOWN,

        /** Speaking in the requested language. */
        READY,

        /**
         * Speaking, but the requested language wasn't installed, so this is
         * the device's default voice. Worth telling the user: they chose a
         * language and are not getting it.
         */
        USING_DEFAULT_VOICE,

        /**
         * The engine works but has no voice data to speak with. Recoverable
         * by the user — this is the case worth offering to fix, through
         * [android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA].
         */
        MISSING_VOICE_DATA,

        /** No text-to-speech engine started at all. */
        ENGINE_UNAVAILABLE;

        /** Whether the guide can speak at all. */
        val canSpeak: Boolean
            get() = this == READY || this == USING_DEFAULT_VOICE
    }
} 