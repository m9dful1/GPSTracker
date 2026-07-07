package com.spiritwisestudios.gpstracker.util

import com.google.android.gms.maps.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteSamplerTest {

    @Test
    fun `empty route yields no samples`() {
        assertTrue(RouteSampler.samplePoints(emptyList(), 1000f).isEmpty())
    }

    @Test
    fun `single point route yields that point`() {
        val point = LatLng(1.0, 1.0)
        assertEquals(listOf(point), RouteSampler.samplePoints(listOf(point), 1000f))
    }

    @Test
    fun `always includes first and last points`() {
        // ~111 km long straight route
        val route = listOf(LatLng(0.0, 0.0), LatLng(0.5, 0.0), LatLng(1.0, 0.0))
        val samples = RouteSampler.samplePoints(route, 10_000f)

        assertEquals(route.first(), samples.first())
        assertEquals(route.last(), samples.last())
    }

    @Test
    fun `dense route is thinned to roughly the requested interval`() {
        // 100 points spaced ~1.1 km apart along a meridian
        val route = (0..100).map { LatLng(it * 0.01, 0.0) }
        val samples = RouteSampler.samplePoints(route, 11_000f)

        // ~111 km of route at ~11 km spacing → about 10 samples, not 100
        assertTrue("expected ~10 samples, got ${samples.size}", samples.size in 8..14)
    }

    @Test
    fun `short route yields just endpoints`() {
        val route = listOf(LatLng(0.0, 0.0), LatLng(0.001, 0.0)) // ~111 m
        val samples = RouteSampler.samplePoints(route, 1000f)

        assertEquals(2, samples.size)
    }
}
