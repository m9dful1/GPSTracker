package com.spiritwisestudios.gpstracker.data.api

import com.spiritwisestudios.gpstracker.domain.model.LatLng
import com.spiritwisestudios.gpstracker.util.GeoUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.net.URLEncoder

/**
 * Fetches real facts about places from Wikipedia (free, no API key).
 *
 * Uses the MediaWiki geosearch API to find articles near a coordinate, then
 * pulls the article's intro extract as narration material.
 */
class WikipediaApiService(private val httpClient: OkHttpClient) {

    data class GeoSearchResult(
        val pageId: Long,
        val title: String,
        val distanceMeters: Double
    )

    data class WikiArticle(
        val pageId: Long,
        val title: String,
        val extract: String,
        val url: String
    )

    companion object {
        private const val API_BASE = "https://en.wikipedia.org/w/api.php"
        private const val USER_AGENT = "GPSTracker-TourGuide/1.0 (educational project)"

        /**
         * Parse a geosearch response into results, nearest first.
         */
        internal fun parseGeoSearchResponse(json: String): List<GeoSearchResult> {
            val root = JSONObject(json)
            val geosearch = root.optJSONObject("query")?.optJSONArray("geosearch")
                ?: return emptyList()

            return (0 until geosearch.length()).map { i ->
                val entry = geosearch.getJSONObject(i)
                GeoSearchResult(
                    pageId = entry.getLong("pageid"),
                    title = entry.getString("title"),
                    distanceMeters = entry.optDouble("dist", Double.MAX_VALUE)
                )
            }
        }

        /**
         * Parse an extracts response into the intro text of the first page.
         */
        internal fun parseExtractResponse(json: String): String? {
            val pages = JSONObject(json).optJSONObject("query")?.optJSONObject("pages")
                ?: return null
            val firstPageKey = pages.keys().asSequence().firstOrNull() ?: return null
            return pages.getJSONObject(firstPageKey)
                .optString("extract", "")
                .takeIf { it.isNotBlank() }
        }

        /**
         * Word-overlap score between a POI name and an article title in [0, 1].
         */
        internal fun titleMatchScore(poiName: String, articleTitle: String): Double {
            val nameTokens = tokenize(poiName)
            val titleTokens = tokenize(articleTitle)
            if (nameTokens.isEmpty() || titleTokens.isEmpty()) return 0.0

            val intersection = nameTokens.intersect(titleTokens).size.toDouble()
            return intersection / minOf(nameTokens.size, titleTokens.size)
        }

        /**
         * Pick the candidate whose title best matches the POI name, or null if
         * nothing clears the match threshold.
         */
        internal fun pickBestArticle(
            poiName: String,
            candidates: List<GeoSearchResult>
        ): GeoSearchResult? {
            return candidates
                .map { it to titleMatchScore(poiName, it.title) }
                .filter { (_, score) -> score >= 0.5 }
                .maxByOrNull { (_, score) -> score }
                ?.first
        }

        private fun tokenize(text: String): Set<String> {
            return text.lowercase()
                .replace(Regex("[^a-z0-9 ]"), " ")
                .split(Regex("\\s+"))
                .filter { it.length > 2 && it !in setOf("the", "and", "for", "los", "las", "san") }
                .toSet()
        }

        /**
         * Parse a by-title lookup (extract + coordinates) into an article,
         * or null when the page is missing, empty, or not about a place
         * near [near]. The coordinate check is the topic validator: "Reno"
         * redirects to the city article (which carries coordinates by the
         * listener), while a disambiguation page has no coordinates and a
         * same-named town elsewhere fails the distance test.
         */
        internal fun parseTitleLookup(
            json: String,
            near: LatLng,
            maxDistanceMeters: Float
        ): WikiArticle? {
            val pages = JSONObject(json).optJSONObject("query")?.optJSONObject("pages")
                ?: return null
            val firstPageKey = pages.keys().asSequence().firstOrNull() ?: return null
            val page = pages.getJSONObject(firstPageKey)
            if (page.has("missing")) return null

            val pageId = page.optLong("pageid", -1L)
            val extract = page.optString("extract", "").takeIf { it.isNotBlank() }
            val coordinates = page.optJSONArray("coordinates")
                ?.takeIf { it.length() > 0 }?.getJSONObject(0)
            if (pageId <= 0 || extract == null || coordinates == null) return null

            val articleLatLng = LatLng(coordinates.getDouble("lat"), coordinates.getDouble("lon"))
            if (GeoUtils.distanceMeters(near, articleLatLng) > maxDistanceMeters) return null

            return WikiArticle(
                pageId = pageId,
                title = page.optString("title", ""),
                extract = extract,
                url = "https://en.wikipedia.org/?curid=$pageId"
            )
        }
    }

    /**
     * Find the Wikipedia article that best describes a place.
     *
     * @param name POI name used for title matching
     * @param location POI coordinates
     * @param radiusMeters Search radius around the POI
     * @param allowNearestFallback When true (landmark-like places), fall back
     *   to the nearest article within 100m if no title matches
     */
    suspend fun findArticleFor(
        name: String,
        location: LatLng,
        radiusMeters: Int = 300,
        allowNearestFallback: Boolean = false
    ): WikiArticle? {
        val candidates = try {
            geoSearch(location, radiusMeters, limit = 8)
        } catch (e: Exception) {
            Timber.e(e, "Wikipedia geosearch failed for $name")
            return null
        }
        if (candidates.isEmpty()) return null

        val best = pickBestArticle(name, candidates)
            ?: candidates.firstOrNull { allowNearestFallback && it.distanceMeters <= 100.0 }
            ?: return null

        val extract = try {
            fetchExtract(best.pageId)
        } catch (e: Exception) {
            Timber.e(e, "Wikipedia extract fetch failed for ${best.title}")
            null
        } ?: return null

        return WikiArticle(
            pageId = best.pageId,
            title = best.title,
            extract = extract,
            url = "https://en.wikipedia.org/?curid=${best.pageId}"
        )
    }

    /**
     * The article for a known name — a city or region rather than a POI,
     * where geosearch would surface the buildings downtown instead of the
     * city's own article. Redirects are followed ("Reno" lands on "Reno,
     * Nevada") and the result must carry coordinates within
     * [maxDistanceMeters] of [near], which filters out disambiguation
     * pages and same-named places elsewhere.
     */
    suspend fun findArticleByTitle(
        title: String,
        near: LatLng,
        maxDistanceMeters: Float = 50_000f
    ): WikiArticle? = withContext(Dispatchers.IO) {
        val url = "$API_BASE?action=query&prop=extracts%7Ccoordinates" +
                "&exintro=1&explaintext=1&redirects=1" +
                "&titles=${URLEncoder.encode(title, "UTF-8")}&format=json"
        try {
            parseTitleLookup(get(url), near, maxDistanceMeters)
        } catch (e: Exception) {
            Timber.e(e, "Wikipedia title lookup failed for $title")
            null
        }
    }

    /**
     * Articles near a coordinate, nearest first.
     */
    suspend fun geoSearch(location: LatLng, radiusMeters: Int, limit: Int): List<GeoSearchResult> =
        withContext(Dispatchers.IO) {
            // gsradius must be within [10, 10000]
            val radius = radiusMeters.coerceIn(10, 10000)
            val url = "$API_BASE?action=query&list=geosearch" +
                    "&gscoord=${location.latitude}%7C${location.longitude}" +
                    "&gsradius=$radius&gslimit=$limit&format=json"
            parseGeoSearchResponse(get(url))
        }

    /**
     * Plain-text intro extract of an article.
     */
    suspend fun fetchExtract(pageId: Long): String? = withContext(Dispatchers.IO) {
        val url = "$API_BASE?action=query&prop=extracts&exintro=1&explaintext=1" +
                "&pageids=$pageId&format=json"
        parseExtractResponse(get(url))
    }

    private fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Wikipedia API HTTP ${response.code}")
            }
            return response.body?.string() ?: throw IOException("Empty Wikipedia response")
        }
    }
}
