package com.spiritwisestudios.gpstracker.util

import com.google.android.gms.maps.model.LatLng

/**
 * Picks evenly spaced sample points along a route polyline. Each sample point
 * becomes the center of a nearby-places search, so together they cover a
 * corridor around the route.
 */
object RouteSampler {

    /**
     * Walk the polyline and return points roughly [intervalMeters] apart,
     * always including the first and last route points. Returns an empty list
     * for an empty route.
     */
    fun samplePoints(route: List<LatLng>, intervalMeters: Float): List<LatLng> {
        if (route.isEmpty()) return emptyList()
        if (route.size == 1 || intervalMeters <= 0f) return listOf(route.first())

        val samples = mutableListOf(route.first())
        var distanceSinceLastSample = 0f

        for (i in 0 until route.size - 1) {
            distanceSinceLastSample += GeoUtils.distanceMeters(route[i], route[i + 1])
            if (distanceSinceLastSample >= intervalMeters) {
                samples.add(route[i + 1])
                distanceSinceLastSample = 0f
            }
        }

        if (samples.last() != route.last()) {
            samples.add(route.last())
        }

        return samples
    }
}
