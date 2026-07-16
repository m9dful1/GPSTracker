package com.spiritwisestudios.gpstracker.data.api

import com.spiritwisestudios.gpstracker.data.api.GeocodingApi.SearchResult
import com.spiritwisestudios.gpstracker.data.api.RoutingApi.Route
import com.spiritwisestudios.gpstracker.data.repository.MapProviderHolder
import com.spiritwisestudios.gpstracker.domain.model.LatLng
import com.spiritwisestudios.gpstracker.domain.model.MapProvider
import com.spiritwisestudios.gpstracker.domain.model.PointOfInterest

/**
 * Provider-switching facades: every call goes to the service matching the
 * current map-provider setting, so flipping the settings toggle switches
 * the data sources immediately — no re-injection anywhere. Google without a
 * configured key falls back to the OpenStreetMap services, which need none.
 */

// OSM element ids ("node/123", "way/456", "relation/789")
private val OSM_ID_REGEX = Regex("^(node|way|relation)/\\d+$")

private fun MapProviderHolder.wantsGoogle(): Boolean = current == MapProvider.GOOGLE

class SwitchingPlacesApi(
    private val mapProviderHolder: MapProviderHolder,
    private val openStreetMap: PlacesApiService,
    private val google: GooglePlacesApiService
) : PlacesApi {

    override suspend fun getNearbyPlaces(center: LatLng, radius: Int): List<PointOfInterest> {
        val active = if (mapProviderHolder.wantsGoogle() && google.isConfigured) google else openStreetMap
        return active.getNearbyPlaces(center, radius)
    }

    override suspend fun getPlaceDetails(placeId: String): PointOfInterest {
        // Place ids encode their origin (OSM ids look like "node/123"), so
        // details are routed by id rather than by the active provider —
        // POIs discovered before a provider switch keep resolving.
        return when {
            OSM_ID_REGEX.matches(placeId) -> openStreetMap.getPlaceDetails(placeId)
            google.isConfigured -> google.getPlaceDetails(placeId)
            else -> openStreetMap.getPlaceDetails(placeId) // fails with a clear error
        }
    }
}

class SwitchingGeocodingApi(
    private val mapProviderHolder: MapProviderHolder,
    private val openStreetMap: GeocodingApiService,
    private val google: GoogleGeocodingApiService
) : GeocodingApi {

    override suspend fun search(query: String, bias: LatLng?, limit: Int): List<SearchResult> {
        val active = if (mapProviderHolder.wantsGoogle() && google.isConfigured) google else openStreetMap
        return active.search(query, bias, limit)
    }
}

class SwitchingRoutingApi(
    private val mapProviderHolder: MapProviderHolder,
    private val openStreetMap: RoutingApiService,
    private val google: GoogleRoutingApiService
) : RoutingApi {

    override suspend fun getRoute(
        origin: LatLng,
        destination: LatLng,
        waypoints: List<LatLng>
    ): Route? {
        val active = if (mapProviderHolder.wantsGoogle() && google.isConfigured) google else openStreetMap
        return active.getRoute(origin, destination, waypoints)
    }
}
