package com.spiritwisestudios.gpstracker.domain.usecase

import com.spiritwisestudios.gpstracker.domain.model.TourContent
import com.spiritwisestudios.gpstracker.domain.model.UserPreferences
import com.spiritwisestudios.gpstracker.domain.service.AudioService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import javax.inject.Inject

/**
 * Use case for speaking content using text-to-speech.
 */
class SpeakContentUseCase @Inject constructor(
    private val audioService: AudioService
) {
    /**
     * Speak the provided tour content.
     *
     * @param content The tour content to speak
     * @param userPreferences User preferences for voice settings
     * @return Flow emitting the speaking status
     */
    operator fun invoke(
        content: TourContent,
        userPreferences: UserPreferences
    ): Flow<AudioService.SpeakingStatus> = flow {
        // Make sure text-to-speech is initialized with current preferences
        audioService.updateVoiceSettings(userPreferences)
        
        // If audio is disabled in preferences, don't speak
        if (!userPreferences.audioEnabled) {
            emit(AudioService.SpeakingStatus.COMPLETED)
            return@flow
        }
        
        // Choose what content to speak based on detail level preference
        val textToSpeak = when (userPreferences.contentDetailLevel) {
            UserPreferences.DetailLevel.BRIEF -> content.summary
            UserPreferences.DetailLevel.MEDIUM -> 
                "${content.title}. ${content.summary}"
            UserPreferences.DetailLevel.DETAILED -> 
                "${content.title}. ${content.content}"
        }
        
        // Emit all updates from the audio service
        emitAll(audioService.speak(textToSpeak))
    }
    
    /**
     * Stop the current speech.
     */
    fun stopSpeaking() {
        audioService.stop()
    }
    
    /**
     * Pause the current speech.
     * 
     * @return True if successfully paused
     */
    fun pauseSpeaking(): Boolean {
        return audioService.pause()
    }
    
    /**
     * Resume paused speech.
     * 
     * @return True if successfully resumed
     */
    fun resumeSpeaking(): Boolean {
        return audioService.resume()
    }
    
    /**
     * Check if currently speaking.
     * 
     * @return True if speaking
     */
    fun isSpeaking(): Boolean {
        return audioService.isSpeaking()
    }
} 