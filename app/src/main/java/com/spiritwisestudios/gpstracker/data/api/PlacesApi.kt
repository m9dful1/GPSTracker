package com.spiritwisestudios.gpstracker.data.api

import com.spiritwisestudios.gpstracker.domain.model.LatLng
import com.spiritwisestudios.gpstracker.domain.model.PointOfInterest

/**
 * Discovers tour-worthy points of interest. One implementation per map
 * provider — [PlacesApiService] (Overpass over OpenStreetMap data) and
 * [GooglePlacesApiService] (Google Places API) — selected at runtime by the
 * map-provider setting via [SwitchingPlacesApi].
 */
interface PlacesApi {

    /** Find tour-worthy points of interest around a location. */
    suspend fun getNearbyPlaces(center: LatLng, radius: Int): List<PointOfInterest>

    /** Detailed information about a specific place; throws when unavailable. */
    suspend fun getPlaceDetails(placeId: String): PointOfInterest
}
