package com.spiritwisestudios.gpstracker.data.api

import com.spiritwisestudios.gpstracker.data.api.GeocodingApi.SearchResult
import com.spiritwisestudios.gpstracker.domain.model.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException

/**
 * Destination search backed by Google Places Text Search (New): one query
 * matches businesses, landmarks, and street addresses alike, so the same
 * search sheet works unchanged. Needs "Places API (New)" enabled on the
 * Google Cloud project that owns the MAPS_API_KEY; active only when the map
 * provider setting is Google.
 */
class GoogleGeocodingApiService(
    private val httpClient: OkHttpClient,
    private val apiKey: String,
    private val baseUrl: String = DEFAULT_BASE_URL
) : GeocodingApi {

    /** False when no MAPS_API_KEY is configured; callers should fall back. */
    val isConfigured: Boolean
        get() = apiKey.isNotBlank()

    companion object {
        private const val DEFAULT_BASE_URL = "https://places.googleapis.com"

        internal const val FIELD_MASK =
            "places.id,places.displayName,places.formattedAddress,places.location"

        // Softly prefer nearby matches without excluding exact-name matches
        // elsewhere in the world
        private const val BIAS_RADIUS_METERS = 50_000.0

        /** Request body for Text Search (New). */
        internal fun buildSearchRequestBody(query: String, bias: LatLng?, limit: Int): String {
            val body = JSONObject()
                .put("textQuery", query)
                .put("pageSize", limit)
            if (bias != null) {
                body.put(
                    "locationBias", JSONObject().put(
                        "circle", JSONObject()
                            .put(
                                "center", JSONObject()
                                    .put("latitude", bias.latitude)
                                    .put("longitude", bias.longitude)
                            )
                            .put("radius", BIAS_RADIUS_METERS)
                    )
                )
            }
            return body.toString()
        }

        /**
         * Parse a Text Search response into search results. Places without
         * a name or coordinates are dropped.
         */
        internal fun parseSearchResponse(json: String): List<SearchResult> {
            val places = JSONObject(json).optJSONArray("places") ?: return emptyList()

            return (0 until places.length()).mapNotNull { i ->
                val place = places.getJSONObject(i)
                val name = place.optJSONObject("displayName")?.optString("text")
                    ?.takeIf { it.isNotBlank() }
                    ?: place.optString("formattedAddress").takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val location = place.optJSONObject("location") ?: return@mapNotNull null

                SearchResult(
                    name = name,
                    detail = place.optString("formattedAddress").takeIf { it != name } ?: "",
                    latLng = LatLng(
                        location.getDouble("latitude"),
                        location.getDouble("longitude")
                    )
                )
            }
        }
    }

    override suspend fun search(
        query: String,
        bias: LatLng?,
        limit: Int
    ): List<SearchResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

        val request = Request.Builder()
            .url("$baseUrl/v1/places:searchText")
            .header("X-Goog-Api-Key", apiKey)
            .header("X-Goog-FieldMask", FIELD_MASK)
            .post(
                buildSearchRequestBody(query, bias, limit)
                    .toRequestBody("application/json".toMediaType())
            )
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.e("Text Search failed: HTTP ${response.code}")
                    return@withContext emptyList()
                }
                val body = response.body?.string() ?: return@withContext emptyList()
                parseSearchResponse(body).take(limit)
            }
        } catch (e: IOException) {
            Timber.e(e, "Text Search failed for \"$query\"")
            emptyList()
        }
    }
}
