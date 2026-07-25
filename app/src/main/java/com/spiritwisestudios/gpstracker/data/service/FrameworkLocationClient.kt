package com.spiritwisestudios.gpstracker.data.service

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
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
     * and displacement. Fixes are delivered the moment the provider
     * produces them ([LocationRequestCompat.Builder.setMinUpdateIntervalMillis]
     * of zero) — the legacy minTime API instead throttles delivery to the
     * interval boundary, adding up to a full interval of latency per fix.
     */
    @SuppressLint("MissingPermission")
    fun requestUpdates(
        minIntervalMs: Long,
        minDistanceMeters: Float,
        listener: LocationListenerCompat
    ) {
        val request = LocationRequestCompat.Builder(minIntervalMs)
            .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
            .setMinUpdateIntervalMillis(0)
            .setMinUpdateDistanceMeters(minDistanceMeters)
            .build()
        try {
            LocationManagerCompat.requestLocationUpdates(
                locationManager,
                bestProvider(),
                request,
                listener,
                Looper.getMainLooper()
            )
        } catch (e: IllegalArgumentException) {
            Timber.e(e, "Location provider unavailable")
        }
    }

    /**
     * Stop delivering to [listener].
     *
     * Lint asks for a location permission here because the compat wrapper
     * shares its annotation with the request side. Giving updates *up* can't
     * expose a location, and this has to work even when the permission has
     * just been revoked — which is precisely when it matters most.
     */
    @SuppressLint("MissingPermission")
    fun removeUpdates(listener: LocationListenerCompat) {
        LocationManagerCompat.removeUpdates(locationManager, listener)
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
