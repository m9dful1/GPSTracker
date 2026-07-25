package com.spiritwisestudios.gpstracker.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TourCommandTest {

    @Test
    fun `a revival without an intent resumes the tour`() {
        // START_STICKY hands a killed service back with a null intent. It
        // used to match nothing, leaving a foreground notification with no
        // monitoring behind it.
        assertEquals(TourCommand.RESUME, TourCommand.forAction(null, isTourRunning = false))
    }

    @Test
    fun `a start request starts a tour`() {
        assertEquals(
            TourCommand.START,
            TourCommand.forAction(AppConstants.ACTION_START_TOUR_MODE, isTourRunning = false)
        )
    }

    @Test
    fun `a start request during a tour is still a start`() {
        // Idempotent at the service: it refreshes the notification and the
        // start itself is a no-op.
        assertEquals(
            TourCommand.START,
            TourCommand.forAction(AppConstants.ACTION_START_TOUR_MODE, isTourRunning = true)
        )
    }

    @Test
    fun `a geofence event is processed whether or not a tour is running`() {
        // Geofences only fire when a tour registered them, so this is the one
        // command that deliberately revives the service.
        assertEquals(
            TourCommand.GEOFENCE,
            TourCommand.forAction(AppConstants.ACTION_PROCESS_GEOFENCE, isTourRunning = false)
        )
        assertEquals(
            TourCommand.GEOFENCE,
            TourCommand.forAction(AppConstants.ACTION_PROCESS_GEOFENCE, isTourRunning = true)
        )
    }

    @Test
    fun `playback controls work on a running tour`() {
        assertEquals(
            TourCommand.PLAY_PAUSE,
            TourCommand.forAction(AppConstants.ACTION_PLAY_PAUSE, isTourRunning = true)
        )
        assertEquals(
            TourCommand.NEXT,
            TourCommand.forAction(AppConstants.ACTION_NEXT_POI, isTourRunning = true)
        )
    }

    @Test
    fun `playback controls for a finished tour are nothing to do`() {
        // The fact card's buttons can send these after the service has gone,
        // and the intent creates a fresh instance to receive them.
        assertEquals(
            TourCommand.NONE,
            TourCommand.forAction(AppConstants.ACTION_PLAY_PAUSE, isTourRunning = false)
        )
        assertEquals(
            TourCommand.NONE,
            TourCommand.forAction(AppConstants.ACTION_NEXT_POI, isTourRunning = false)
        )
    }

    @Test
    fun `stopping a running tour stops it`() {
        assertEquals(
            TourCommand.STOP,
            TourCommand.forAction(AppConstants.ACTION_STOP_TOUR_MODE, isTourRunning = true)
        )
    }

    @Test
    fun `stopping a tour that has already ended is nothing to do`() {
        assertEquals(
            TourCommand.NONE,
            TourCommand.forAction(AppConstants.ACTION_STOP_TOUR_MODE, isTourRunning = false)
        )
    }

    @Test
    fun `an unknown action is nothing to do`() {
        assertEquals(TourCommand.NONE, TourCommand.forAction("com.example.WHATEVER", isTourRunning = true))
    }
}
