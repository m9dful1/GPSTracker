package com.spiritwisestudios.gpstracker.data.service

import com.spiritwisestudios.gpstracker.domain.service.AudioService
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The interleavings between narration, navigation prompts, pause, resume and a
 * collector going away.
 *
 * None of this had a test before. `AudioServiceImplTest` covered the pure
 * helpers around it — where to resume a sentence from, how far through the
 * text the engine is, whether a language is usable — and the state machine
 * they serve was reachable only by driving with the sound on.
 */
class SpeechSessionTest {

    private var nextId = 0

    private fun channel(): SendChannel<AudioService.SpeakingStatus> =
        Channel(Channel.UNLIMITED)

    private fun SpeechSession.narrate(
        text: String = "The Neon Museum opened in 1996.",
        channel: SendChannel<AudioService.SpeakingStatus>? = null
    ): SendChannel<AudioService.SpeakingStatus> {
        val sink = channel ?: channel()
        startedSpeaking(SpeechSession.Utterance("u${nextId++}", text, sink, isPrompt = false))
        return sink
    }

    private fun SpeechSession.prompt(
        text: String = "Turn left in 200 feet",
        channel: SendChannel<AudioService.SpeakingStatus>? = null
    ): SendChannel<AudioService.SpeakingStatus> {
        val sink = channel ?: channel()
        startedSpeaking(SpeechSession.Utterance("u${nextId++}", text, sink, isPrompt = true))
        return sink
    }

    // --- C1: the defect this class was extracted to fix ---

    @Test
    fun `pausing during a prompt keeps the story, and drops the prompt`() {
        val session = SpeechSession()
        val story = session.narrate("The Neon Museum opened in 1996.")
        session.recordPosition(session.speaking!!.id, 12)

        // A turn comes up; the story is set aside
        val interruption = session.interruptForPrompt()
        assertSame(story, interruption.notifyPaused)
        val prompt = session.prompt()

        // The listener taps pause — or a call arrives and does it for them
        val paused = session.pause()

        assertTrue(paused.paused)
        // The prompt is what gets abandoned...
        assertSame(prompt, paused.close)
        assertNull(paused.notifyPaused)
        // ...and the story is still there, at the point it reached. This is
        // the bug: the story used to be overwritten by the prompt and its
        // channel dropped without ever being closed, which left the delivery
        // loop collecting it suspended forever.
        assertEquals("The Neon Museum opened in 1996.", session.parkedNarration?.text)
        assertEquals(12, session.parkedNarration?.position)
        assertSame(story, session.parkedNarration?.channel)
    }

    @Test
    fun `resuming after that pause picks the story back up`() {
        val session = SpeechSession()
        val story = session.narrate("The Neon Museum opened in 1996.")
        session.recordPosition(session.speaking!!.id, 12)
        session.interruptForPrompt()
        session.prompt()
        session.pause()

        val resumed = session.resume()

        assertSame(story, resumed?.channel)
        assertEquals(12, resumed?.position)
        assertFalse(session.isPaused)
        assertNull(session.parkedNarration)
    }

    // --- the ordinary paths ---

    @Test
    fun `a prompt hands the floor back when it finishes`() {
        val session = SpeechSession()
        val story = session.narrate()
        session.recordPosition(session.speaking!!.id, 30)
        session.interruptForPrompt()
        session.prompt()
        val promptId = session.speaking!!.id

        val finish = session.finish(promptId)

        assertEquals(promptId, finish.finished?.id)
        assertSame(story, finish.resume?.channel)
        assertEquals(30, finish.resume?.position)
    }

    @Test
    fun `a prompt that finishes while paused leaves the story for the listener`() {
        val session = SpeechSession()
        session.narrate()
        session.interruptForPrompt()
        session.prompt()
        val promptId = session.speaking!!.id
        // Paused mid-prompt, which drops the prompt but keeps the story
        session.pause()

        // The engine's callback for the dropped prompt still arrives
        val finish = session.finish(promptId)

        assertNull(finish.finished) // it is no longer current
        assertNull(finish.resume)
        // The story waits for an explicit resume rather than starting itself
        assertTrue(session.isPaused)
        assertTrue(session.parkedNarration != null)
    }

    @Test
    fun `a newer prompt replaces an older one and leaves the story alone`() {
        val session = SpeechSession()
        val story = session.narrate()
        session.interruptForPrompt()
        val firstPrompt = session.prompt("Turn left in 200 feet")

        val second = session.interruptForPrompt()

        assertSame(firstPrompt, second.close)
        assertNull(second.notifyPaused)
        assertSame(story, session.parkedNarration?.channel)
    }

    @Test
    fun `pausing a narration sets it aside and says so`() {
        val session = SpeechSession()
        val story = session.narrate()
        session.recordPosition(session.speaking!!.id, 5)

        val paused = session.pause()

        assertTrue(paused.paused)
        assertSame(story, paused.notifyPaused)
        assertNull(paused.close)
        assertEquals(5, session.parkedNarration?.position)
        assertFalse(session.isAudible)
    }

    @Test
    fun `a new narration supersedes what is playing and what is set aside`() {
        val session = SpeechSession()
        val story = session.narrate()
        session.interruptForPrompt()
        val prompt = session.prompt()

        val closing = session.supersede()

        assertEquals(2, closing.size)
        assertTrue(closing.contains(story))
        assertTrue(closing.contains(prompt))
        assertNull(session.parkedNarration)
        assertFalse(session.isPaused)
    }

    // --- the edges ---

    @Test
    fun `pausing twice, or with nothing playing, changes nothing`() {
        val session = SpeechSession()
        assertFalse(session.pause().paused)

        session.narrate()
        assertTrue(session.pause().paused)
        assertFalse(session.pause().paused)
    }

    @Test
    fun `resuming when nothing is paused does nothing`() {
        val session = SpeechSession()
        assertNull(session.resume())

        session.narrate()
        assertNull(session.resume())
    }

    @Test
    fun `a resume that cannot go ahead can be put back`() {
        // Audio focus was refused; the story must not be lost for it
        val session = SpeechSession()
        val story = session.narrate()
        session.pause()
        val toResume = session.resume()!!

        session.repark(toResume)

        assertTrue(session.isPaused)
        assertSame(story, session.parkedNarration?.channel)
    }

    @Test
    fun `only the owner of the current utterance stops the engine`() {
        val session = SpeechSession()
        val story = session.narrate()
        val stale = channel()

        assertFalse(session.abandon(stale))
        assertTrue(session.abandon(story))
        assertNull(session.speaking)
    }

    @Test
    fun `abandoning a set-aside story drops it without stopping the prompt`() {
        val session = SpeechSession()
        val story = session.narrate()
        session.interruptForPrompt()
        session.prompt()

        val stopsEngine = session.abandon(story)

        assertFalse(stopsEngine)
        assertNull(session.parkedNarration)
        // The prompt is still speaking
        assertTrue(session.speaking?.isPrompt == true)
    }

    @Test
    fun `position is only recorded for the utterance actually speaking`() {
        val session = SpeechSession()
        session.narrate()
        val speakingId = session.speaking!!.id

        assertNull(session.recordPosition("some-other-id", 99))
        assertEquals(speakingId, session.recordPosition(speakingId, 42)?.id)

        session.pause()
        assertEquals(42, session.parkedNarration?.position)
    }

    @Test
    fun `an engine that refuses to start drops that utterance only`() {
        val session = SpeechSession()
        session.narrate()
        val id = session.speaking!!.id

        session.failedToStart("a-different-id")
        assertEquals(id, session.speaking?.id)

        session.failedToStart(id)
        assertNull(session.speaking)
    }

    @Test
    fun `finishing something that is not current is ignored`() {
        val session = SpeechSession()
        session.narrate()
        session.narrate() // superseded by a second, without closing the first

        assertNull(session.finish("u0").finished)
    }

    @Test
    fun `clearing closes everything and forgets the position`() {
        val session = SpeechSession()
        val story = session.narrate()
        session.recordPosition(session.speaking!!.id, 17)
        session.interruptForPrompt()
        val prompt = session.prompt()

        val closing = session.clear()

        assertEquals(2, closing.size)
        assertTrue(closing.contains(story))
        assertTrue(closing.contains(prompt))
        assertNull(session.speaking)
        assertNull(session.parkedNarration)
        assertFalse(session.isPaused)
    }

    @Test
    fun `audible means speaking and not paused`() {
        val session = SpeechSession()
        assertFalse(session.isAudible)

        session.narrate()
        assertTrue(session.isAudible)

        session.pause()
        assertFalse(session.isAudible)
    }
}
