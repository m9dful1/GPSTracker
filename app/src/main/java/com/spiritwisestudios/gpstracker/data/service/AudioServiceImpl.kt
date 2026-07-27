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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

        /**
         * Fraction of an utterance spoken so far, given the character index
         * the engine is currently speaking.
         */
        internal fun progressFraction(charIndex: Int, textLength: Int): Float {
            if (textLength <= 0) return 0f
            return (charIndex.toFloat() / textLength).coerceIn(0f, 1f)
        }

        /**
         * Whether a `setLanguage` result means the engine can actually speak
         * that language. Null means there was no engine to ask.
         */
        internal fun languageUsable(setLanguageResult: Int?): Boolean {
            return setLanguageResult != null &&
                setLanguageResult != TextToSpeech.LANG_MISSING_DATA &&
                setLanguageResult != TextToSpeech.LANG_NOT_SUPPORTED &&
                setLanguageResult != TextToSpeech.ERROR
        }
    }

    private var textToSpeech: TextToSpeech? = null
    private var isInitialized = false

    // Who is speaking, what was interrupted, and what happens next. Extracted
    // so the interleavings can be tested without an engine or a device.
    private val session = SpeechSession()

    private val _speechProgress = MutableStateFlow(0f)
    override val speechProgress: StateFlow<Float> = _speechProgress

    private val _voiceAvailability = MutableStateFlow(AudioService.VoiceAvailability.UNKNOWN)
    override val voiceAvailability: StateFlow<AudioService.VoiceAvailability> = _voiceAvailability

    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying

    /**
     * Republish whether anything is audibly playing. Called after every
     * transition — including the ones that come from outside, like audio focus
     * being taken by a phone call — so nothing has to infer it.
     */
    private fun publishPlaying() {
        _isPlaying.value = session.isAudible
    }

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
                if (session.isPaused) {
                    resume()
                }
            }
        }
    }

    // Single persistent listener; dispatches to the current utterance's flow
    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) {
            val started = session.speaking?.takeIf { it.id == utteranceId }
            if (started != null && !started.isPrompt) {
                _speechProgress.value = 0f
            }
            started?.channel?.trySend(AudioService.SpeakingStatus.STARTED)
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
            val speaking = session.recordPosition(utteranceId, start)
            if (speaking != null && !speaking.isPrompt) {
                _speechProgress.value = progressFraction(start, speaking.text.length)
            }
            speaking?.channel?.trySend(AudioService.SpeakingStatus.IN_PROGRESS)
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
                if (status != TextToSpeech.SUCCESS) {
                    Timber.e("TextToSpeech initialization failed with status: $status")
                    // Don't keep a half-built engine around: it can't speak,
                    // and holding it leaks the connection to the TTS service
                    discardEngine(AudioService.VoiceAvailability.ENGINE_UNAVAILABLE)
                    continuation.resume(false)
                    return@TextToSpeech
                }

                // Prefer the requested language; a device without it can still
                // guide in its own default voice, which is far better than a
                // silent tour
                val requested = Locale.forLanguageTag(userPreferences.voiceLanguage)
                val availability = when {
                    languageUsable(textToSpeech?.setLanguage(requested)) ->
                        AudioService.VoiceAvailability.READY

                    languageUsable(textToSpeech?.setLanguage(Locale.getDefault())) -> {
                        Timber.w(
                            "No voice for ${userPreferences.voiceLanguage}; " +
                                "falling back to ${Locale.getDefault().toLanguageTag()}"
                        )
                        AudioService.VoiceAvailability.USING_DEFAULT_VOICE
                    }

                    else -> {
                        Timber.e("No usable voice data on this device")
                        AudioService.VoiceAvailability.MISSING_VOICE_DATA
                    }
                }

                if (!availability.canSpeak) {
                    discardEngine(availability)
                    continuation.resume(false)
                    return@TextToSpeech
                }

                // Set speech rate and pitch
                textToSpeech?.setSpeechRate(userPreferences.voiceSpeed)
                textToSpeech?.setPitch(userPreferences.voicePitch)
                textToSpeech?.setOnUtteranceProgressListener(progressListener)

                isInitialized = true
                _voiceAvailability.value = availability
                continuation.resume(true)
            }

            continuation.invokeOnCancellation {
                shutdown()
            }
        }
    }

    /** Release an engine that can't be used, and record why. */
    private fun discardEngine(reason: AudioService.VoiceAvailability) {
        textToSpeech?.shutdown()
        textToSpeech = null
        isInitialized = false
        _voiceAvailability.value = reason
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

        // New narration replaces anything in flight, including a parked one
        session.supersede().forEach { it.close() }
        startUtterance(text, channel = this, isPrompt = false)

        awaitClose { onChannelClosed(this) }
    }

    override fun speakPriority(text: String): Flow<AudioService.SpeakingStatus> = callbackFlow {
        if (!isInitialized || textToSpeech == null || !requestAudioFocus()) {
            trySend(AudioService.SpeakingStatus.ERROR)
            close()
            return@callbackFlow
        }

        val interruption = session.interruptForPrompt()
        interruption.notifyPaused?.trySend(AudioService.SpeakingStatus.PAUSED)
        interruption.close?.close()
        startUtterance(text, channel = this, isPrompt = true)

        awaitClose { onChannelClosed(this) }
    }

    override fun pause(): Boolean {
        val outcome = session.pause()
        if (!outcome.paused) return false

        outcome.notifyPaused?.trySend(AudioService.SpeakingStatus.PAUSED)
        // A prompt is dropped rather than parked; see SpeechSession.pause
        outcome.close?.close()
        textToSpeech?.stop()
        publishPlaying()
        return true
    }

    override fun resume(): Boolean {
        val toResume = session.resume() ?: return false

        if (!hasAudioFocus && !requestAudioFocus()) {
            session.repark(toResume)
            publishPlaying()
            return false
        }

        startUtterance(resumeTextFrom(toResume.text, toResume.position), toResume.channel, isPrompt = false)
        return true
    }

    override fun stop() {
        session.clear().forEach { it.close() }
        _speechProgress.value = 0f
        textToSpeech?.stop()
        releaseAudioFocus()
        publishPlaying()
    }

    override fun isSpeaking(): Boolean {
        return isInitialized && textToSpeech?.isSpeaking == true
    }

    override fun updateVoiceSettings(preferences: UserPreferences) {
        if (!isInitialized || textToSpeech == null) return

        // Update language if needed, falling back the same way initialization
        // does — a language the device never had shouldn't mute a live tour
        val requested = Locale.forLanguageTag(preferences.voiceLanguage)
        _voiceAvailability.value = when {
            languageUsable(textToSpeech?.setLanguage(requested)) ->
                AudioService.VoiceAvailability.READY

            languageUsable(textToSpeech?.setLanguage(Locale.getDefault())) ->
                AudioService.VoiceAvailability.USING_DEFAULT_VOICE

            else -> {
                // A failed setLanguage leaves the engine on the voice it was
                // already using, so the guide still speaks — just not in the
                // language the user picked, which is what this reports.
                Timber.w("No voice for ${preferences.voiceLanguage}; keeping the current voice")
                AudioService.VoiceAvailability.USING_DEFAULT_VOICE
            }
        }

        // Update speech rate and pitch
        textToSpeech?.setSpeechRate(preferences.voiceSpeed)
        textToSpeech?.setPitch(preferences.voicePitch)
    }

    override fun shutdown() {
        stop()
        discardEngine(AudioService.VoiceAvailability.UNKNOWN)
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
        session.startedSpeaking(SpeechSession.Utterance(utteranceId, text, channel, isPrompt))

        val bundle = Bundle()
        bundle.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)

        val result = textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, bundle, utteranceId)
        if (result == TextToSpeech.ERROR) {
            session.failedToStart(utteranceId)
            channel?.trySend(AudioService.SpeakingStatus.ERROR)
            channel?.close()
            releaseAudioFocus()
        }
        publishPlaying()
    }

    private fun handleUtteranceFinished(utteranceId: String, error: Boolean) {
        val outcome = session.finish(utteranceId)
        val finished = outcome.finished ?: return

        publishPlaying()

        if (!finished.isPrompt) {
            _speechProgress.value = if (error) 0f else 1f
        }

        val status = if (error) AudioService.SpeakingStatus.ERROR else AudioService.SpeakingStatus.COMPLETED
        finished.channel?.trySend(status)
        finished.channel?.close()

        val resume = outcome.resume
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
        if (session.abandon(channel)) {
            textToSpeech?.stop()
            releaseAudioFocus()
        }
        publishPlaying()
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
