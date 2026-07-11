package com.spiritwisestudios.gpstracker.data.api

import com.spiritwisestudios.gpstracker.domain.model.LatLng
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacesApiServiceTest {

    private fun tags(vararg pairs: Pair<String, String>) =
        JSONObject(pairs.toMap())

    // --- categoryForTags ---

    @Test
    fun `museum maps to cultural`() {
        assertEquals("CULTURAL", PlacesApiService.categoryForTags(tags("tourism" to "museum")))
    }

    @Test
    fun `any historic tag maps to historical`() {
        assertEquals("HISTORICAL", PlacesApiService.categoryForTags(tags("historic" to "monument")))
        assertEquals("HISTORICAL", PlacesApiService.categoryForTags(tags("historic" to "castle")))
        assertEquals("HISTORICAL", PlacesApiService.categoryForTags(tags("amenity" to "place_of_worship")))
    }

    @Test
    fun `park and viewpoint map to natural`() {
        assertEquals("NATURAL", PlacesApiService.categoryForTags(tags("leisure" to "park")))
        assertEquals("NATURAL", PlacesApiService.categoryForTags(tags("tourism" to "viewpoint")))
        assertEquals("NATURAL", PlacesApiService.categoryForTags(tags("natural" to "peak")))
    }

    @Test
    fun `restaurant maps to dining`() {
        assertEquals("DINING", PlacesApiService.categoryForTags(tags("amenity" to "restaurant")))
    }

    @Test
    fun `attraction and cinema map to entertainment`() {
        assertEquals("ENTERTAINMENT", PlacesApiService.categoryForTags(tags("tourism" to "attraction")))
        assertEquals("ENTERTAINMENT", PlacesApiService.categoryForTags(tags("amenity" to "cinema")))
    }

    @Test
    fun `mall maps to shopping`() {
        assertEquals("SHOPPING", PlacesApiService.categoryForTags(tags("shop" to "mall")))
    }

    @Test
    fun `untagged places have no category`() {
        assertNull(PlacesApiService.categoryForTags(tags("amenity" to "parking")))
        assertNull(PlacesApiService.categoryForTags(tags()))
    }

    @Test
    fun `cultural match wins over dining when both present`() {
        // A museum with a cafe inside should narrate as a museum
        assertEquals(
            "CULTURAL",
            PlacesApiService.categoryForTags(tags("tourism" to "museum", "amenity" to "cafe"))
        )
    }

    // --- elementToPoi ---

    @Test
    fun `node element becomes a POI`() {
        val element = JSONObject(
            """
            {
              "type": "node", "id": 123456, "lat": 37.7749, "lon": -122.4194,
              "tags": { "name": "City Museum", "tourism": "museum", "addr:street": "Main St", "addr:housenumber": "1" }
            }
            """
        )

        val poi = PlacesApiService.elementToPoi(element)!!

        assertEquals("node/123456", poi.id)
        assertEquals("node/123456", poi.placeId)
        assertEquals("City Museum", poi.name)
        assertEquals(37.7749, poi.latLng.latitude, 1e-9)
        assertEquals("CULTURAL", poi.category)
        assertEquals("1 Main St", poi.address)
        assertNull(poi.rating)
    }

    @Test
    fun `way element uses its center`() {
        val element = JSONObject(
            """
            {
              "type": "way", "id": 42, "center": { "lat": 37.5, "lon": -122.1 },
              "tags": { "name": "Old Fort", "historic": "fort" }
            }
            """
        )

        val poi = PlacesApiService.elementToPoi(element)!!

        assertEquals("way/42", poi.id)
        assertEquals(37.5, poi.latLng.latitude, 1e-9)
        assertEquals("HISTORICAL", poi.category)
    }

    @Test
    fun `nameless or uncategorized elements are skipped`() {
        val nameless = JSONObject("""{ "type": "node", "id": 1, "lat": 1.0, "lon": 1.0, "tags": { "tourism": "museum" } }""")
        val uncategorized = JSONObject("""{ "type": "node", "id": 2, "lat": 1.0, "lon": 1.0, "tags": { "name": "Parking", "amenity": "parking" } }""")

        assertNull(PlacesApiService.elementToPoi(nameless))
        assertNull(PlacesApiService.elementToPoi(uncategorized))
    }

    // --- parseElementsResponse ---

    @Test
    fun `results sort closest first`() {
        val json = """
            {
              "elements": [
                { "type": "node", "id": 1, "lat": 37.80, "lon": -122.40, "tags": { "name": "Far Museum", "tourism": "museum" } },
                { "type": "node", "id": 2, "lat": 37.7751, "lon": -122.4195, "tags": { "name": "Near Museum", "tourism": "museum" } }
              ]
            }
        """.trimIndent()

        val pois = PlacesApiService.parseElementsResponse(json, LatLng(37.7749, -122.4194))

        assertEquals(listOf("Near Museum", "Far Museum"), pois.map { it.name })
    }

    @Test
    fun `empty response yields empty list`() {
        assertTrue(PlacesApiService.parseElementsResponse("""{"elements":[]}""").isEmpty())
        assertTrue(PlacesApiService.parseElementsResponse("""{}""").isEmpty())
    }

    // --- buildDetailsQuery ---

    @Test
    fun `details query resolves osm ids`() {
        assertEquals(
            "[out:json][timeout:25];node(123);out center;",
            PlacesApiService.buildDetailsQuery("node/123")
        )
        assertEquals(
            "[out:json][timeout:25];way(456);out center;",
            PlacesApiService.buildDetailsQuery("way/456")
        )
    }

    @Test
    fun `details query rejects non-osm ids`() {
        // Google place ids linger in the local cache from before the migration
        assertNull(PlacesApiService.buildDetailsQuery("ChIJd8BlQ2BZwokRAFUEcm_qrcA"))
        assertNull(PlacesApiService.buildDetailsQuery("node/abc"))
        assertNull(PlacesApiService.buildDetailsQuery("planet/1"))
    }

    // --- buildDescription ---

    @Test
    fun `description assembles informational tags`() {
        val description = PlacesApiService.buildDescription(
            tags(
                "opening_hours" to "Mo-Fr 09:00-17:00",
                "website" to "https://example.org",
                "cuisine" to "italian"
            )
        )

        assertEquals("Cuisine: italian · Hours: Mo-Fr 09:00-17:00 · Website: https://example.org", description)
    }

    @Test
    fun `description is empty when nothing is tagged`() {
        assertEquals("", PlacesApiService.buildDescription(tags("name" to "X")))
    }
}
