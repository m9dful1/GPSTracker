package com.spiritwisestudios.gpstracker.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationCadenceTest {

    @Test
    fun `stationary user gets the relaxed cadence`() {
        assertEquals(
            LocationCadence.INTERVAL_STATIONARY_MS,
            LocationCadence.speedIntervalMs(0f)
        )
    }

    @Test
    fun `walking tightens the cadence`() {
        assertEquals(
            LocationCadence.INTERVAL_WALKING_MS,
            LocationCadence.speedIntervalMs(1.4f)
        )
    }

    @Test
    fun `city driving tightens further`() {
        assertEquals(
            LocationCadence.INTERVAL_DRIVING_MS,
            LocationCadence.speedIntervalMs(7f)
        )
    }

    @Test
    fun `fast driving gets the tightest cadence`() {
        assertEquals(
            LocationCadence.INTERVAL_FAST_MS,
            LocationCadence.speedIntervalMs(25f)
        )
    }

    @Test
    fun `cadence never loosens as speed rises`() {
        val intervals = listOf(0f, 1f, 3f, 8f, 15f, 30f)
            .map { LocationCadence.speedIntervalMs(it) }
        assertEquals(intervals, intervals.sortedDescending())
    }

    @Test
    fun `full battery imposes no floor`() {
        assertEquals(0L, LocationCadence.batteryFloorMs(100))
    }

    @Test
    fun `low battery floor overrides fast driving`() {
        assertEquals(
            LocationCadence.FLOOR_LOW_BATTERY_MS,
            LocationCadence.intervalMs(25f, batteryPercent = 10)
        )
    }

    @Test
    fun `medium battery floor caps the walking cadence`() {
        assertEquals(
            LocationCadence.FLOOR_MEDIUM_BATTERY_MS,
            LocationCadence.intervalMs(1.4f, batteryPercent = 40)
        )
    }

    @Test
    fun `healthy battery lets speed decide`() {
        assertEquals(
            LocationCadence.INTERVAL_FAST_MS,
            LocationCadence.intervalMs(25f, batteryPercent = 80)
        )
    }

    @Test
    fun `floor never makes the cadence faster`() {
        // Stationary at medium battery: speed already asks for 10 s,
        // the 10 s floor must not tighten it
        assertTrue(
            LocationCadence.intervalMs(0f, batteryPercent = 40) >=
                LocationCadence.speedIntervalMs(0f)
        )
    }
}
