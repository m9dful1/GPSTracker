package com.spiritwisestudios.gpstracker.util

import com.spiritwisestudios.gpstracker.domain.model.UserPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The voice sliders' two directions, which have to agree.
 *
 * They didn't: the scale was 20 steps across 0.5–2.0, so the app's own default
 * speed of 1.0 sat at position 6.67, truncated to 6, and came back 0.95. Saving
 * the settings sheet changed a value nobody had touched. These were four
 * private functions inside a fragment, which is why nothing could ask.
 */
class VoiceSlidersTest {

    @Test
    fun `the app's own defaults survive a round trip`() {
        // The case that was broken. A default install that opens settings and
        // saves must come back out with the speed and pitch it went in with.
        val defaults = UserPreferences()

        assertEquals(
            defaults.voiceSpeed,
            VoiceSliders.valueFor(VoiceSliders.progressFor(defaults.voiceSpeed)),
            1e-6f
        )
        assertEquals(
            defaults.voicePitch,
            VoiceSliders.valueFor(VoiceSliders.progressFor(defaults.voicePitch)),
            1e-6f
        )
    }

    @Test
    fun `every value a user can set survives a round trip`() {
        for (progress in 0..VoiceSliders.STEPS) {
            val value = VoiceSliders.valueFor(progress)
            assertEquals(
                "position $progress became $value and then moved",
                progress,
                VoiceSliders.progressFor(value)
            )
        }
    }

    @Test
    fun `the round numbers people expect are all positions on the scale`() {
        // 0.05 per step is chosen for exactly this
        for (value in listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)) {
            assertEquals(
                "$value is not on the scale",
                value,
                VoiceSliders.valueFor(VoiceSliders.progressFor(value)),
                1e-6f
            )
        }
    }

    @Test
    fun `the ends of the scale are the ends of the range`() {
        assertEquals(VoiceSliders.MIN, VoiceSliders.valueFor(0), 1e-6f)
        assertEquals(VoiceSliders.MAX, VoiceSliders.valueFor(VoiceSliders.STEPS), 1e-6f)
    }

    @Test
    fun `a value between steps moves to the nearer one, not always down`() {
        // What an install from the old 0.075 scale has stored. Truncating cost
        // a whole step every time; nearest costs at most half of one. The steps
        // here are 0.95 and 1.00, so 0.98 belongs to the upper and 0.96 to the
        // lower — 0.97 would be a coin toss and is not what this pins.
        assertEquals(VoiceSliders.progressFor(1.0f), VoiceSliders.progressFor(0.98f))
        assertEquals(VoiceSliders.progressFor(0.95f), VoiceSliders.progressFor(0.96f))
    }

    @Test
    fun `every value the sheet can store is one a Material Slider accepts`() {
        // The F1 crash. A Material Slider throws IllegalStateException unless
        // the value is valueFrom plus a whole number of steps, and the
        // place-details dialog hands it whatever is stored. Both ends use this
        // scale now, so every position must satisfy that rule exactly.
        for (progress in 0..VoiceSliders.STEPS) {
            val value = VoiceSliders.valueFor(progress)
            val steps = (value - VoiceSliders.MIN) / VoiceSliders.STEP
            assertEquals(
                "$value is not valueFrom plus a whole number of steps",
                Math.round(steps).toFloat(),
                steps,
                1e-4f
            )
        }
    }

    @Test
    fun `anything stored is put on the grid before a Slider sees it`() {
        // Including values no slider produced: an older install's 0.075 scale,
        // a restored preferences file, a hand-edited one
        for (stored in listOf(0.95f, 1.025f, 1.175f, 1.475f, 0.1f, 7f, 1.37f)) {
            val onGrid = VoiceSliders.onGrid(stored)
            val steps = (onGrid - VoiceSliders.MIN) / VoiceSliders.STEP
            assertEquals(
                "$stored became $onGrid, which is off the grid",
                Math.round(steps).toFloat(),
                steps,
                1e-4f
            )
            assertEquals(onGrid, VoiceSliders.valueFor(VoiceSliders.progressFor(onGrid)), 1e-6f)
        }
    }

    @Test
    fun `a value already on the grid is left alone`() {
        for (progress in 0..VoiceSliders.STEPS) {
            val value = VoiceSliders.valueFor(progress)
            assertEquals(value, VoiceSliders.onGrid(value), 1e-6f)
        }
    }

    @Test
    fun `values outside the range are clamped rather than escaping it`() {
        assertEquals(0, VoiceSliders.progressFor(0.1f))
        assertEquals(VoiceSliders.STEPS, VoiceSliders.progressFor(9f))
        assertEquals(VoiceSliders.MIN, VoiceSliders.valueFor(-5), 1e-6f)
        assertEquals(VoiceSliders.MAX, VoiceSliders.valueFor(999), 1e-6f)
    }
}
