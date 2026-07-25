package com.spiritwisestudios.gpstracker.util

import com.spiritwisestudios.gpstracker.domain.model.LatLng
import com.spiritwisestudios.gpstracker.domain.model.PointOfInterest
import com.spiritwisestudios.gpstracker.domain.model.TourFocus
import com.spiritwisestudios.gpstracker.domain.model.TourLength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TourPlanLogicTest {

    private fun poi(
        name: String,
        category: String,
        lat: Double = 36.1,
        lon: Double = -115.17,
        rating: Double? = null
    ) = PointOfInterest(
        id = name, name = name, latLng = LatLng(lat, lon),
        address = "", category = category, rating = rating, placeId = name
    )

    @Test
    fun `search radius scales with tour length within sane bounds`() {
        assertEquals(1600, TourPlanLogic.poiSearchRadiusMeters(TourLength.SHORT))
        assertEquals(3800, TourPlanLogic.poiSearchRadiusMeters(TourLength.MEDIUM))
        assertEquals(8000, TourPlanLogic.poiSearchRadiusMeters(TourLength.LONG))
    }

    @Test
    fun `a history focus outranks lunch spots for landmarks`() {
        val fort = poi("Fort", "HISTORICAL")
        val diner = poi("Diner", "DINING", rating = 4.9)

        val historyFocus = TourPlanLogic.scoreStop(fort, TourFocus.HISTORY_AND_CULTURE, emptySet())
        val dinerScore = TourPlanLogic.scoreStop(diner, TourFocus.HISTORY_AND_CULTURE, emptySet())
        assertTrue(historyFocus > dinerScore)

        // With a food focus the same diner wins
        val foodFort = TourPlanLogic.scoreStop(fort, TourFocus.FOOD_AND_FUN, emptySet())
        val foodDiner = TourPlanLogic.scoreStop(diner, TourFocus.FOOD_AND_FUN, emptySet())
        assertTrue(foodDiner > foodFort)
    }

    @Test
    fun `preferred categories and ratings sweeten a stop`() {
        val plain = poi("Plain", "NATURAL")
        val rated = poi("Rated", "NATURAL", rating = 5.0)

        assertTrue(
            TourPlanLogic.scoreStop(rated, TourFocus.BALANCED, emptySet()) >
                TourPlanLogic.scoreStop(plain, TourFocus.BALANCED, emptySet())
        )
        assertTrue(
            TourPlanLogic.scoreStop(plain, TourFocus.BALANCED, setOf("NATURAL")) >
                TourPlanLogic.scoreStop(plain, TourFocus.BALANCED, emptySet())
        )
    }

    @Test
    fun `stop selection spreads across the area`() {
        // Two great museums on the same corner and a park a mile away:
        // a 2-stop tour should take one museum and the park, not both museums
        val museumA = poi("Museum A", "CULTURAL", 36.1000, -115.1700)
        val museumB = poi("Museum B", "CULTURAL", 36.1001, -115.1701)
        val park = poi("Park", "NATURAL", 36.1150, -115.1700)

        val stops = TourPlanLogic.selectStops(
            listOf(museumA, museumB, park),
            count = 2,
            minSpacingMeters = 500f,
            focus = TourFocus.BALANCED,
            preferredCategories = emptySet()
        )

        assertEquals(2, stops.size)
        assertTrue(stops.any { it.category == "NATURAL" })
    }

    @Test
    fun `a compact downtown still fills the tour`() {
        // Everything within a block: spacing can't be honored, but the tour
        // shouldn't come back half-empty
        val pois = (1..5).map { i -> poi("P$i", "CULTURAL", 36.1 + i * 1e-5, -115.17) }

        val stops = TourPlanLogic.selectStops(
            pois, count = 4, minSpacingMeters = 5000f,
            focus = TourFocus.BALANCED, preferredCategories = emptySet()
        )

        assertEquals(4, stops.size)
    }

    @Test
    fun `stops come back in driving order from the start`() {
        val start = LatLng(36.0, -115.0)
        val near = poi("Near", "CULTURAL", 36.01, -115.0)
        val mid = poi("Mid", "CULTURAL", 36.05, -115.0)
        val far = poi("Far", "CULTURAL", 36.10, -115.0)

        val ordered = TourPlanLogic.orderStops(start, listOf(far, near, mid))

        assertEquals(listOf("Near", "Mid", "Far"), ordered.map { it.name })
    }

    // --- corridorPlaces ---

    @Test
    fun `the tour's own stops are watched before anything discovered`() {
        // Order is the fix: corridor discovery caps how much it returns, so a
        // planned stop listed after it could be the one that gets cut.
        val chosen = listOf(poi("Chosen A", "CULTURAL"), poi("Chosen B", "HISTORICAL"))
        val found = listOf(poi("Found 1", "DINING"), poi("Found 2", "SHOPPING"))

        val watched = TourPlanLogic.corridorPlaces(chosen, found)

        assertEquals(
            listOf("Chosen A", "Chosen B", "Found 1", "Found 2"),
            watched.map { it.name }
        )
    }

    @Test
    fun `a stop discovery also found is listed once, as the chosen one`() {
        val chosen = poi("Old Mill", "HISTORICAL")
        // Same place id, arrived at through corridor discovery
        val alsoFound = poi("Old Mill", "HISTORICAL").copy(id = "corridor-row")

        val watched = TourPlanLogic.corridorPlaces(listOf(chosen), listOf(alsoFound))

        assertEquals(1, watched.size)
        assertEquals(chosen.id, watched.single().id)
    }

    @Test
    fun `an ordinary drive watches only what was discovered`() {
        val found = listOf(poi("Found 1", "DINING"), poi("Found 2", "SHOPPING"))

        assertEquals(found, TourPlanLogic.corridorPlaces(emptyList(), found))
    }

    @Test
    fun `a tour whose corridor turned up nothing still watches its stops`() {
        val chosen = listOf(poi("Chosen", "CULTURAL"))

        assertEquals(chosen, TourPlanLogic.corridorPlaces(chosen, emptyList()))
    }
}
