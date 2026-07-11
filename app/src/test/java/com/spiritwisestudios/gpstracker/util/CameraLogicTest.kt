package com.spiritwisestudios.gpstracker.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraLogicTest {

    @Test
    fun `stationary user gets the close-up zoom`() {
        assertEquals(CameraLogic.MAX_ZOOM, CameraLogic.zoomForSpeed(0f))
    }

    @Test
    fun `highway speed gets the widest zoom`() {
        // 30 m/s = 108 km/h — past the 100 km/h cap
        assertEquals(CameraLogic.MIN_ZOOM, CameraLogic.zoomForSpeed(30f))
    }

    @Test
    fun `city driving sits near the old fixed zoom`() {
        // 10 m/s = 36 km/h; the pre-adaptive camera used a fixed 17f
        val zoom = CameraLogic.zoomForSpeed(10f)
        assertTrue("zoom was $zoom", zoom > 16.5f && zoom < 17.5f)
    }

    @Test
    fun `zoom never increases with speed`() {
        val zooms = listOf(0f, 2f, 5f, 10f, 20f, 30f, 50f).map {
            CameraLogic.zoomForSpeed(it)
        }
        assertEquals(zooms, zooms.sortedDescending())
    }

    @Test
    fun `negative GPS speed is treated as stationary`() {
        assertEquals(CameraLogic.MAX_ZOOM, CameraLogic.zoomForSpeed(-3f))
    }

    @Test
    fun `camera lead covers one ease-worth of travel`() {
        // 13 m/s over the 1 s ease → aim 13 m ahead
        assertEquals(13f, CameraLogic.cameraLeadMeters(13f), 0.01f)
    }

    @Test
    fun `camera lead is zero when stationary`() {
        assertEquals(0f, CameraLogic.cameraLeadMeters(0f), 0f)
    }

    @Test
    fun `camera lead ignores negative GPS speed`() {
        assertEquals(0f, CameraLogic.cameraLeadMeters(-5f), 0f)
    }

    @Test
    fun `camera lead is capped for implausible speeds`() {
        assertEquals(
            CameraLogic.MAX_CAMERA_LEAD_METERS,
            CameraLogic.cameraLeadMeters(500f),
            0f
        )
    }

    @Test
    fun `driving view engages at driving speed`() {
        val gate = CameraLogic.DrivingCameraGate()
        assertTrue(gate.onSpeed(10f))
    }

    @Test
    fun `walking never engages the driving view`() {
        val gate = CameraLogic.DrivingCameraGate()
        repeat(100) {
            assertFalse(gate.onSpeed(1.4f)) // brisk walk
        }
    }

    @Test
    fun `a short red light keeps the driving view`() {
        val gate = CameraLogic.DrivingCameraGate()
        gate.onSpeed(15f)
        repeat(CameraLogic.STILL_FIXES_TO_RELEASE - 1) {
            assertTrue(gate.onSpeed(0f))
        }
        // Light turns green before the release threshold
        assertTrue(gate.onSpeed(15f))
    }

    @Test
    fun `a sustained stop releases the driving view`() {
        val gate = CameraLogic.DrivingCameraGate()
        gate.onSpeed(15f)
        repeat(CameraLogic.STILL_FIXES_TO_RELEASE) {
            gate.onSpeed(0f)
        }
        assertFalse(gate.isDriving)
    }

    @Test
    fun `moving again keeps resetting the release countdown`() {
        val gate = CameraLogic.DrivingCameraGate()
        gate.onSpeed(15f)
        repeat(3) {
            repeat(CameraLogic.STILL_FIXES_TO_RELEASE - 1) {
                gate.onSpeed(0f)
            }
            gate.onSpeed(15f) // creep forward in traffic
        }
        assertTrue(gate.isDriving)
    }

    @Test
    fun `reset releases the driving view immediately`() {
        val gate = CameraLogic.DrivingCameraGate()
        gate.onSpeed(15f)
        gate.reset()
        assertFalse(gate.isDriving)
    }
}
