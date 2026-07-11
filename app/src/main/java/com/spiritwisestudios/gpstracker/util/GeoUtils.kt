package com.spiritwisestudios.gpstracker.util

import com.spiritwisestudios.gpstracker.domain.model.LatLng
import kotlin.math.asin
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
     * Points approximating a circle around a center, for drawing circular
     * overlays on maps whose annotation API has no circle primitive. The
     * ring is closed (first point repeated at the end).
     */
    fun circlePoints(center: LatLng, radiusMeters: Double, steps: Int = 64): List<LatLng> {
        val latRadius = Math.toDegrees(radiusMeters / EARTH_RADIUS_METERS)
        val lonRadius = latRadius / cos(Math.toRadians(center.latitude))

        return (0..steps).map { i ->
            val angle = 2.0 * Math.PI * i / steps
            LatLng(
                center.latitude + latRadius * sin(angle),
                center.longitude + lonRadius * cos(angle)
            )
        }
    }

    /**
     * The point [distanceMeters] away from [start] along [bearingDegrees]
     * (great-circle destination point).
     */
    fun offsetMeters(start: LatLng, bearingDegrees: Float, distanceMeters: Float): LatLng {
        val angular = distanceMeters / EARTH_RADIUS_METERS
        val bearing = Math.toRadians(bearingDegrees.toDouble())
        val lat1 = Math.toRadians(start.latitude)
        val lon1 = Math.toRadians(start.longitude)

        val lat2 = asin(sin(lat1) * cos(angular) + cos(lat1) * sin(angular) * cos(bearing))
        val lon2 = lon1 + atan2(
            sin(bearing) * sin(angular) * cos(lat1),
            cos(angular) - sin(lat1) * sin(lat2)
        )
        return LatLng(Math.toDegrees(lat2), Math.toDegrees(lon2))
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
