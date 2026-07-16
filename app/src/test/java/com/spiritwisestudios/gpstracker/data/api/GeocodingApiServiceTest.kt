package com.spiritwisestudios.gpstracker.data.api

import com.spiritwisestudios.gpstracker.domain.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeocodingApiServiceTest {

    @Test
    fun `parses photon features into results`() {
        val json = """
            {
              "type": "FeatureCollection",
              "features": [
                {
                  "geometry": { "type": "Point", "coordinates": [-122.4783, 37.8199] },
                  "properties": {
                    "name": "Golden Gate Bridge",
                    "city": "San Francisco",
                    "state": "California"
                  }
                },
                {
                  "geometry": { "type": "Point", "coordinates": [-122.41, 37.77] },
                  "properties": {
                    "housenumber": "123",
                    "street": "Market Street",
                    "city": "San Francisco"
                  }
                }
              ]
            }
        """.trimIndent()

        val results = GeocodingApiService.parseSearchResponse(json)

        assertEquals(2, results.size)

        val bridge = results[0]
        assertEquals("Golden Gate Bridge", bridge.name)
        // GeoJSON is [lon, lat] — make sure they land the right way around
        assertEquals(37.8199, bridge.latLng.latitude, 1e-9)
        assertEquals(-122.4783, bridge.latLng.longitude, 1e-9)
        assertEquals("San Francisco, California", bridge.detail)

        // Address-only feature falls back to the street as its name
        val address = results[1]
        assertEquals("Market Street", address.name)
        assertEquals("123 Market Street, San Francisco", address.detail)
    }

    @Test
    fun `detail line includes the country to tell same-named places apart`() {
        val json = """
            {
              "features": [
                {
                  "geometry": { "coordinates": [2.2945, 48.8584] },
                  "properties": {
                    "name": "Eiffel Tower",
                    "city": "Paris",
                    "state": "Ile-de-France",
                    "country": "France"
                  }
                }
              ]
            }
        """.trimIndent()

        val results = GeocodingApiService.parseSearchResponse(json)

        assertEquals("Paris, Ile-de-France, France", results.single().detail)
    }

    @Test
    fun `features without name or coordinates are dropped`() {
        val json = """
            {
              "features": [
                { "geometry": { "coordinates": [1.0, 2.0] }, "properties": { "city": "Nowhere" } },
                { "properties": { "name": "No geometry" } }
              ]
            }
        """.trimIndent()

        assertTrue(GeocodingApiService.parseSearchResponse(json).isEmpty())
    }

    @Test
    fun `empty response yields empty list`() {
        assertTrue(GeocodingApiService.parseSearchResponse("""{}""").isEmpty())
    }

    @Test
    fun `same-named results nearby collapse to the highest-ranked`() {
        // A bridge split into several OSM ways comes back as several features;
        // the outermost fragments sit ~1.6 km apart
        val results = listOf(
            result("Golden Gate Bridge", 37.8199, -122.4783),
            result("Golden Gate Bridge", 37.8340, -122.4800),
            result("golden gate bridge", 37.8210, -122.4770)
        )

        val deduped = GeocodingApiService.dedupe(results)

        assertEquals(1, deduped.size)
        // First (highest-ranked) occurrence wins
        assertEquals(37.8199, deduped[0].latLng.latitude, 1e-9)
    }

    @Test
    fun `same-named places far apart are all kept`() {
        val results = listOf(
            result("Eiffel Tower", 48.8584, 2.2945),   // Paris, France
            result("Eiffel Tower", 33.6609, -95.5555)  // Paris, Texas
        )

        assertEquals(2, GeocodingApiService.dedupe(results).size)
    }

    @Test
    fun `device language maps to a photon lang, unsupported falls back to english`() {
        assertEquals("en", GeocodingApiService.searchLanguage("en"))
        assertEquals("de", GeocodingApiService.searchLanguage("de"))
        assertEquals("fr", GeocodingApiService.searchLanguage("FR"))
        assertEquals("en", GeocodingApiService.searchLanguage("es"))
        assertEquals("en", GeocodingApiService.searchLanguage(""))
    }

    @Test
    fun `search url carries language, over-fetch limit, and softened bias`() {
        val biased = GeocodingApiService.buildSearchUrl(
            "https://photon.example",
            "golden gate",
            LatLng(37.7749, -122.4194),
            "en"
        )

        assertTrue(biased.contains("q=golden+gate"))
        assertTrue(biased.contains("&lang=en"))
        assertTrue(biased.contains("&limit=30"))
        assertTrue(biased.contains("&lat=37.7749&lon=-122.4194"))
        assertTrue(biased.contains("&zoom=11&location_bias_scale=0.1"))

        val unbiased = GeocodingApiService.buildSearchUrl(
            "https://photon.example", "golden gate", null, "de"
        )

        assertTrue(unbiased.contains("&lang=de"))
        assertFalse(unbiased.contains("&lat="))
        assertFalse(unbiased.contains("&zoom="))
    }

    private fun result(name: String, lat: Double, lon: Double) =
        GeocodingApi.SearchResult(name, "", LatLng(lat, lon))
}
