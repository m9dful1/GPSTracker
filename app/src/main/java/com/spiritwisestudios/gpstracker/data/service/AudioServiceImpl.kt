package com.spiritwisestudios.gpstracker.data.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.spiritwisestudios.gpstracker.domain.model.TourContent
import com.spiritwisestudios.gpstracker.domain.model.UserPreferences
import com.spiritwisestudios.gpstracker.domain.service.AudioService
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlin.coroutines.resume

/**
 * Implementation of AudioService using Android's TextToSpeech.
 */
class AudioServiceImpl @Inject constructor(
    private val context: Context
) : AudioService {
    
    private var textToSpeech: TextToSpeech? = null
    private var isInitialized = false
    private var currentUtteranceId: String? = null
    private var isPaused = false
    private var lastSpokenText: String? = null
    private var lastSpeakingPosition: Int = 0
    
    // Audio Manager for handling audio focus
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    
    // Current speaking status flow
    private val _currentSpeakingStatus = MutableStateFlow<AudioService.SpeakingStatus?>(null)
    
    // Audio focus change listener
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // We've lost focus permanently - stop speaking
                Timber.d("Audio focus lost permanently")
                hasAudioFocus = false
                stopTTS()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // We've lost focus temporarily - pause speaking
                Timber.d("Audio focus lost temporarily")
                hasAudioFocus = false
                pauseTTS()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                // We've gained focus - resume if we were speaking previously
                Timber.d("Audio focus gained")
                hasAudioFocus = true
                if (isPaused) {
                    resumeTTS()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // We can duck (play at lower volume) - implement if needed
                Timber.d("Audio focus loss - can duck")
                // For TTS, ducking doesn't make much sense, so we pause
                hasAudioFocus = false
                pauseTTS()
            }
        }
    }
    
    override suspend fun initialize(userPreferences: UserPreferences): Boolean {
        // If already initialized, just update settings
        if (isInitialized) {
            updateVoiceSettings(userPreferences)
            return true
        }
        
        return suspendCancellableCoroutine { continuation ->
            textToSpeech = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    // Set language
                    val locale = Locale.forLanguageTag(userPreferences.voiceLanguage)
                    val result = textToSpeech?.setLanguage(locale)
                    
                    if (result == TextToSpeech.LANG_MISSING_DATA || 
                        result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Timber.e("Language not supported: ${userPreferences.voiceLanguage}")
                        continuation.resume(false)
                        return@TextToSpeech
                    }
                    
                    // Set speech rate and pitch
                    textToSpeech?.setSpeechRate(userPreferences.voiceSpeed)
                    textToSpeech?.setPitch(userPreferences.voicePitch)
                    
                    isInitialized = true
                    continuation.resume(true)
                } else {
                    Timber.e("TextToSpeech initialization failed with status: $status")
                    continuation.resume(false)
                }
            }
            
            continuation.invokeOnCancellation {
                shutdown()
            }
        }
    }
    
    override fun speak(content: TourContent): Flow<AudioService.SpeakingStatus> {
        return speak(content.content)
    }
    
    override fun speak(text: String): Flow<AudioService.SpeakingStatus> = callbackFlow {
        if (!isInitialized || textToSpeech == null) {
            trySend(AudioService.SpeakingStatus.ERROR)
            close()
            return@callbackFlow
        }
        
        // Save text for potential resumption later
        lastSpokenText = text
        lastSpeakingPosition = 0
        isPaused = false
        
        // Request audio focus before speaking
        if (!requestAudioFocus()) {
            trySend(AudioService.SpeakingStatus.ERROR)
            close()
            return@callbackFlow
        }
        
        // Generate a unique ID for this utterance
        val utteranceId = UUID.randomUUID().toString()
        currentUtteranceId = utteranceId
        
        // Set up progress listener
        textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {
                _currentSpeakingStatus.value = AudioService.SpeakingStatus.STARTED
                trySend(AudioService.SpeakingStatus.STARTED)
            }
            
            override fun onDone(utteranceId: String) {
                if (utteranceId == currentUtteranceId) {
                    _currentSpeakingStatus.value = AudioService.SpeakingStatus.COMPLETED
                    currentUtteranceId = null
                    lastSpokenText = null
                    lastSpeakingPosition = 0
                    isPaused = false
                    releaseAudioFocus()
                    trySend(AudioService.SpeakingStatus.COMPLETED)
                    close()
                }
            }
            
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) {
                if (utteranceId == currentUtteranceId) {
                    _currentSpeakingStatus.value = AudioService.SpeakingStatus.ERROR
                    currentUtteranceId = null
                    releaseAudioFocus()
                    trySend(AudioService.SpeakingStatus.ERROR)
                    close()
                }
            }
            
            // Added in API level 23
            override fun onError(utteranceId: String, errorCode: Int) {
                if (utteranceId == currentUtteranceId) {
                    _currentSpeakingStatus.value = AudioService.SpeakingStatus.ERROR
                    currentUtteranceId = null
                    releaseAudioFocus()
                    trySend(AudioService.SpeakingStatus.ERROR)
                    close()
                }
            }
            
            override fun onRangeStart(utteranceId: String, start: Int, end: Int, frame: Int) {
                if (utteranceId == currentUtteranceId) {
                    _currentSpeakingStatus.value = AudioService.SpeakingStatus.IN_PROGRESS
                    lastSpeakingPosition = start
                    trySend(AudioService.SpeakingStatus.IN_PROGRESS)
                }
            }
        })
        
        // Start speaking
        val bundle = Bundle()
        bundle.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        
        val result = textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, bundle, utteranceId)
        if (result == TextToSpeech.ERROR) {
            _currentSpeakingStatus.value = AudioService.SpeakingStatus.ERROR
            releaseAudioFocus()
            trySend(AudioService.SpeakingStatus.ERROR)
            close()
        }
        
        awaitClose {
            // Clean up when flow is closed
            if (currentUtteranceId == utteranceId) {
                textToSpeech?.stop()
                releaseAudioFocus()
                currentUtteranceId = null
            }
        }
    }
    
    override fun pause(): Boolean {
        if (!isPaused && textToSpeech != null && currentUtteranceId != null) {
            pauseTTS()
            return true
        }
        return false
    }
    
    override fun resume(): Boolean {
        if (isPaused && textToSpeech != null && lastSpokenText != null) {
            if (!hasAudioFocus && !requestAudioFocus()) {
                return false
            }
            resumeTTS()
            return true
        }
        return false
    }
    
    override fun stop() {
        stopTTS()
    }
    
    override fun isSpeaking(): Boolean {
        return isInitialized && textToSpeech?.isSpeaking == true
    }
    
    override fun updateVoiceSettings(preferences: UserPreferences) {
        if (!isInitialized || textToSpeech == null) return
        
        // Update language if needed
        val locale = Locale.forLanguageTag(preferences.voiceLanguage)
        textToSpeech?.setLanguage(locale)
        
        // Update speech rate and pitch
        textToSpeech?.setSpeechRate(preferences.voiceSpeed)
        textToSpeech?.setPitch(preferences.voicePitch)
    }
    
    override fun shutdown() {
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        releaseAudioFocus()
        isInitialized = false
        currentUtteranceId = null
        lastSpokenText = null
        lastSpeakingPosition = 0
        isPaused = false
    }
    
    /**
     * Request audio focus for TTS playback.
     */
    private fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) return true
        
        // Release any existing audio focus first
        releaseAudioFocus()
        
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // For Android 8.0+
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
                
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(audioAttributes)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .setWillPauseWhenDucked(true)
                .build()
                
            audioFocusRequest = focusRequest
            audioManager.requestAudioFocus(focusRequest)
        } else {
            // For Android 7.1 and below
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
        
        hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        return hasAudioFocus
    }
    
    /**
     * Release audio focus when done speaking.
     */
    private fun releaseAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
        hasAudioFocus = false
    }
    
    /**
     * Pause TTS playback.
     */
    private fun pauseTTS() {
        if (textToSpeech?.isSpeaking == true) {
            textToSpeech?.stop()
            isPaused = true
            _currentSpeakingStatus.value = AudioService.SpeakingStatus.PAUSED
        }
    }
    
    /**
     * Resume TTS playback.
     */
    private fun resumeTTS() {
        val textToResume = lastSpokenText ?: return
        
        // If we have a position to resume from, try to resume from that point
        // This is a simplistic approach - for a more sophisticated implementation, 
        // we would need to parse sentences and find a good starting point
        val positionToResume = if (lastSpeakingPosition > 0) {
            // Find the start of the current sentence or a reasonable point to resume
            val textToStart = textToResume.substring(lastSpeakingPosition)
            // If we're in the middle of a sentence, start from the beginning of that sentence
            textToResume.substring(0, lastSpeakingPosition) + textToStart
        } else {
            textToResume
        }
        
        val utteranceId = UUID.randomUUID().toString()
        currentUtteranceId = utteranceId
        
        val bundle = Bundle()
        bundle.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        
        textToSpeech?.speak(positionToResume, TextToSpeech.QUEUE_FLUSH, bundle, utteranceId)
        isPaused = false
        _currentSpeakingStatus.value = AudioService.SpeakingStatus.IN_PROGRESS
    }
    
    /**
     * Stop TTS playback completely.
     */
    private fun stopTTS() {
        textToSpeech?.stop()
        currentUtteranceId = null
        lastSpokenText = null
        lastSpeakingPosition = 0
        isPaused = false
        releaseAudioFocus()
        _currentSpeakingStatus.value = null
    }
} 