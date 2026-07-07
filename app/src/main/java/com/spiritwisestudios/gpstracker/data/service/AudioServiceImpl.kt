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
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlin.coroutines.resume

/**
 * TextToSpeech-based AudioService.
 *
 * A single persistent UtteranceProgressListener dispatches TTS callbacks to
 * the flow of whichever utterance is current (per-call listeners would clobber
 * each other). Navigation prompts via [speakPriority] pause tour narration and
 * resume it from the interrupted sentence when the prompt completes.
 */
class AudioServiceImpl @Inject constructor(
    private val context: Context
) : AudioService {

    private data class Utterance(
        val id: String,
        val text: String,
        val channel: SendChannel<AudioService.SpeakingStatus>?,
        val isPrompt: Boolean
    )

    private data class PendingNarration(
        val text: String,
        val position: Int,
        val channel: SendChannel<AudioService.SpeakingStatus>?
    )

    companion object {
        /**
         * Text to speak when resuming narration that was interrupted at
         * [position]: restarts from the beginning of the interrupted sentence.
         */
        internal fun resumeTextFrom(text: String, position: Int): String {
            val clamped = position.coerceIn(0, text.length)
            if (clamped == 0) return text

            val boundary = Regex("[.!?]\\s").findAll(text.substring(0, clamped))
                .lastOrNull()?.range?.last?.plus(1) ?: 0

            return text.substring(boundary).trim().ifEmpty { text }
        }
    }

    private var textToSpeech: TextToSpeech? = null
    private var isInitialized = false

    private val lock = Any()
    private var current: Utterance? = null
    private var pendingResume: PendingNarration? = null
    private var lastSpeakingPosition = 0
    private var isPaused = false

    // Audio Manager for handling audio focus
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false

    // Audio focus change listener
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                Timber.d("Audio focus lost permanently")
                hasAudioFocus = false
                stop()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Timber.d("Audio focus lost temporarily")
                hasAudioFocus = false
                pause()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Timber.d("Audio focus gained")
                hasAudioFocus = true
                if (isPaused) {
                    resume()
                }
            }
        }
    }

    // Single persistent listener; dispatches to the current utterance's flow
    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) {
            val channel = synchronized(lock) {
                current?.takeIf { it.id == utteranceId }?.channel
            }
            channel?.trySend(AudioService.SpeakingStatus.STARTED)
        }

        override fun onDone(utteranceId: String) {
            handleUtteranceFinished(utteranceId, error = false)
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String) {
            handleUtteranceFinished(utteranceId, error = true)
        }

        override fun onError(utteranceId: String, errorCode: Int) {
            handleUtteranceFinished(utteranceId, error = true)
        }

        override fun onRangeStart(utteranceId: String, start: Int, end: Int, frame: Int) {
            val channel = synchronized(lock) {
                if (current?.id == utteranceId) {
                    lastSpeakingPosition = start
                    current?.channel
                } else null
            }
            channel?.trySend(AudioService.SpeakingStatus.IN_PROGRESS)
        }

        override fun onStop(utteranceId: String, interrupted: Boolean) {
            // State transitions are handled where the interruption originates
            // (speak/speakPriority/pause/stop), nothing to do here.
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
                    textToSpeech?.setOnUtteranceProgressListener(progressListener)

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
        if (!isInitialized || textToSpeech == null || !requestAudioFocus()) {
            trySend(AudioService.SpeakingStatus.ERROR)
            close()
            return@callbackFlow
        }

        synchronized(lock) {
            // New narration replaces anything in flight, including a pending resume
            current?.channel?.close()
            pendingResume?.channel?.close()
            pendingResume = null
            isPaused = false
        }
        startUtterance(text, channel = this, isPrompt = false)

        awaitClose { onChannelClosed(this) }
    }

    override fun speakPriority(text: String): Flow<AudioService.SpeakingStatus> = callbackFlow {
        if (!isInitialized || textToSpeech == null || !requestAudioFocus()) {
            trySend(AudioService.SpeakingStatus.ERROR)
            close()
            return@callbackFlow
        }

        synchronized(lock) {
            val interrupted = current
            if (interrupted != null && !interrupted.isPrompt) {
                // Park the narration; it resumes when the prompt completes
                pendingResume = PendingNarration(interrupted.text, lastSpeakingPosition, interrupted.channel)
                interrupted.channel?.trySend(AudioService.SpeakingStatus.PAUSED)
                current = null
            } else if (interrupted != null) {
                // A newer prompt replaces an older one; keep any pending narration
                interrupted.channel?.close()
                current = null
            }
        }
        startUtterance(text, channel = this, isPrompt = true)

        awaitClose { onChannelClosed(this) }
    }

    override fun pause(): Boolean {
        synchronized(lock) {
            val active = current ?: return false
            if (isPaused) return false

            pendingResume = PendingNarration(active.text, lastSpeakingPosition, active.channel)
            current = null
            isPaused = true
            active.channel?.trySend(AudioService.SpeakingStatus.PAUSED)
        }
        textToSpeech?.stop()
        return true
    }

    override fun resume(): Boolean {
        val toResume = synchronized(lock) {
            if (!isPaused) return false
            pendingResume.also { pendingResume = null; isPaused = false }
        } ?: return false

        if (!hasAudioFocus && !requestAudioFocus()) {
            synchronized(lock) {
                pendingResume = toResume
                isPaused = true
            }
            return false
        }

        startUtterance(resumeTextFrom(toResume.text, toResume.position), toResume.channel, isPrompt = false)
        return true
    }

    override fun stop() {
        synchronized(lock) {
            current?.channel?.close()
            pendingResume?.channel?.close()
            current = null
            pendingResume = null
            isPaused = false
            lastSpeakingPosition = 0
        }
        textToSpeech?.stop()
        releaseAudioFocus()
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
        stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        isInitialized = false
    }

    /**
     * Begin speaking [text], flushing any current TTS output, and route
     * subsequent callbacks to [channel].
     */
    private fun startUtterance(
        text: String,
        channel: SendChannel<AudioService.SpeakingStatus>?,
        isPrompt: Boolean
    ) {
        val utteranceId = UUID.randomUUID().toString()
        synchronized(lock) {
            current = Utterance(utteranceId, text, channel, isPrompt)
            lastSpeakingPosition = 0
        }

        val bundle = Bundle()
        bundle.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)

        val result = textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, bundle, utteranceId)
        if (result == TextToSpeech.ERROR) {
            synchronized(lock) {
                if (current?.id == utteranceId) current = null
            }
            channel?.trySend(AudioService.SpeakingStatus.ERROR)
            channel?.close()
            releaseAudioFocus()
        }
    }

    private fun handleUtteranceFinished(utteranceId: String, error: Boolean) {
        val finished: Utterance
        var toResume: PendingNarration? = null

        synchronized(lock) {
            val active = current ?: return
            if (active.id != utteranceId) return
            finished = active
            current = null
            if (active.isPrompt && pendingResume != null && !isPaused) {
                toResume = pendingResume
                pendingResume = null
            }
        }

        val status = if (error) AudioService.SpeakingStatus.ERROR else AudioService.SpeakingStatus.COMPLETED
        finished.channel?.trySend(status)
        finished.channel?.close()

        val resume = toResume
        if (resume != null && resume.channel?.isClosedForSend != true) {
            // Resume the narration the prompt interrupted
            startUtterance(resumeTextFrom(resume.text, resume.position), resume.channel, isPrompt = false)
        } else {
            releaseAudioFocus()
        }
    }

    /**
     * Collector went away: stop speech only if this channel owns the current
     * utterance, and drop any pending resume bound to it.
     */
    private fun onChannelClosed(channel: SendChannel<AudioService.SpeakingStatus>) {
        var shouldStopTts = false
        synchronized(lock) {
            if (current?.channel === channel) {
                current = null
                shouldStopTts = true
            }
            if (pendingResume?.channel === channel) {
                pendingResume = null
            }
        }
        if (shouldStopTts) {
            textToSpeech?.stop()
            releaseAudioFocus()
        }
    }

    /**
     * Request audio focus for TTS playback.
     */
    private fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) return true

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
}
