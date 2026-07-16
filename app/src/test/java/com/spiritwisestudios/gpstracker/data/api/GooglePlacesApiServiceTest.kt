package com.spiritwisestudios.gpstracker.data.api

import com.spiritwisestudios.gpstracker.domain.model.LatLng
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GooglePlacesApiServiceTest {

    @Test
    fun `nearby request restricts to a circle around the center`() {
        val body = JSONObject(
            GooglePlacesApiService.buildNearbyRequestBody(LatLng(37.8, -122.47), 500)
        )

        assertEquals(20, body.getInt("maxResultCount"))
        val circle = body.getJSONObject("locationRestriction").getJSONObject("circle")
        assertEquals(37.8, circle.getJSONObject("center").getDouble("latitude"), 1e-9)
        assertEquals(-122.47, circle.getJSONObject("center").getDouble("longitude"), 1e-9)
        assertEquals(500.0, circle.getDouble("radius"), 1e-9)
    }

    @Test
    fun `place types map onto the domain categories`() {
        assertEquals("CULTURAL", GooglePlacesApiService.mapPlaceTypesToCategory(listOf("museum")))
        assertEquals("HISTORICAL", GooglePlacesApiService.mapPlaceTypesToCategory(listOf("historical_landmark")))
        assertEquals("NATURAL", GooglePlacesApiService.mapPlaceTypesToCategory(listOf("park")))
        assertEquals("ENTERTAINMENT", GooglePlacesApiService.mapPlaceTypesToCategory(listOf("tourist_attraction")))
        assertEquals("DINING", GooglePlacesApiService.mapPlaceTypesToCategory(listOf("restaurant")))
        assertEquals("SHOPPING", GooglePlacesApiService.mapPlaceTypesToCategory(listOf("shopping_mall")))
        assertEquals("OTHER", GooglePlacesApiService.mapPlaceTypesToCategory(listOf("gas_station")))
        assertEquals("OTHER", GooglePlacesApiService.mapPlaceTypesToCategory(emptyList()))
    }

    @Test
    fun `nearby results become POIs and boring places are dropped`() {
        val json = """
            {"places":[
              {"id":"abc","displayName":{"text":"Fort Point"},
               "location":{"latitude":37.81,"longitude":-122.477},
               "formattedAddress":"San Francisco, CA",
               "types":["historical_landmark","point_of_interest"],"rating":4.7},
              {"id":"def","displayName":{"text":"Gas N Go"},
               "location":{"latitude":37.0,"longitude":-122.0},
               "types":["gas_station"]}
            ]}
        """.trimIndent()

        val pois = GooglePlacesApiService.parseNearbyResponse(json)

        assertEquals(1, pois.size)
        val poi = pois.first()
        assertEquals("Fort Point", poi.name)
        assertEquals("HISTORICAL", poi.category)
        assertEquals("abc", poi.placeId)
        assertEquals("San Francisco, CA", poi.address)
        assertEquals(4.7, poi.rating!!, 1e-9)
        assertEquals(37.81, poi.latLng.latitude, 1e-9)
    }

    @Test
    fun `places without an id or location are dropped`() {
        val noId = JSONObject("""{"displayName":{"text":"X"},"location":{"latitude":1,"longitude":2}}""")
        val noLocation = JSONObject("""{"id":"abc","displayName":{"text":"X"}}""")

        assertNull(GooglePlacesApiService.placeToPoi(noId, requireCategory = false))
        assertNull(GooglePlacesApiService.placeToPoi(noLocation, requireCategory = false))
    }

    @Test
    fun `details keep uncategorized places and build a description`() {
        val json = """
            {"id":"abc","displayName":{"text":"Some Diner"},
             "location":{"latitude":36.1,"longitude":-115.17},
             "types":["gas_station"],
             "businessStatus":"OPERATIONAL","priceLevel":"PRICE_LEVEL_MODERATE",
             "rating":4.5,"userRatingCount":120,
             "internationalPhoneNumber":"+1 555-0100",
             "websiteUri":"https://example.com"}
        """.trimIndent()

        val poi = GooglePlacesApiService.parsePlaceDetails(json)!!

        assertEquals("OTHER", poi.category) // details never filter by category
        val description = poi.description!!
        assertTrue(description.contains("Open"))
        assertTrue(description.contains("Moderate"))
        assertTrue(description.contains("4.5 stars (120 reviews)"))
        assertTrue(description.contains("Phone: +1 555-0100"))
        assertTrue(description.contains("Website: https://example.com"))
    }

    @Test
    fun `empty responses parse to no results`() {
        assertTrue(GooglePlacesApiService.parseNearbyResponse("""{"places":[]}""").isEmpty())
        assertTrue(GooglePlacesApiService.parseNearbyResponse("""{}""").isEmpty())
    }
}
