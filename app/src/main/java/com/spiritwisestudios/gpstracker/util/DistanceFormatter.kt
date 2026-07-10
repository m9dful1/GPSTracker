package com.spiritwisestudios.gpstracker.util

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Formats distances for display and speech. Distances stay in meters
 * everywhere internally (GPS, geofences, route data); only the presentation
 * converts, and the app presents imperial units by default.
 */
object DistanceFormatter {

    private const val FEET_PER_METER = 3.28084f
    private const val METERS_PER_MILE = 1609.344f

    /** Below this many feet distances read in feet; beyond it, miles. */
    private const val FEET_TO_MILES_CUTOVER = 1000f

    /** Compact form for on-screen labels: "450 ft", "0.9 mi", "12 mi". */
    fun format(distanceMeters: Float): String {
        val feet = distanceMeters * FEET_PER_METER
        if (feet < FEET_TO_MILES_CUTOVER) {
            val rounded = (feet / 10f).roundToInt() * 10
            return "$rounded ft"
        }
        val miles = distanceMeters / METERS_PER_MILE
        return if (miles < 10f) {
            String.format(Locale.US, "%.1f mi", miles)
        } else {
            "${miles.roundToInt()} mi"
        }
    }

    /**
     * Spoken form for voice prompts: "500 feet", "1 mile", "2.5 miles".
     * Feet round to 50 — GPS jitter makes finer precision fake.
     */
    fun spoken(distanceMeters: Float): String {
        val feet = distanceMeters * FEET_PER_METER
        if (feet < FEET_TO_MILES_CUTOVER) {
            val rounded = ((feet / 50f).roundToInt() * 50).coerceAtLeast(50)
            return "$rounded feet"
        }
        val tenthsOfMile = (distanceMeters / METERS_PER_MILE * 10f).roundToInt()
        return if (tenthsOfMile % 10 == 0) {
            val miles = tenthsOfMile / 10
            "$miles mile${if (miles == 1) "" else "s"}"
        } else {
            "${tenthsOfMile / 10.0} miles"
        }
    }
}
