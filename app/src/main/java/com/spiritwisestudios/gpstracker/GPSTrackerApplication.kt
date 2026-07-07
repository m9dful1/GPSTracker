package com.spiritwisestudios.gpstracker

import android.app.Application
import com.google.android.gms.maps.MapsInitializer
import com.google.android.gms.maps.MapsInitializer.Renderer
import com.google.android.gms.maps.OnMapsSdkInitializedCallback
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class GPSTrackerApplication : Application(), OnMapsSdkInitializedCallback {

    override fun onCreate() {
        super.onCreate()

        // Initialize Timber for logging
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // The Maps SDK reads its API key from the manifest meta-data entry,
        // which Gradle fills in from MAPS_API_KEY in local.properties.
        if (BuildConfig.MAPS_API_KEY.isEmpty()) {
            Timber.e("MAPS_API_KEY is not set in local.properties — the map will not load")
        }

        // Initialize Maps with the latest renderer
        MapsInitializer.initialize(applicationContext, Renderer.LATEST, this)
    }

    override fun onMapsSdkInitialized(renderer: Renderer) {
        when (renderer) {
            Renderer.LATEST -> Timber.d("Using the latest Maps renderer")
            Renderer.LEGACY -> Timber.d("Using the legacy Maps renderer")
        }
    }
}
