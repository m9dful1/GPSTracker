package com.spiritwisestudios.gpstracker.data.service

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.location.LocationListenerCompat
import timber.log.Timber

/**
 * Thin wrapper over the framework [LocationManager], replacing the Play
 * Services fused client so the app also runs on devices without Google
 * services. Callers are responsible for holding the location permission.
 */
class FrameworkLocationClient(context: Context) {

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    /**
     * Best provider for a high-accuracy outdoor app: GPS when available,
     * else the platform fused provider (API 31+), else network.
     */
    private fun bestProvider(): String {
        val providers = locationManager.allProviders
        return when {
            providers.contains(LocationManager.GPS_PROVIDER) &&
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ->
                LocationManager.GPS_PROVIDER
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                providers.contains(LocationManager.FUSED_PROVIDER) ->
                LocationManager.FUSED_PROVIDER
            else -> LocationManager.NETWORK_PROVIDER
        }
    }

    /**
     * Stream location updates to [listener] on the main looper. Calling
     * again with the same listener re-registers it with the new interval
     * and displacement.
     */
    @SuppressLint("MissingPermission")
    fun requestUpdates(
        minIntervalMs: Long,
        minDistanceMeters: Float,
        listener: LocationListenerCompat
    ) {
        try {
            locationManager.requestLocationUpdates(
                bestProvider(),
                minIntervalMs,
                minDistanceMeters,
                listener,
                Looper.getMainLooper()
            )
        } catch (e: IllegalArgumentException) {
            Timber.e(e, "Location provider unavailable")
        }
    }

    fun removeUpdates(listener: LocationListenerCompat) {
        locationManager.removeUpdates(listener)
    }

    /** The freshest fix any provider has, or null when there is none yet. */
    @SuppressLint("MissingPermission")
    fun lastKnownLocation(): Location? {
        return locationManager.allProviders
            .mapNotNull { provider ->
                try {
                    locationManager.getLastKnownLocation(provider)
                } catch (e: IllegalArgumentException) {
                    null
                }
            }
            .maxByOrNull { it.time }
    }
}
