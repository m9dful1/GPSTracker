package com.spiritwisestudios.gpstracker.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolylineTest {

    @Test
    fun `decodes Google's documented example polyline`() {
        // Example from the Google polyline encoding docs:
        // (38.5, -120.2), (40.7, -120.95), (43.252, -126.453)
        val points = Polyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")

        assertEquals(3, points.size)
        assertEquals(38.5, points[0].latitude, 1e-5)
        assertEquals(-120.2, points[0].longitude, 1e-5)
        assertEquals(40.7, points[1].latitude, 1e-5)
        assertEquals(-120.95, points[1].longitude, 1e-5)
        assertEquals(43.252, points[2].latitude, 1e-5)
        assertEquals(-126.453, points[2].longitude, 1e-5)
    }

    @Test
    fun `decodes empty string to empty list`() {
        assertTrue(Polyline.decode("").isEmpty())
    }
}
