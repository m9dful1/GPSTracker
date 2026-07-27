package com.spiritwisestudios.gpstracker.ui.map

import com.spiritwisestudios.gpstracker.domain.model.LatLng
import com.spiritwisestudios.gpstracker.util.CameraLogic
import com.spiritwisestudios.gpstracker.util.GeoUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which camera move a fix calls for.
 *
 * `CameraLogicTest` already covers the arithmetic — the zoom curve, the lead
 * distance, the driving gate's hysteresis. What had no test was the part that
 * chose between them, because it was four callbacks in `MainActivity` and the
 * only way to ask it anything was to drive.
 */
class CameraDirectorTest {

    private val here = LatLng(39.5, -119.8)
    private val alongTheRoad = LatLng(39.51, -119.8)

    private val walking = 1.5f
    private val driving = 15f // ~54 km/h, well past the driving gate

    /** Past the first fix, which is a move of its own. */
    private fun placed(): CameraDirector = CameraDirector().apply {
        onLocation(0f, null)
        moveFor(here, navigating = false)
    }

    @Test
    fun `the first fix places the map, whatever else is going on`() {
        val camera = CameraDirector()
        camera.onLocation(driving, 90f)

        val move = camera.moveFor(here, navigating = true)

        assertEquals(CameraDirector.Move.FirstFix(here), move)
        assertFalse(camera.awaitingFirstMove)
    }

    @Test
    fun `a standing start just follows`() {
        val camera = placed()
        camera.onLocation(0f, null)

        assertEquals(CameraDirector.Move.Follow(alongTheRoad), camera.moveFor(alongTheRoad, false))
    }

    @Test
    fun `walking pace is not driving`() {
        val camera = placed()
        camera.onLocation(walking, 90f)

        assertTrue(camera.moveFor(alongTheRoad, false) is CameraDirector.Move.Follow)
        assertFalse(camera.isDriving)
    }

    @Test
    fun `driving speed turns the view heading-up, ahead of the fix`() {
        val camera = placed()
        camera.onLocation(driving, bearing = 90f)

        val move = camera.moveFor(alongTheRoad, navigating = false)

        assertTrue(move is CameraDirector.Move.Driving)
        move as CameraDirector.Move.Driving
        assertEquals(90f, move.bearing, 1e-4f)
        assertEquals(CameraLogic.zoomForSpeed(driving), move.zoom, 1e-4f)
        // Aimed one ease of travel down the road, not at the fix itself
        val lead = CameraLogic.cameraLeadMeters(driving)
        assertEquals(
            lead.toDouble(),
            GeoUtils.distanceMeters(alongTheRoad, move.target).toDouble(),
            1.0
        )
    }

    @Test
    fun `a car that comes to rest settles the view back flat, once`() {
        val camera = placed()
        camera.onLocation(driving, 90f)
        camera.moveFor(alongTheRoad, false)

        // The gate holds through a red light rather than flattening at once
        repeat(CameraLogic.STILL_FIXES_TO_RELEASE) { camera.onLocation(0f, null) }

        assertEquals(CameraDirector.Move.TopDown(here), camera.moveFor(here, false))
        // And having settled, it stays settled instead of re-animating
        camera.onLocation(0f, null)
        assertTrue(camera.moveFor(here, false) is CameraDirector.Move.Follow)
    }

    @Test
    fun `a short stop keeps the driving view`() {
        val camera = placed()
        camera.onLocation(driving, 90f)
        camera.moveFor(alongTheRoad, false)

        repeat(CameraLogic.STILL_FIXES_TO_RELEASE - 1) { camera.onLocation(0f, null) }

        assertTrue(camera.moveFor(here, false) is CameraDirector.Move.Driving)
    }

    // --- who owns the camera ---

    @Test
    fun `panning away hands the camera over until it is asked for back`() {
        val camera = placed()
        camera.onLocation(driving, 90f)

        camera.onUserGesture()
        assertEquals(CameraDirector.Move.None, camera.moveFor(alongTheRoad, false))

        val move = camera.recenter(alongTheRoad, navigating = false)

        assertTrue(camera.isFollowingUser)
        // Driving, so recentering goes back to heading-up
        assertTrue(move is CameraDirector.Move.Driving)
    }

    @Test
    fun `recentering while parked and not navigating goes flat`() {
        val camera = placed()
        camera.onUserGesture()
        camera.onLocation(0f, null)

        assertEquals(
            CameraDirector.Move.TopDown(here),
            camera.recenter(here, navigating = false)
        )
    }

    @Test
    fun `recentering during guidance goes heading-up even when stopped`() {
        // Stopped at a light mid-route is still a drive
        val camera = placed()
        camera.onUserGesture()
        camera.onLocation(0f, null)

        assertTrue(camera.recenter(here, navigating = true) is CameraDirector.Move.Driving)
    }

    @Test
    fun `navigation owns the camera, so a plain fix does nothing`() {
        val camera = placed()
        camera.onLocation(driving, 90f)

        assertEquals(CameraDirector.Move.None, camera.moveFor(alongTheRoad, navigating = true))
        // Guidance moves it explicitly instead
        assertTrue(camera.navigationMove(alongTheRoad) is CameraDirector.Move.Driving)
    }

    @Test
    fun `following again does not move the camera by itself`() {
        val camera = placed()
        camera.onUserGesture()
        camera.followAgain()

        assertTrue(camera.isFollowingUser)
        camera.onLocation(0f, null)
        assertTrue(camera.moveFor(here, false) is CameraDirector.Move.Follow)
    }

    // --- bearing ---

    @Test
    fun `a bearing from a standstill is noise and is not kept`() {
        val camera = placed()
        // A parked phone reports a course that means nothing
        camera.onLocation(0.5f, bearing = 270f)
        camera.onLocation(driving, bearing = null)

        val move = camera.moveFor(alongTheRoad, false) as CameraDirector.Move.Driving

        assertEquals(0f, move.bearing, 1e-4f) // no history yet either
    }

    @Test
    fun `without a GPS course the bearing comes from where the driver has been`() {
        val camera = placed()
        camera.onLocation(driving, bearing = null)

        // Two driving fixes north; the second knows which way that was
        camera.moveFor(here, false)
        val move = camera.moveFor(alongTheRoad, false) as CameraDirector.Move.Driving

        assertEquals(
            GeoUtils.bearingDegrees(here, alongTheRoad),
            move.bearing,
            1f
        )
    }

    @Test
    fun `a drive ending forgets the trail`() {
        val camera = placed()
        camera.onLocation(driving, bearing = null)
        camera.moveFor(here, false)
        camera.moveFor(alongTheRoad, false)

        camera.onNavigationEnded()

        // Nothing to derive a heading from any more, so it starts over at 0
        val move = camera.moveFor(alongTheRoad, false) as CameraDirector.Move.Driving
        assertEquals(0f, move.bearing, 1e-4f)
    }

    @Test
    fun `a route overview leaves the view flat, so no settling is owed`() {
        val camera = placed()
        camera.onLocation(driving, 90f)
        camera.moveFor(alongTheRoad, false) // tilted

        camera.onRouteOverview()
        repeat(CameraLogic.STILL_FIXES_TO_RELEASE) { camera.onLocation(0f, null) }

        // Already flat: a plain follow, not another animation back to flat
        assertTrue(camera.moveFor(here, false) is CameraDirector.Move.Follow)
    }
}
