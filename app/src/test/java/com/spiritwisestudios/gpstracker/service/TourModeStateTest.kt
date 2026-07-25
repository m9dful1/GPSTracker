package com.spiritwisestudios.gpstracker.service

import com.spiritwisestudios.gpstracker.domain.model.LatLng
import com.spiritwisestudios.gpstracker.domain.model.PointOfInterest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tour state is what the UI binds to, so "is a tour running?" has to
 * answer the same way for every caller — the FAB, stop requests, and the
 * geofence revival path in `onStartCommand`.
 */
class TourModeStateTest {

    @Test
    fun `a starting tour counts as running`() {
        // Discovery and TTS setup take seconds. Reading this as "no tour"
        // is what made the FAB flip back to start right after the tap.
        assertTrue(TourModeService.TourModeState.Starting.isRunning)
    }

    @Test
    fun `an active tour counts as running`() {
        val state = TourModeService.TourModeState.Active(
            listOf(
                PointOfInterest(
                    id = "poi-1",
                    name = "Old Mill",
                    latLng = LatLng(45.0, -93.0),
                    address = "1 River Road",
                    category = "historic"
                )
            )
        )
        assertTrue(state.isRunning)
    }

    @Test
    fun `an active tour with nothing found yet still counts as running`() {
        assertTrue(TourModeService.TourModeState.Active(emptyList()).isRunning)
    }

    @Test
    fun `an inactive tour is not running`() {
        assertFalse(TourModeService.TourModeState.Inactive.isRunning)
    }

    @Test
    fun `a failed tour is not running`() {
        assertFalse(TourModeService.TourModeState.Error("no location").isRunning)
    }
}
