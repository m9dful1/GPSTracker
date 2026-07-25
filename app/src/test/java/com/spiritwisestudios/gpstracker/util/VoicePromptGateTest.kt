package com.spiritwisestudios.gpstracker.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePromptGateTest {

    @Test
    fun `a maneuver is spoken once`() {
        val gate = VoicePromptGate()

        assertTrue(gate.shouldSpeak("turn-left|IMMEDIATE"))
        assertFalse(gate.shouldSpeak("turn-left|IMMEDIATE"))
    }

    @Test
    fun `status updates during one turn do not repeat it`() {
        val gate = VoicePromptGate()
        gate.shouldSpeak("turn-left|IMMEDIATE")

        val repeats = (1..10).count { gate.shouldSpeak("turn-left|IMMEDIATE") }
        assertEquals(0, repeats)
    }

    @Test
    fun `the same turn is announced again at a closer distance`() {
        // "In half a mile, turn left" and then "turn left now" are two
        // prompts for one maneuver, and both are wanted.
        val gate = VoicePromptGate()

        assertTrue(gate.shouldSpeak("turn-left|APPROACHING"))
        assertTrue(gate.shouldSpeak("turn-left|IMMEDIATE"))
    }

    @Test
    fun `each maneuver along the route is spoken`() {
        val gate = VoicePromptGate()

        assertTrue(gate.shouldSpeak("turn-left|IMMEDIATE"))
        assertTrue(gate.shouldSpeak("turn-right|IMMEDIATE"))
        assertTrue(gate.shouldSpeak("merge|IMMEDIATE"))
    }

    @Test
    fun `arrival is spoken once even when it is reported differently`() {
        // The service reports arrival from the route's own final instruction
        // and from its own synthesized one; the driver should hear it once.
        val gate = VoicePromptGate()

        assertTrue(gate.shouldSpeak("arrive|at-destination", isArrival = true))
        assertFalse(gate.shouldSpeak("arrive|at-the-car|drifted", isArrival = true))
    }

    @Test
    fun `a turn after arrival is still spoken`() {
        // Arrival latches arrival, not the whole gate: an off-route
        // recalculation past the destination still needs its prompts.
        val gate = VoicePromptGate()
        gate.shouldSpeak("arrive|at-destination", isArrival = true)

        assertTrue(gate.shouldSpeak("turn-left|IMMEDIATE"))
    }

    @Test
    fun `the next drive can announce its arrival`() {
        val gate = VoicePromptGate()
        gate.shouldSpeak("arrive|at-destination", isArrival = true)

        gate.reset()
        assertTrue(gate.shouldSpeak("arrive|at-destination", isArrival = true))
    }

    @Test
    fun `the next drive can repeat the previous drive's turns`() {
        val gate = VoicePromptGate()
        gate.shouldSpeak("turn-left|IMMEDIATE")

        gate.reset()
        assertTrue(gate.shouldSpeak("turn-left|IMMEDIATE"))
    }
}
