package com.spiritwisestudios.gpstracker.data.repository

import com.google.android.gms.maps.model.LatLng
import com.spiritwisestudios.gpstracker.domain.model.PointOfInterest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `visited ids are overlaid onto fresh results`() {
        val merged = PlacesRepositoryImpl.mergeVisitedState(
            listOf(poi("a"), poi("b"), poi("c")),
            visitedIds = setOf("b")
        )

        assertFalse(merged[0].isVisited)
        assertTrue(merged[1].isVisited)
        assertFalse(merged[2].isVisited)
    }

    @Test
    fun `matches on placeId as well as id`() {
        val merged = PlacesRepositoryImpl.mergeVisitedState(
            listOf(poi(id = "row-1", placeId = "google-place-1")),
            visitedIds = setOf("google-place-1")
        )

        assertTrue(merged.single().isVisited)
    }

    @Test
    fun `empty visited set leaves results untouched`() {
        val pois = listOf(poi("a"), poi("b"))
        assertEquals(pois, PlacesRepositoryImpl.mergeVisitedState(pois, emptySet()))
    }

    @Test
    fun `order and size are preserved`() {
        val pois = listOf(poi("a"), poi("b"), poi("c"))
        val merged = PlacesRepositoryImpl.mergeVisitedState(pois, setOf("a", "c"))

        assertEquals(pois.map { it.id }, merged.map { it.id })
    }
}
