package com.spiritwisestudios.gpstracker.data.api

import com.spiritwisestudios.gpstracker.domain.model.LatLng
import com.spiritwisestudios.gpstracker.domain.model.PointOfInterest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException

/**
 * Finds tour-worthy points of interest with the Google Places API (New)
 * over REST. Nearby discovery uses Nearby Search (New); place details use
 * Place Details (New). Both need "Places API (New)" enabled on the Google
 * Cloud project that owns the MAPS_API_KEY.
 *
 * Active only when the map provider setting is Google; POI ids are Google
 * place ids, which are stable across queries.
 */
class GooglePlacesApiService(
    private val httpClient: OkHttpClient,
    private val apiKey: String,
    private val baseUrl: String = DEFAULT_BASE_URL
) : PlacesApi {

    /** False when no MAPS_API_KEY is configured; callers should fall back. */
    val isConfigured: Boolean
        get() = apiKey.isNotBlank()

    companion object {
        private const val DEFAULT_BASE_URL = "https://places.googleapis.com"
        private const val MAX_NEARBY_RESULTS = 20

        // Field masks are required; request only what the parsers read
        internal const val NEARBY_FIELD_MASK =
            "places.id,places.displayName,places.location,places.formattedAddress," +
                "places.types,places.rating"
        internal const val DETAILS_FIELD_MASK =
            "id,displayName,location,formattedAddress,types,rating," +
                "internationalPhoneNumber,websiteUri,businessStatus,priceLevel,userRatingCount"

        /** Request body for Nearby Search (New) around a point. */
        internal fun buildNearbyRequestBody(center: LatLng, radiusMeters: Int): String {
            return JSONObject()
                .put("maxResultCount", MAX_NEARBY_RESULTS)
                .put(
                    "locationRestriction", JSONObject().put(
                        "circle", JSONObject()
                            .put(
                                "center", JSONObject()
                                    .put("latitude", center.latitude)
                                    .put("longitude", center.longitude)
                            )
                            .put("radius", radiusMeters.toDouble())
                    )
                )
                .toString()
        }

        /**
         * Map Google place type strings to a stable domain category label.
         * Covers both legacy type names (stored in the local POI cache) and
         * the expanded type set returned by the new Places API.
         */
        internal fun mapPlaceTypesToCategory(types: List<String>): String {
            return when {
                types.any { it in setOf("museum", "art_gallery", "zoo", "aquarium", "library", "university", "cultural_center", "performing_arts_theater") } -> "CULTURAL"
                types.any { it in setOf("church", "place_of_worship", "synagogue", "mosque", "hindu_temple", "cemetery", "city_hall", "historical_landmark", "historical_place", "monument") } -> "HISTORICAL"
                types.any { it in setOf("park", "campground", "natural_feature", "national_park", "state_park", "botanical_garden", "garden", "beach") } -> "NATURAL"
                types.any { it in setOf("tourist_attraction", "amusement_park", "movie_theater", "night_club", "stadium", "casino", "bowling_alley") } -> "ENTERTAINMENT"
                types.any { it in setOf("restaurant", "cafe", "bar", "bakery", "meal_takeaway") } -> "DINING"
                types.any { it in setOf("shopping_mall", "department_store", "book_store", "clothing_store") } -> "SHOPPING"
                else -> "OTHER"
            }
        }

        /**
         * Parse a Nearby Search response into domain POIs. Places with
         * nothing to narrate (gas stations, offices, ...) are dropped.
         */
        internal fun parseNearbyResponse(json: String): List<PointOfInterest> {
            val places = JSONObject(json).optJSONArray("places") ?: return emptyList()
            return (0 until places.length()).mapNotNull { i ->
                placeToPoi(places.getJSONObject(i), requireCategory = true)
            }
        }

        /** Parse a Place Details response (a single place object). */
        internal fun parsePlaceDetails(json: String): PointOfInterest? =
            placeToPoi(JSONObject(json), requireCategory = false)

        /**
         * The two parsers above, with a body we can't read reported as the
         * `IOException` every [PlacesApi] caller already handles rather than
         * a `JSONException` none of them names.
         */
        internal fun parseNearbyResponseOrThrow(json: String): List<PointOfInterest> =
            orThrow("Nearby Search") { parseNearbyResponse(json) }

        internal fun parsePlaceDetailsOrThrow(json: String): PointOfInterest? =
            orThrow("Place Details") { parsePlaceDetails(json) }

        private fun <T> orThrow(context: String, parse: () -> T): T =
            try {
                parse()
            } catch (e: JSONException) {
                Timber.e(e, "$context response could not be parsed")
                throw IOException("Malformed $context response", e)
            }

        /**
         * Convert one REST place object into a domain POI, or null when it
         * has no id or location (or, when required, no touring category).
         */
        internal fun placeToPoi(place: JSONObject, requireCategory: Boolean): PointOfInterest? {
            val placeId = place.optString("id").takeIf { it.isNotBlank() } ?: return null
            val location = place.optJSONObject("location") ?: return null

            val types = place.optJSONArray("types").toStringList()
            val category = mapPlaceTypesToCategory(types)
            if (requireCategory && category == "OTHER") return null

            return PointOfInterest(
                id = placeId,
                name = place.optJSONObject("displayName")?.optString("text")
                    ?.takeIf { it.isNotBlank() } ?: "Unknown Place",
                latLng = LatLng(
                    location.getDouble("latitude"),
                    location.getDouble("longitude")
                ),
                address = place.optString("formattedAddress"),
                category = category,
                rating = place.optDouble("rating").takeIf { !it.isNaN() },
                description = buildDescription(place).takeIf { it.isNotBlank() },
                // Photos (New) needs a separate billable resolve call per
                // place and nothing in the UI renders POI photos yet
                photoUrl = null,
                placeId = placeId
            )
        }

        /** Human-readable summary from the informational place fields. */
        internal fun buildDescription(place: JSONObject): String {
            val parts = mutableListOf<String>()

            when (place.optString("businessStatus")) {
                "OPERATIONAL" -> "Open"
                "CLOSED_TEMPORARILY" -> "Temporarily Closed"
                "CLOSED_PERMANENTLY" -> "Permanently Closed"
                else -> null
            }?.let { parts.add(it) }

            when (place.optString("priceLevel")) {
                "PRICE_LEVEL_FREE" -> "Free"
                "PRICE_LEVEL_INEXPENSIVE" -> "Inexpensive"
                "PRICE_LEVEL_MODERATE" -> "Moderate"
                "PRICE_LEVEL_EXPENSIVE" -> "Expensive"
                "PRICE_LEVEL_VERY_EXPENSIVE" -> "Very Expensive"
                else -> null
            }?.let { parts.add(it) }

            val rating = place.optDouble("rating")
            val ratingCount = place.optInt("userRatingCount", -1)
            if (!rating.isNaN() && ratingCount >= 0) {
                parts.add("$rating stars ($ratingCount reviews)")
            }

            place.optString("internationalPhoneNumber").takeIf { it.isNotBlank() }
                ?.let { parts.add("Phone: $it") }
            place.optString("websiteUri").takeIf { it.isNotBlank() }
                ?.let { parts.add("Website: $it") }

            return parts.joinToString(" · ")
        }

        private fun JSONArray?.toStringList(): List<String> {
            if (this == null) return emptyList()
            return (0 until length()).map { getString(it) }
        }
    }

    override suspend fun getNearbyPlaces(center: LatLng, radius: Int): List<PointOfInterest> {
        val request = Request.Builder()
            .url("$baseUrl/v1/places:searchNearby")
            .header("X-Goog-Api-Key", apiKey)
            .header("X-Goog-FieldMask", NEARBY_FIELD_MASK)
            .post(
                buildNearbyRequestBody(center, radius)
                    .toRequestBody("application/json".toMediaType())
            )
            .build()

        return parseNearbyResponseOrThrow(execute(request, "Nearby Search"))
    }

    override suspend fun getPlaceDetails(placeId: String): PointOfInterest {
        val request = Request.Builder()
            .url("$baseUrl/v1/places/$placeId")
            .header("X-Goog-Api-Key", apiKey)
            .header("X-Goog-FieldMask", DETAILS_FIELD_MASK)
            .build()

        return parsePlaceDetailsOrThrow(execute(request, "Place Details"))
            ?: throw IOException("Place not found: $placeId")
    }

    /**
     * Run a request and return the body, surfacing authorization problems
     * distinctly (the ViewModel shows a key-configuration message for
     * SecurityException) and everything else as a network-style failure.
     */
    private suspend fun execute(request: Request, context: String): String =
        withContext(Dispatchers.IO) {
            try {
                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string()
                    if (!response.isSuccessful) {
                        Timber.e("$context failed: HTTP ${response.code}")
                        if (response.code == 401 || response.code == 403) {
                            throw SecurityException(
                                "Places API authorization error. Please check API key configuration."
                            )
                        }
                        throw IOException("$context: HTTP ${response.code}")
                    }
                    body ?: throw IOException("Empty $context response")
                }
            } catch (e: IOException) {
                Timber.e(e, "$context failed")
                throw e
            }
        }
}
