package com.spiritwisestudios.gpstracker.data.api

import com.spiritwisestudios.gpstracker.domain.model.LatLng
import com.spiritwisestudios.gpstracker.domain.model.PointOfInterest
import com.spiritwisestudios.gpstracker.util.GeoUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException

/**
 * Finds tour-worthy points of interest with the Overpass API — a free,
 * keyless query service over OpenStreetMap data.
 *
 * Nearby discovery queries the OSM tag families that make good narration
 * targets (tourism, historic, leisure, amenity, ...) around any point, e.g.
 * sampled points along a navigation route. Place details re-resolve a single
 * OSM element by its id. POI ids are OSM element ids ("node/123", "way/456",
 * "relation/789"), which are stable across queries.
 */
class PlacesApiService(
    private val httpClient: OkHttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL
) : PlacesApi {

    companion object {
        private const val DEFAULT_BASE_URL = "https://overpass-api.de"
        private const val USER_AGENT = "GPSTracker-TourGuide/1.0 (educational project)"
        private const val MAX_NEARBY_RESULTS = 20
        // Ask for more than we keep so the closest-first trim has options
        private const val QUERY_RESULT_CAP = 60

        // Tag values worth touring, grouped by the OSM key they live under.
        private val TOURISM_VALUES = setOf(
            "museum", "gallery", "attraction", "viewpoint", "artwork",
            "zoo", "aquarium", "theme_park"
        )
        private val LEISURE_VALUES = setOf(
            "park", "garden", "nature_reserve", "stadium"
        )
        private val NATURAL_VALUES = setOf("beach", "peak", "spring", "geyser")
        private val AMENITY_VALUES = setOf(
            "place_of_worship", "theatre", "arts_centre", "library",
            "townhall", "university", "fountain", "cinema", "nightclub",
            "casino", "restaurant", "cafe", "bar", "pub", "fast_food",
            "marketplace"
        )
        private val SHOP_VALUES = setOf("mall", "department_store", "books")

        /**
         * Overpass QL for named POIs around a point. `nwr` covers nodes, ways
         * and relations; `out center` adds a centroid for the latter two.
         */
        internal fun buildNearbyQuery(center: LatLng, radiusMeters: Int): String {
            val around = "around:$radiusMeters,${center.latitude},${center.longitude}"
            fun regex(values: Set<String>) = "^(${values.joinToString("|")})$"

            return """
                [out:json][timeout:25];
                (
                  nwr($around)["tourism"~"${regex(TOURISM_VALUES)}"]["name"];
                  nwr($around)["historic"]["name"];
                  nwr($around)["leisure"~"${regex(LEISURE_VALUES)}"]["name"];
                  nwr($around)["natural"~"${regex(NATURAL_VALUES)}"]["name"];
                  nwr($around)["amenity"~"${regex(AMENITY_VALUES)}"]["name"];
                  nwr($around)["shop"~"${regex(SHOP_VALUES)}"]["name"];
                );
                out center $QUERY_RESULT_CAP;
            """.trimIndent()
        }

        /** Overpass QL resolving one element by its "type/id" POI id. */
        internal fun buildDetailsQuery(osmId: String): String? {
            val (type, id) = osmId.split("/").takeIf { it.size == 2 } ?: return null
            if (type !in setOf("node", "way", "relation") || id.toLongOrNull() == null) return null
            return "[out:json][timeout:25];$type($id);out center;"
        }

        /**
         * Parse an Overpass response into domain POIs. Results are sorted
         * closest-first when a center is given, and capped at
         * [MAX_NEARBY_RESULTS].
         */
        internal fun parseElementsResponse(json: String, center: LatLng? = null): List<PointOfInterest> {
            val elements = JSONObject(json).optJSONArray("elements") ?: return emptyList()

            val pois = (0 until elements.length()).mapNotNull { i ->
                elementToPoi(elements.getJSONObject(i))
            }

            val sorted = if (center != null) {
                pois.sortedBy { GeoUtils.distanceMeters(center, it.latLng) }
            } else {
                pois
            }
            return sorted.take(MAX_NEARBY_RESULTS)
        }

        /**
         * [parseElementsResponse], with a body we can't read reported as the
         * `IOException` every [PlacesApi] caller already handles.
         *
         * Overpass answers HTTP 200 with an HTML error page when it is
         * overloaded, so a body that isn't JSON is the server's ordinary way
         * of saying no — not a `JSONException` for a ViewModel to render as
         * "Please try again" with nothing in the log to say why.
         */
        internal fun parseElementsResponseOrThrow(
            json: String,
            center: LatLng? = null
        ): List<PointOfInterest> =
            try {
                parseElementsResponse(json, center)
            } catch (e: JSONException) {
                Timber.e(e, "Overpass response could not be parsed")
                throw IOException("Malformed Overpass response", e)
            }

        /**
         * Convert one OSM element into a domain POI, or null when it has
         * nothing to narrate (no name, no touring category, no location).
         */
        internal fun elementToPoi(element: JSONObject): PointOfInterest? {
            val tags = element.optJSONObject("tags") ?: return null
            val name = tags.optString("name").takeIf { it.isNotBlank() } ?: return null

            // Nodes carry lat/lon directly; ways and relations get a center
            val location = if (element.has("lat") && element.has("lon")) {
                LatLng(element.getDouble("lat"), element.getDouble("lon"))
            } else {
                element.optJSONObject("center")?.let {
                    LatLng(it.getDouble("lat"), it.getDouble("lon"))
                }
            } ?: return null

            val category = categoryForTags(tags) ?: return null
            val osmId = "${element.optString("type")}/${element.optLong("id")}"

            return PointOfInterest(
                id = osmId,
                name = name,
                latLng = location,
                address = buildAddress(tags),
                category = category,
                rating = null, // OSM has no rating data
                description = buildDescription(tags).takeIf { it.isNotBlank() },
                photoUrl = null,
                placeId = osmId
            )
        }

        /**
         * Map OSM tags to a stable domain category label, or null for places
         * that aren't tour-worthy. Mirrors the buckets the Google place types
         * used to map onto, so cached POIs keep matching.
         */
        internal fun categoryForTags(tags: JSONObject): String? {
            val tourism = tags.optString("tourism")
            val amenity = tags.optString("amenity")
            val leisure = tags.optString("leisure")
            val natural = tags.optString("natural")
            val shop = tags.optString("shop")

            return when {
                tourism in setOf("museum", "gallery", "artwork", "zoo", "aquarium") ||
                    amenity in setOf("arts_centre", "library", "theatre", "university") -> "CULTURAL"
                tags.has("historic") ||
                    amenity in setOf("place_of_worship", "townhall") -> "HISTORICAL"
                leisure in setOf("park", "garden", "nature_reserve") ||
                    natural.isNotBlank() || tourism == "viewpoint" -> "NATURAL"
                tourism in setOf("attraction", "theme_park") ||
                    amenity in setOf("cinema", "nightclub", "casino") ||
                    leisure == "stadium" -> "ENTERTAINMENT"
                amenity in setOf("restaurant", "cafe", "bar", "pub", "fast_food") -> "DINING"
                shop.isNotBlank() || amenity == "marketplace" -> "SHOPPING"
                amenity == "fountain" -> "CULTURAL"
                else -> null
            }
        }

        /** Street address assembled from addr:* tags, best-effort. */
        internal fun buildAddress(tags: JSONObject): String {
            val street = listOf(
                tags.optString("addr:housenumber"),
                tags.optString("addr:street")
            ).filter { it.isNotBlank() }.joinToString(" ")

            return listOf(street, tags.optString("addr:city"))
                .filter { it.isNotBlank() }
                .joinToString(", ")
        }

        /** Human-readable summary from the informational OSM tags. */
        internal fun buildDescription(tags: JSONObject): String {
            val parts = mutableListOf<String>()

            tags.optString("description").takeIf { it.isNotBlank() }?.let { parts.add(it) }
            tags.optString("cuisine").takeIf { it.isNotBlank() }?.let {
                parts.add("Cuisine: ${it.replace('_', ' ').replace(';', ',')}")
            }
            tags.optString("opening_hours").takeIf { it.isNotBlank() }?.let { parts.add("Hours: $it") }
            listOf("phone", "contact:phone").firstNotNullOfOrNull {
                tags.optString(it).takeIf { v -> v.isNotBlank() }
            }?.let { parts.add("Phone: $it") }
            listOf("website", "contact:website").firstNotNullOfOrNull {
                tags.optString(it).takeIf { v -> v.isNotBlank() }
            }?.let { parts.add("Website: $it") }

            return parts.joinToString(" · ")
        }
    }

    /**
     * Find tour-worthy points of interest around a location.
     */
    override suspend fun getNearbyPlaces(center: LatLng, radius: Int): List<PointOfInterest> {
        val response = runQuery(buildNearbyQuery(center, radius))
        return parseElementsResponseOrThrow(response, center)
    }

    /**
     * Get detailed information about a specific place (an OSM element id).
     */
    override suspend fun getPlaceDetails(placeId: String): PointOfInterest {
        val query = buildDetailsQuery(placeId)
            ?: throw IOException("Not an OSM place id: $placeId")

        val response = runQuery(query)
        return parseElementsResponseOrThrow(response).firstOrNull()
            ?: throw IOException("Place not found: $placeId")
    }

    private suspend fun runQuery(query: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/api/interpreter")
            .header("User-Agent", USER_AGENT)
            .post(FormBody.Builder().add("data", query).build())
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.e("Overpass query failed: HTTP ${response.code}")
                    throw IOException("Overpass API HTTP ${response.code}")
                }
                response.body?.string() ?: throw IOException("Empty Overpass response")
            }
        } catch (e: IOException) {
            Timber.e(e, "Overpass query failed")
            throw e
        }
    }
}
