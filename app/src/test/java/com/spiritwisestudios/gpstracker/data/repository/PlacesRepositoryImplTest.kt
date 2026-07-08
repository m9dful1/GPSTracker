package com.spiritwisestudios.gpstracker.data.repository

import com.google.android.gms.maps.model.LatLng
import com.spiritwisestudios.gpstracker.domain.model.PointOfInterest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacesRepositoryImplTest {

    private fun poi(id: String, placeId: String? = id) = PointOfInterest(
        id = id,
        name = "Place $id",
        latLng = LatLng(0.0, 0.0),
        address = "",
        category = "CULTURAL",
        rating = null,
        placeId = placeId
    )

    @Test
    fun `visited state is overlaid onto fresh results`() {
        val merged = PlacesRepositoryImpl.mergeVisitedState(
            listOf(poi("a"), poi("b"), poi("c")),
            visitedDates = mapOf("b" to 1_000L)
        )

        assertFalse(merged[0].isVisited)
        assertTrue(merged[1].isVisited)
        assertFalse(merged[2].isVisited)
    }

    @Test
    fun `visit timestamp rides along with the visited flag`() {
        val merged = PlacesRepositoryImpl.mergeVisitedState(
            listOf(poi("a"), poi("b")),
            visitedDates = mapOf("a" to 42_000L, "b" to null)
        )

        assertEquals(42_000L, merged[0].visitedDate)
        assertTrue(merged[1].isVisited)
        assertNull(merged[1].visitedDate)
    }

    @Test
    fun `matches on placeId as well as id`() {
        val merged = PlacesRepositoryImpl.mergeVisitedState(
            listOf(poi(id = "row-1", placeId = "google-place-1")),
            visitedDates = mapOf("google-place-1" to 7_000L)
        )

        assertTrue(merged.single().isVisited)
        assertEquals(7_000L, merged.single().visitedDate)
    }

    @Test
    fun `empty visited map leaves results untouched`() {
        val pois = listOf(poi("a"), poi("b"))
        assertEquals(pois, PlacesRepositoryImpl.mergeVisitedState(pois, emptyMap()))
    }

    @Test
    fun `order and size are preserved`() {
        val pois = listOf(poi("a"), poi("b"), poi("c"))
        val merged = PlacesRepositoryImpl.mergeVisitedState(
            pois,
            mapOf("a" to 1L, "c" to 2L)
        )

        assertEquals(pois.map { it.id }, merged.map { it.id })
    }
}
