package com.spiritwisestudios.gpstracker.data.api

import com.spiritwisestudios.gpstracker.data.api.GeocodingApi.SearchResult
import com.spiritwisestudios.gpstracker.domain.model.LatLng
import com.spiritwisestudios.gpstracker.util.GeoUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale

/**
 * Destination search backed by Photon (photon.komoot.io) — a free, keyless,
 * typo-tolerant geocoder over OpenStreetMap data, suitable for
 * search-as-you-type. Replaces the Google Places Autocomplete widget.
 */
class GeocodingApiService(
    private val httpClient: OkHttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL
) : GeocodingApi {

    companion object {
        private const val DEFAULT_BASE_URL = "https://photon.komoot.io"
        private const val USER_AGENT = "GPSTracker-TourGuide/1.0 (educational project)"

        /**
         * Photon only matches translated place names when asked in that
         * language; without a lang param, "Eiffel Tower" misses "Tour Eiffel"
         * entirely. The public instance supports these UI languages.
         */
        private val SUPPORTED_LANGUAGES = setOf("en", "de", "fr")

        /**
         * Fetch well past what we show: Photon returns one feature per OSM
         * element, so a bridge split into several ways arrives several times
         * and deduplication needs slack to still fill the requested limit.
         */
        private const val FETCH_LIMIT = 30

        // Wide enough that fragments of a long landmark (the Golden Gate
        // Bridge's OSM ways sit ~1.6 km apart) still collapse into one result.
        private const val DEDUPE_DISTANCE_METERS = 2000f

        // Softer than Photon's defaults, so nearby matches still rank first
        // without burying exact-name matches elsewhere in the world.
        private const val BIAS_ZOOM = 11
        private const val BIAS_SCALE = 0.1

        /** Photon lang value for a device language, falling back to English. */
        internal fun searchLanguage(deviceLanguage: String): String =
            deviceLanguage.lowercase(Locale.ROOT).takeIf { it in SUPPORTED_LANGUAGES } ?: "en"

        internal fun buildSearchUrl(
            baseUrl: String,
            query: String,
            bias: LatLng?,
            language: String
        ): String = buildString {
            append("$baseUrl/api/?q=${URLEncoder.encode(query, "UTF-8")}")
            append("&limit=$FETCH_LIMIT&lang=$language")
            if (bias != null) {
                append("&lat=${bias.latitude}&lon=${bias.longitude}")
                append("&zoom=$BIAS_ZOOM&location_bias_scale=$BIAS_SCALE")
            }
        }

        /**
         * Parse a Photon GeoJSON FeatureCollection into search results.
         * Features without a name or coordinates are dropped.
         */
        internal fun parseSearchResponse(json: String): List<SearchResult> {
            val features = JSONObject(json).optJSONArray("features") ?: return emptyList()

            return (0 until features.length()).mapNotNull { i ->
                val feature = features.getJSONObject(i)
                val properties = feature.optJSONObject("properties") ?: return@mapNotNull null

                val name = properties.optString("name").takeIf { it.isNotBlank() }
                    ?: properties.optString("street").takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                // GeoJSON coordinates are [longitude, latitude]
                val coordinates = feature.optJSONObject("geometry")
                    ?.optJSONArray("coordinates") ?: return@mapNotNull null

                SearchResult(
                    name = name,
                    detail = detailLine(properties, name),
                    latLng = LatLng(coordinates.getDouble(1), coordinates.getDouble(0))
                )
            }
        }

        /**
         * [parseSearchResponse], with a body we can't read treated as no
         * results.
         *
         * [search] promises "empty on failure" and its callers hold it to
         * that: both search sheets launch it into a `lifecycleScope` with no
         * catch of their own, so a `JSONException` from a truncated body or an
         * HTML error page didn't fail the search — it took the app down.
         */
        internal fun parseSearchResponseOrEmpty(json: String): List<SearchResult> =
            try {
                parseSearchResponse(json)
            } catch (e: JSONException) {
                Timber.e(e, "Photon response could not be parsed")
                emptyList()
            }

        /**
         * Collapse same-named results within [DEDUPE_DISTANCE_METERS] of each
         * other, keeping the highest-ranked one. Same-named places far apart
         * (the many Eiffel Towers) are distinct results and all kept.
         */
        internal fun dedupe(results: List<SearchResult>): List<SearchResult> {
            val kept = mutableListOf<SearchResult>()
            for (result in results) {
                val isDuplicate = kept.any {
                    it.name.equals(result.name, ignoreCase = true) &&
                        GeoUtils.distanceMeters(it.latLng, result.latLng) < DEDUPE_DISTANCE_METERS
                }
                if (!isDuplicate) kept += result
            }
            return kept
        }

        /** "Street, City, State, Country" line under the result name, best-effort. */
        internal fun detailLine(properties: JSONObject, name: String): String {
            val street = listOf(
                properties.optString("housenumber"),
                properties.optString("street")
            ).filter { it.isNotBlank() }.joinToString(" ")

            return listOf(
                street,
                properties.optString("city"),
                properties.optString("state"),
                properties.optString("country")
            )
                .filter { it.isNotBlank() && it != name }
                .joinToString(", ")
        }
    }

    /**
     * Search places and addresses matching a free-text query, biased toward
     * a location when one is known.
     */
    override suspend fun search(
        query: String,
        bias: LatLng?,
        limit: Int
    ): List<SearchResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

        val url = buildSearchUrl(
            baseUrl, query, bias,
            searchLanguage(Locale.getDefault().language)
        )

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.e("Photon search failed: HTTP ${response.code}")
                    return@withContext emptyList()
                }
                val body = response.body?.string() ?: return@withContext emptyList()
                dedupe(parseSearchResponseOrEmpty(body)).take(limit)
            }
        } catch (e: IOException) {
            Timber.e(e, "Photon search failed for \"$query\"")
            emptyList()
        }
    }
}
