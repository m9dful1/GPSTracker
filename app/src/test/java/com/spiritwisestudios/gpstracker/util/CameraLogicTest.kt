package com.spiritwisestudios.gpstracker.util

import org.junit.Assert.assertEquals
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
}
