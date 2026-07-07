package com.spiritwisestudios.gpstracker.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WikipediaApiServiceTest {

    // --- parseGeoSearchResponse ---

    @Test
    fun `parses geosearch results`() {
        val json = """
            {
              "query": {
                "geosearch": [
                  { "pageid": 100, "title": "Golden Gate Bridge", "dist": 25.4 },
                  { "pageid": 200, "title": "Fort Point", "dist": 210.0 }
                ]
              }
            }
        """.trimIndent()

        val results = WikipediaApiService.parseGeoSearchResponse(json)

        assertEquals(2, results.size)
        assertEquals(100L, results[0].pageId)
        assertEquals("Golden Gate Bridge", results[0].title)
        assertEquals(25.4, results[0].distanceMeters, 1e-6)
    }

    @Test
    fun `empty geosearch yields empty list`() {
        assertTrue(WikipediaApiService.parseGeoSearchResponse("""{"query":{"geosearch":[]}}""").isEmpty())
        assertTrue(WikipediaApiService.parseGeoSearchResponse("""{}""").isEmpty())
    }

    // --- parseExtractResponse ---

    @Test
    fun `parses extract text`() {
        val json = """
            {
              "query": {
                "pages": {
                  "100": { "pageid": 100, "title": "Golden Gate Bridge", "extract": "The Golden Gate Bridge is a suspension bridge." }
                }
              }
            }
        """.trimIndent()

        assertEquals(
            "The Golden Gate Bridge is a suspension bridge.",
            WikipediaApiService.parseExtractResponse(json)
        )
    }

    @Test
    fun `blank extract yields null`() {
        val json = """{"query":{"pages":{"100":{"pageid":100,"extract":""}}}}"""
        assertNull(WikipediaApiService.parseExtractResponse(json))
    }

    // --- title matching ---

    @Test
    fun `exact name match scores one`() {
        assertEquals(1.0, WikipediaApiService.titleMatchScore("Golden Gate Bridge", "Golden Gate Bridge"), 1e-6)
    }

    @Test
    fun `unrelated names score zero`() {
        assertEquals(0.0, WikipediaApiService.titleMatchScore("Joe's Diner", "Golden Gate Bridge"), 1e-6)
    }

    @Test
    fun `picks best matching candidate`() {
        val candidates = listOf(
            WikipediaApiService.GeoSearchResult(1, "History of San Francisco", 10.0),
            WikipediaApiService.GeoSearchResult(2, "Golden Gate Bridge", 50.0),
            WikipediaApiService.GeoSearchResult(3, "Presidio", 80.0)
        )

        val best = WikipediaApiService.pickBestArticle("The Golden Gate Bridge", candidates)
        assertEquals(2L, best?.pageId)
    }

    @Test
    fun `no candidate above threshold yields null`() {
        val candidates = listOf(
            WikipediaApiService.GeoSearchResult(1, "History of San Francisco", 10.0)
        )
        assertNull(WikipediaApiService.pickBestArticle("Joe's Diner", candidates))
    }
}
