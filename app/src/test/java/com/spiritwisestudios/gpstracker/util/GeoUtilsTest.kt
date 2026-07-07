package com.spiritwisestudios.gpstracker.util

import com.google.android.gms.maps.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Test

class GeoUtilsTest {

    @Test
    fun `distance between identical points is zero`() {
        val point = LatLng(37.7749, -122.4194)
        assertEquals(0f, GeoUtils.distanceMeters(point, point), 0.01f)
    }

    @Test
    fun `distance of one degree longitude at equator is about 111 km`() {
        val start = LatLng(0.0, 0.0)
        val end = LatLng(0.0, 1.0)
        assertEquals(111_195f, GeoUtils.distanceMeters(start, end), 200f)
    }

    @Test
    fun `bearing due north is zero degrees`() {
        val start = LatLng(37.0, -122.0)
        val end = LatLng(38.0, -122.0)
        assertEquals(0f, GeoUtils.bearingDegrees(start, end), 0.5f)
    }

    @Test
    fun `bearing due east is about 90 degrees`() {
        val start = LatLng(0.0, 0.0)
        val end = LatLng(0.0, 1.0)
        assertEquals(90f, GeoUtils.bearingDegrees(start, end), 0.5f)
    }

    @Test
    fun `bearing due south is 180 degrees`() {
        val start = LatLng(38.0, -122.0)
        val end = LatLng(37.0, -122.0)
        assertEquals(180f, GeoUtils.bearingDegrees(start, end), 0.5f)
    }
}
