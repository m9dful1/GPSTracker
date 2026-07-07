package com.spiritwisestudios.gpstracker.util

import com.google.android.gms.maps.model.LatLng
import com.spiritwisestudios.gpstracker.domain.model.PointOfInterest
import com.spiritwisestudios.gpstracker.domain.model.UserPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TourLogicTest {

    private fun poi(
        category: String = "CULTURAL",
        rating: Double? = null,
        isVisited: Boolean = false
    ) = PointOfInterest(
        id = "test-id",
        name = "Test Place",
        latLng = LatLng(0.0, 0.0),
        address = "123 Test St",
        category = category,
        rating = rating,
        isVisited = isVisited
    )

    // --- geofenceRadiusFor ---

    @Test
    fun `stationary user keeps base radius`() {
        assertEquals(200, TourLogic.geofenceRadiusFor(0f, 200))
    }

    @Test
    fun `walking speed grows radius by half`() {
        // 1.5 m/s = 5.4 km/h → walking bucket
        assertEquals(300, TourLogic.geofenceRadiusFor(1.5f, 200))
    }

    @Test
    fun `city driving triples radius`() {
        // 10 m/s = 36 km/h → city-driving bucket
        assertEquals(600, TourLogic.geofenceRadiusFor(10f, 200))
    }

    @Test
    fun `highway driving quintuples radius`() {
        // 20 m/s = 72 km/h → highway bucket
        assertEquals(1000, TourLogic.geofenceRadiusFor(20f, 200))
    }

    @Test
    fun `radius grows monotonically with speed`() {
        val radii = listOf(0f, 1f, 3f, 10f, 20f, 30f).map {
            TourLogic.geofenceRadiusFor(it, 100)
        }
        assertEquals(radii, radii.sorted())
    }

    // --- contentPriorityFor ---

    @Test
    fun `high rating adds three points`() {
        val prefs = UserPreferences(preferredCategories = emptySet())
        assertEquals(3, TourLogic.contentPriorityFor(poi(rating = 4.8), prefs, 0))
    }

    @Test
    fun `preferred category adds two points`() {
        val prefs = UserPreferences(preferredCategories = setOf(PointOfInterest.Category.CULTURAL))
        assertEquals(2, TourLogic.contentPriorityFor(poi(category = "CULTURAL"), prefs, 0))
    }

    @Test
    fun `visited place is penalized`() {
        val prefs = UserPreferences(preferredCategories = emptySet())
        val fresh = TourLogic.contentPriorityFor(poi(rating = 4.8), prefs, 0)
        val visited = TourLogic.contentPriorityFor(poi(rating = 4.8, isVisited = true), prefs, 0)
        assertTrue(visited < fresh)
    }

    @Test
    fun `priority never drops below zero`() {
        val prefs = UserPreferences(preferredCategories = emptySet())
        assertEquals(0, TourLogic.contentPriorityFor(poi(isVisited = true), prefs, 0))
    }

    @Test
    fun `unknown category string does not crash`() {
        val prefs = UserPreferences()
        assertEquals(1, TourLogic.contentPriorityFor(poi(category = "no-such-category"), prefs, 1))
    }

    // --- relativeDirection ---

    @Test
    fun `poi on the nose is ahead`() {
        assertEquals(TourLogic.RelativeDirection.AHEAD, TourLogic.relativeDirection(0f, 0f))
    }

    @Test
    fun `poi at ninety degrees is on the right`() {
        assertEquals(TourLogic.RelativeDirection.RIGHT, TourLogic.relativeDirection(0f, 90f))
    }

    @Test
    fun `poi at one eighty is behind`() {
        assertEquals(TourLogic.RelativeDirection.BEHIND, TourLogic.relativeDirection(0f, 180f))
    }

    @Test
    fun `poi at two seventy is on the left`() {
        assertEquals(TourLogic.RelativeDirection.LEFT, TourLogic.relativeDirection(0f, 270f))
    }

    @Test
    fun `direction is relative to travel heading not north`() {
        // Heading east, POI due north → over the left shoulder
        assertEquals(TourLogic.RelativeDirection.LEFT, TourLogic.relativeDirection(90f, 0f))
    }

    @Test
    fun `quadrant wraps around north`() {
        // Heading 350°, POI bearing 10° → only 20° off the nose
        assertEquals(TourLogic.RelativeDirection.AHEAD, TourLogic.relativeDirection(350f, 10f))
    }

    @Test
    fun `quadrant boundaries fall clockwise`() {
        assertEquals(TourLogic.RelativeDirection.RIGHT, TourLogic.relativeDirection(0f, 45f))
        assertEquals(TourLogic.RelativeDirection.BEHIND, TourLogic.relativeDirection(0f, 135f))
        assertEquals(TourLogic.RelativeDirection.LEFT, TourLogic.relativeDirection(0f, 225f))
        assertEquals(TourLogic.RelativeDirection.AHEAD, TourLogic.relativeDirection(0f, 315f))
    }

    // --- narrationIntroFor ---

    @Test
    fun `intro names the direction`() {
        assertEquals(
            "On your left: Fort Point.",
            TourLogic.narrationIntroFor("Fort Point", TourLogic.RelativeDirection.LEFT)
        )
    }

    @Test
    fun `unknown direction falls back to neutral intro`() {
        assertEquals(
            "Coming up: Fort Point.",
            TourLogic.narrationIntroFor("Fort Point", null)
        )
    }
}
