package com.spiritwisestudios.gpstracker.data.api

import com.spiritwisestudios.gpstracker.domain.model.LatLng
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

    // --- parseTitleLookup ---

    private val reno = LatLng(39.5296, -119.8138)

    private fun titleLookupJson(
        extract: String = "Reno is a city in Nevada known for its casinos.",
        withCoordinates: Boolean = true,
        lat: Double = 39.5296,
        lon: Double = -119.8138
    ): String {
        val coordinates = if (withCoordinates) {
            """, "coordinates": [ { "lat": $lat, "lon": $lon } ]"""
        } else ""
        return """
            {
              "query": {
                "pages": {
                  "100": { "pageid": 100, "title": "Reno, Nevada", "extract": "$extract"$coordinates }
                }
              }
            }
        """.trimIndent()
    }

    @Test
    fun `title lookup parses an article near the expected spot`() {
        val article = WikipediaApiService.parseTitleLookup(titleLookupJson(), reno, 50_000f)!!
        assertEquals(100L, article.pageId)
        assertEquals("Reno, Nevada", article.title)
        assertTrue(article.extract.contains("casinos"))
    }

    @Test
    fun `title lookup rejects pages without coordinates`() {
        // Disambiguation pages have no coordinates — the wrong page for a
        // region, however well the title matched
        assertNull(
            WikipediaApiService.parseTitleLookup(
                titleLookupJson(withCoordinates = false), reno, 50_000f
            )
        )
    }

    @Test
    fun `title lookup rejects a same-named place elsewhere`() {
        // A "Reno" in Texas is not the Reno the listener is driving through
        assertNull(
            WikipediaApiService.parseTitleLookup(
                titleLookupJson(lat = 33.6657, lon = -95.4633), reno, 50_000f
            )
        )
    }

    @Test
    fun `title lookup rejects missing pages and blank extracts`() {
        val missing = """{"query":{"pages":{"-1":{"title":"Nowhere","missing":""}}}}"""
        assertNull(WikipediaApiService.parseTitleLookup(missing, reno, 50_000f))
        assertNull(
            WikipediaApiService.parseTitleLookup(titleLookupJson(extract = ""), reno, 50_000f)
        )
    }
}
