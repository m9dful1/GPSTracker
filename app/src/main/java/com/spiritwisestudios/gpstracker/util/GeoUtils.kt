package com.spiritwisestudios.gpstracker.util

import com.google.android.gms.maps.model.LatLng
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure geographic math helpers, kept free of Android framework classes so they
 * can be unit tested on the JVM.
 */
object GeoUtils {

    private const val EARTH_RADIUS_METERS = 6371000.0

    /**
     * Great-circle distance between two points in meters (haversine formula).
     */
    fun distanceMeters(start: LatLng, end: LatLng): Float {
        val dLat = Math.toRadians(end.latitude - start.latitude)
        val dLon = Math.toRadians(end.longitude - start.longitude)

        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(start.latitude)) * cos(Math.toRadians(end.latitude)) *
                sin(dLon / 2) * sin(dLon / 2)

        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return (EARTH_RADIUS_METERS * c).toFloat()
    }

    /**
     * Initial bearing from start to end in degrees [0, 360).
     */
    fun bearingDegrees(start: LatLng, end: LatLng): Float {
        val lat1 = Math.toRadians(start.latitude)
        val lat2 = Math.toRadians(end.latitude)
        val dLon = Math.toRadians(end.longitude - start.longitude)

        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)

        val bearing = Math.toDegrees(atan2(y, x))
        return ((bearing + 360) % 360).toFloat()
    }
}
