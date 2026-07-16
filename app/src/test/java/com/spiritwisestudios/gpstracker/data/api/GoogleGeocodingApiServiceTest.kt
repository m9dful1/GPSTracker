package com.spiritwisestudios.gpstracker.data.api

import com.spiritwisestudios.gpstracker.domain.model.LatLng
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleGeocodingApiServiceTest {

    @Test
    fun `search request carries the query and a location bias`() {
        val body = JSONObject(
            GoogleGeocodingApiService.buildSearchRequestBody(
                "golden gate", LatLng(37.77, -122.42), 10
            )
        )

        assertEquals("golden gate", body.getString("textQuery"))
        assertEquals(10, body.getInt("pageSize"))
        val circle = body.getJSONObject("locationBias").getJSONObject("circle")
        assertEquals(37.77, circle.getJSONObject("center").getDouble("latitude"), 1e-9)
    }

    @Test
    fun `no bias means no locationBias in the request`() {
        val body = JSONObject(
            GoogleGeocodingApiService.buildSearchRequestBody("query", null, 5)
        )
        assertFalse(body.has("locationBias"))
        assertEquals(5, body.getInt("pageSize"))
    }

    @Test
    fun `search results parse name, detail, and coordinates`() {
        val json = """
            {"places":[
              {"id":"a","displayName":{"text":"Golden Gate Bridge"},
               "formattedAddress":"San Francisco, CA 94129",
               "location":{"latitude":37.8199,"longitude":-122.4783}},
              {"id":"b","formattedAddress":"1 Main St, Somewhere",
               "location":{"latitude":36.0,"longitude":-115.0}}
            ]}
        """.trimIndent()

        val results = GoogleGeocodingApiService.parseSearchResponse(json)

        assertEquals(2, results.size)
        assertEquals("Golden Gate Bridge", results[0].name)
        assertEquals("San Francisco, CA 94129", results[0].detail)
        assertEquals(37.8199, results[0].latLng.latitude, 1e-9)

        // No display name: the address becomes the name, not also the detail
        assertEquals("1 Main St, Somewhere", results[1].name)
        assertEquals("", results[1].detail)
    }

    @Test
    fun `places without coordinates are dropped and empty responses are fine`() {
        val json = """{"places":[{"id":"a","displayName":{"text":"No Location"}}]}"""
        assertTrue(GoogleGeocodingApiService.parseSearchResponse(json).isEmpty())
        assertTrue(GoogleGeocodingApiService.parseSearchResponse("""{}""").isEmpty())
    }
}
