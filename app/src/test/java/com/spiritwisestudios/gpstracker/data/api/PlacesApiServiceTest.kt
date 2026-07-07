package com.spiritwisestudios.gpstracker.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class PlacesApiServiceTest {

    // --- mapPlaceTypesToCategory ---

    @Test
    fun `museum maps to cultural`() {
        assertEquals("CULTURAL", PlacesApiService.mapPlaceTypesToCategory(listOf("museum", "point_of_interest")))
    }

    @Test
    fun `church maps to historical`() {
        assertEquals("HISTORICAL", PlacesApiService.mapPlaceTypesToCategory(listOf("church", "place_of_worship")))
    }

    @Test
    fun `park maps to natural`() {
        assertEquals("NATURAL", PlacesApiService.mapPlaceTypesToCategory(listOf("park")))
    }

    @Test
    fun `restaurant maps to dining`() {
        assertEquals("DINING", PlacesApiService.mapPlaceTypesToCategory(listOf("restaurant", "food")))
    }

    @Test
    fun `unknown types map to other`() {
        assertEquals("OTHER", PlacesApiService.mapPlaceTypesToCategory(listOf("gas_station", "atm")))
        assertEquals("OTHER", PlacesApiService.mapPlaceTypesToCategory(emptyList()))
    }

    // --- parseNearbySearchResponse ---

    private val sampleResponse = """
        {
          "status": "OK",
          "results": [
            {
              "place_id": "abc123",
              "name": "City Museum",
              "geometry": { "location": { "lat": 37.1, "lng": -122.2 } },
              "vicinity": "1 Museum Way",
              "types": ["museum", "point_of_interest"],
              "rating": 4.6,
              "photos": [{ "photo_reference": "photoref1" }]
            },
            {
              "place_id": "def456",
              "name": "Gas Stop",
              "geometry": { "location": { "lat": 37.2, "lng": -122.3 } },
              "vicinity": "2 Fuel Rd",
              "types": ["gas_station"]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `parses places and filters out boring types`() {
        val pois = PlacesApiService.parseNearbySearchResponse(sampleResponse, "KEY")

        assertEquals(1, pois.size)
        val museum = pois[0]
        assertEquals("abc123", museum.id)
        assertEquals("abc123", museum.placeId)
        assertEquals("City Museum", museum.name)
        assertEquals("CULTURAL", museum.category)
        assertEquals(37.1, museum.latLng.latitude, 1e-6)
        assertEquals(4.6, museum.rating!!, 1e-6)
        assertTrue(museum.photoUrl!!.contains("photoref1"))
        assertTrue(museum.photoUrl!!.contains("KEY"))
    }

    @Test
    fun `zero results yields empty list`() {
        val json = """{ "status": "ZERO_RESULTS", "results": [] }"""
        assertTrue(PlacesApiService.parseNearbySearchResponse(json, "KEY").isEmpty())
    }

    @Test
    fun `error status throws`() {
        val json = """{ "status": "REQUEST_DENIED", "error_message": "bad key", "results": [] }"""
        val e = assertThrows(IOException::class.java) {
            PlacesApiService.parseNearbySearchResponse(json, "KEY")
        }
        assertTrue(e.message!!.contains("REQUEST_DENIED"))
    }

    @Test
    fun `missing photo yields null photoUrl`() {
        val json = """
            {
              "status": "OK",
              "results": [{
                "place_id": "x",
                "name": "Old Church",
                "geometry": { "location": { "lat": 1.0, "lng": 2.0 } },
                "types": ["church"]
              }]
            }
        """.trimIndent()

        assertNull(PlacesApiService.parseNearbySearchResponse(json, "KEY")[0].photoUrl)
    }
}
