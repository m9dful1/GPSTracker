package com.spiritwisestudios.gpstracker.data.api

import com.spiritwisestudios.gpstracker.domain.model.LatLng

/**
 * Destination search for the navigation search sheet. One implementation
 * per map provider — [GeocodingApiService] (Photon over OpenStreetMap data)
 * and [GoogleGeocodingApiService] (Google Places Text Search) — selected at
 * runtime by the map-provider setting via [SwitchingGeocodingApi].
 */
interface GeocodingApi {

    data class SearchResult(
        val name: String,
        /** Secondary line: street, city, state, country — best effort. */
        val detail: String,
        val latLng: LatLng
    )

    /**
     * Search places and addresses matching a free-text query, biased toward
     * a location when one is known. Empty on failure — search-as-you-type
     * retries on the next keystroke anyway.
     */
    suspend fun search(
        query: String,
        bias: LatLng? = null,
        limit: Int = DEFAULT_LIMIT
    ): List<SearchResult>

    companion object {
        const val DEFAULT_LIMIT = 10
    }
}
