package com.spiritwisestudios.gpstracker.data.api

import com.spiritwisestudios.gpstracker.domain.model.LatLng
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
 * Lists the cities and towns around a point for the Take a Tour picker,
 * via the Overpass API (free, keyless, OpenStreetMap data). Used with
 * either map provider: Google's APIs have no "cities near me" query, and
 * this is supplementary UI data rather than part of the mapping stack.
 */
class NearbyCityApiService(
    private val httpClient: OkHttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL
) {

    data class City(
        val name: String,
        val latLng: LatLng,
        val population: Long?
    )

    companion object {
        private const val DEFAULT_BASE_URL = "https://overpass-api.de"
        private const val USER_AGENT = "GPSTracker-TourGuide/1.0 (educational project)"
        private const val MAX_RESULTS = 10

        /** Overpass QL for named city/town place nodes around a point. */
        internal fun buildCitiesQuery(center: LatLng, radiusMeters: Int): String {
            val around = "around:$radiusMeters,${center.latitude},${center.longitude}"
            return """
                [out:json][timeout:25];
                (
                  node($around)["place"="city"]["name"];
                  node($around)["place"="town"]["name"];
                );
                out body 80;
            """.trimIndent()
        }

        /**
         * Parse the response into cities, nearest first, deduplicated by
         * name (OSM occasionally carries duplicate place nodes).
         */
        internal fun parseCitiesResponse(json: String, center: LatLng): List<City> {
            val elements = JSONObject(json).optJSONArray("elements") ?: return emptyList()

            val cities = (0 until elements.length()).mapNotNull { i ->
                val element = elements.getJSONObject(i)
                val tags = element.optJSONObject("tags") ?: return@mapNotNull null
                val name = tags.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                if (!element.has("lat") || !element.has("lon")) return@mapNotNull null

                City(
                    name = name,
                    latLng = LatLng(element.getDouble("lat"), element.getDouble("lon")),
                    population = tags.optString("population").toLongOrNull()
                )
            }

            return cities
                .sortedBy { GeoUtils.distanceMeters(center, it.latLng) }
                .distinctBy { it.name }
                .take(MAX_RESULTS)
        }
    }

    /** Cities and towns around a location, nearest first; empty on failure. */
    suspend fun nearbyCities(
        center: LatLng,
        radiusMeters: Int = 60_000
    ): List<City> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/api/interpreter")
            .header("User-Agent", USER_AGENT)
            .post(FormBody.Builder().add("data", buildCitiesQuery(center, radiusMeters)).build())
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.e("Nearby cities query failed: HTTP ${response.code}")
                    return@withContext emptyList()
                }
                val body = response.body?.string() ?: return@withContext emptyList()
                parseCitiesResponse(body, center)
            }
        } catch (e: IOException) {
            Timber.e(e, "Nearby cities query failed")
            emptyList()
        } catch (e: JSONException) {
            // A truncated or unexpected body is a failed lookup, not an
            // exception for the caller to handle: this returns "empty on
            // failure", and a JSONException escaping meant the way-of-life
            // watcher logged a mystery error and retried 30 seconds later.
            Timber.e(e, "Nearby cities response could not be parsed")
            emptyList()
        }
    }
}
