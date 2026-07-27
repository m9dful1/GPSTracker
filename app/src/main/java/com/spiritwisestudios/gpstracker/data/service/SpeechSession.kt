package com.spiritwisestudios.gpstracker.data.service

import com.spiritwisestudios.gpstracker.domain.service.AudioService
import kotlinx.coroutines.channels.SendChannel

/**
 * Who is speaking, what was interrupted, and what happens when the current
 * utterance ends.
 *
 * This is the bookkeeping [AudioServiceImpl] used to keep in four fields
 * behind one lock. It is here, on its own, for the reason [TourSession] is:
 * the rules are not about Android — a `TextToSpeech` engine and an audio-focus
 * request are the only parts that are — and the interleavings between
 * narration, navigation prompts, pause, resume and a collector going away are
 * where the bugs live.
 *
 * Every method is atomic, and every one that has consequences outside the lock
 * *returns* them rather than performing them: channels to close, statuses to
 * send, an utterance to start. The caller does that work after the lock is
 * released.
 */
class SpeechSession {

    /** Something being spoken, and the flow listening to it. */
    data class Utterance(
        val id: String,
        val text: String,
        val channel: SendChannel<AudioService.SpeakingStatus>?,
        val isPrompt: Boolean
    )

    /** A narration set aside, to be picked up from [position]. */
    data class Parked(
        val text: String,
        val position: Int,
        val channel: SendChannel<AudioService.SpeakingStatus>?
    )

    private val lock = Any()

    private var current: Utterance? = null
    private var parked: Parked? = null
    private var position = 0
    private var paused = false

    /** The utterance the engine is working on, if any. */
    val speaking: Utterance?
        get() = synchronized(lock) { current }

    /** Whether anything is audibly playing — false while paused. */
    val isAudible: Boolean
        get() = synchronized(lock) { current != null && !paused }

    val isPaused: Boolean
        get() = synchronized(lock) { paused }

    /** The narration waiting to be picked up, if any. For tests and asserts. */
    val parkedNarration: Parked?
        get() = synchronized(lock) { parked }

    /** The utterance [id] is the current one — the engine's callbacks use it. */
    fun isCurrent(id: String): Boolean = synchronized(lock) { current?.id == id }

    /**
     * A new narration supersedes everything: whatever is speaking and whatever
     * was set aside are both over.
     *
     * @return the channels the caller must close.
     */
    fun supersede(): List<SendChannel<AudioService.SpeakingStatus>> = synchronized(lock) {
        val closing = listOfNotNull(current?.channel, parked?.channel)
        parked = null
        paused = false
        closing
    }

    /** What [interruptForPrompt] leaves for the caller to do. */
    data class PromptInterruption(
        /** A narration told it is pausing, so its collector can show that. */
        val notifyPaused: SendChannel<AudioService.SpeakingStatus>? = null,
        /** An older prompt, superseded by this one. */
        val close: SendChannel<AudioService.SpeakingStatus>? = null
    )

    /**
     * A navigation prompt takes the floor. A narration under it is set aside
     * to be picked up afterwards; an older prompt is simply replaced.
     */
    fun interruptForPrompt(): PromptInterruption = synchronized(lock) {
        val interrupted = current ?: return PromptInterruption()
        current = null

        if (!interrupted.isPrompt) {
            parked = Parked(interrupted.text, position, interrupted.channel)
            PromptInterruption(notifyPaused = interrupted.channel)
        } else {
            // A newer prompt replaces an older one, and whatever narration is
            // already set aside stays set aside
            PromptInterruption(close = interrupted.channel)
        }
    }

    /** The engine has been asked to speak [utterance]. */
    fun startedSpeaking(utterance: Utterance) = synchronized(lock) {
        current = utterance
        position = 0
    }

    /** The engine refused [id]; drop it if it is still the current one. */
    fun failedToStart(id: String) = synchronized(lock) {
        if (current?.id == id) current = null
    }

    /** The engine reports it is speaking the character at [charIndex]. */
    fun recordPosition(id: String, charIndex: Int): Utterance? = synchronized(lock) {
        if (current?.id != id) return null
        position = charIndex
        current
    }

    /** What [pause] leaves for the caller to do. */
    data class Pause(
        /** Nothing was playing; there was nothing to pause. */
        val paused: Boolean,
        /** Told it is pausing — a narration that will be picked up again. */
        val notifyPaused: SendChannel<AudioService.SpeakingStatus>? = null,
        /** Abandoned outright — a prompt, whose tail is not worth keeping. */
        val close: SendChannel<AudioService.SpeakingStatus>? = null
    )

    /**
     * Stop speaking, and be ready to pick up where the *narration* left off.
     *
     * A prompt on top of a narration is dropped rather than set aside: this
     * used to overwrite the narration parked underneath it, which left the
     * story's flow referenced by nothing and never closed — so the delivery
     * loop collecting it never returned, never released the delivery gate, and
     * the guide went quiet for the rest of the tour. Losing the tail of "turn
     * left in 200 feet" is a much smaller thing than losing the story, and a
     * phone call arriving mid-prompt used to be enough to do it.
     */
    fun pause(): Pause = synchronized(lock) {
        val active = current ?: return Pause(paused = false)
        if (paused) return Pause(paused = false)

        current = null
        paused = true

        if (active.isPrompt) {
            Pause(paused = true, close = active.channel)
        } else {
            parked = Parked(active.text, position, active.channel)
            Pause(paused = true, notifyPaused = active.channel)
        }
    }

    /**
     * Pick the set-aside narration back up, or null when nothing is paused.
     * The caller decides whether it can actually speak (audio focus) and calls
     * [repark] if it cannot.
     */
    fun resume(): Parked? = synchronized(lock) {
        if (!paused) return null
        paused = false
        parked.also { parked = null }
    }

    /** [resume] could not go ahead after all; put it back. */
    fun repark(narration: Parked) = synchronized(lock) {
        parked = narration
        paused = true
    }

    /** What [finish] leaves for the caller to do. */
    data class Finish(
        /** The utterance that ended, or null if it was not the current one. */
        val finished: Utterance? = null,
        /** A narration to pick up now that the prompt over it has ended. */
        val resume: Parked? = null
    )

    /**
     * The engine finished (or failed) the utterance [id]. A narration set
     * aside under a prompt is picked up here — unless the listener paused in
     * the meantime, in which case it stays set aside for them to resume.
     */
    fun finish(id: String): Finish = synchronized(lock) {
        val active = current ?: return Finish()
        if (active.id != id) return Finish()

        current = null
        val resume = if (active.isPrompt && !paused) {
            parked.also { parked = null }
        } else {
            null
        }
        Finish(finished = active, resume = resume)
    }

    /**
     * A collector went away. Only the owner of the current utterance stops the
     * engine; anyone else is a flow that has already been superseded.
     *
     * @return whether the engine should be stopped.
     */
    fun abandon(channel: SendChannel<AudioService.SpeakingStatus>): Boolean = synchronized(lock) {
        var ownsCurrent = false
        if (current?.channel === channel) {
            current = null
            ownsCurrent = true
        }
        if (parked?.channel === channel) {
            parked = null
        }
        ownsCurrent
    }

    /**
     * Everything stops and nothing is picked up later.
     *
     * @return the channels the caller must close.
     */
    fun clear(): List<SendChannel<AudioService.SpeakingStatus>> = synchronized(lock) {
        val closing = listOfNotNull(current?.channel, parked?.channel)
        current = null
        parked = null
        paused = false
        position = 0
        closing
    }
}
