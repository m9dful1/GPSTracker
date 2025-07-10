package com.spiritwisestudios.gpstracker.domain.service

import com.spiritwisestudios.gpstracker.domain.model.TourContent
import com.spiritwisestudios.gpstracker.domain.model.UserPreferences
import kotlinx.coroutines.flow.Flow

/**
 * Service interface for text-to-speech and audio management functionality.
 */
interface AudioService {
    
    /**
     * Initialize the text-to-speech engine.
     * 
     * @param userPreferences User preferences for voice settings
     * @return True if initialization was successful
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
} 