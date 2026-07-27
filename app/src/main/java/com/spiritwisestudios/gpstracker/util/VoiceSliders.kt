package com.spiritwisestudios.gpstracker.util

/**
 * The settings sheet's voice speed and pitch sliders: a multiplier the speech
 * engine wants, and the integer position a `SeekBar` can show.
 *
 * The scale is chosen so the two directions agree. It used to be 20 steps
 * across 0.5–2.0 — a step of 0.075 — on which the app's own default of 1.0 is
 * not a position at all: it landed at 6.67, truncated to 6, and came back as
 * **0.95**. Anyone who opened settings, changed the notification distance and
 * pressed Save left with a guide speaking slower and lower than it had been
 * asked to, having touched neither slider.
 *
 * At 0.05 per step every value the app uses — 0.5, 1.0, 1.2, 1.5, 2.0 — is a
 * position, so a value that goes down to the slider and back is the same value.
 */
object VoiceSliders {

    /** Slowest/lowest the engine is asked for. */
    const val MIN = 0.5f

    /** Fastest/highest the engine is asked for. */
    const val MAX = 2.0f

    /** `android:max` for both sliders; 0.05 per step across [MIN]..[MAX]. */
    const val STEPS = 30

    // Integer hundredths, so a position maps to the nearest float to a round
    // decimal rather than accumulating a multiply's error
    private const val MIN_HUNDREDTHS = 50
    private const val STEP_HUNDREDTHS = 5

    /** The multiplier a slider position means. */
    fun valueFor(progress: Int): Float {
        val steps = progress.coerceIn(0, STEPS)
        return (MIN_HUNDREDTHS + steps * STEP_HUNDREDTHS) / 100f
    }

    /**
     * The slider position for a stored multiplier: **nearest**, not rounded
     * down. A value from an older install that sits between steps moves by at
     * most half a step rather than always losing a whole one.
     */
    fun progressFor(value: Float): Int {
        val hundredths = Math.round(value.coerceIn(MIN, MAX) * 100)
        return Math.round((hundredths - MIN_HUNDREDTHS) / STEP_HUNDREDTHS.toFloat())
            .coerceIn(0, STEPS)
    }
}
