package com.spiritwisestudios.gpstracker.data.api

import com.spiritwisestudios.gpstracker.domain.model.LatLng
import com.spiritwisestudios.gpstracker.domain.service.NavigationService

/**
 * Turn-by-turn route computation. One implementation per map provider —
 * [RoutingApiService] (Valhalla over OpenStreetMap data) and
 * [GoogleRoutingApiService] (Google Routes API) — selected at runtime by
 * the map-provider setting via [SwitchingRoutingApi].
 */
interface RoutingApi {

    data class Route(
        val points: List<LatLng>,
        val distanceMeters: Float,
        val durationMillis: Long,
        val instructions: List<NavigationService.NavigationInstruction>
    )

    /**
     * Compute a route, or null when the server is unreachable or returns
     * nothing usable. Waypoints are routed through in the given order.
     */
    suspend fun getRoute(
        origin: LatLng,
        destination: LatLng,
        waypoints: List<LatLng> = emptyList()
    ): Route?
}
