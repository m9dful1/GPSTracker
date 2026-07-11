package com.spiritwisestudios.gpstracker

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import org.maplibre.android.MapLibre
import timber.log.Timber

@HiltAndroidApp
class GPSTrackerApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Initialize Timber for logging
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // MapLibre must be initialized before any MapView is inflated.
        // The OpenStreetMap tiles it renders need no API key.
        MapLibre.getInstance(this)
    }
}
