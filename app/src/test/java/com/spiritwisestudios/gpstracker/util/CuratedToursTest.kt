package com.spiritwisestudios.gpstracker.util

import com.spiritwisestudios.gpstracker.domain.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CuratedToursTest {

    @Test
    fun `las vegas offers the strip and hoover dam, nearest first`() {
        val lasVegas = LatLng(36.17, -115.14)

        val nearby = CuratedTours.near(lasVegas)

        assertTrue(nearby.isNotEmpty())
        assertEquals("Las Vegas Strip", nearby.first().name)
        assertTrue(nearby.any { it.name == "Hoover Dam" })
        // Boston is a continent away
        assertTrue(nearby.none { it.name.contains("Freedom Trail") })
    }

    @Test
    fun `the middle of the ocean offers nothing`() {
        assertTrue(CuratedTours.near(LatLng(0.0, -140.0)).isEmpty())
    }

    @Test
    fun `every curated tour has a usable definition`() {
        CuratedTours.ALL.forEach { tour ->
            assertTrue(tour.name.isNotBlank())
            assertTrue(tour.center.latitude in -90.0..90.0)
            assertTrue(tour.center.longitude in -180.0..180.0)
        }
        // No duplicate names — they become one-tap buttons
        assertEquals(CuratedTours.ALL.size, CuratedTours.ALL.map { it.name }.toSet().size)
    }
}
