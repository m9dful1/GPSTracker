package com.spiritwisestudios.gpstracker

import android.app.Application
import com.google.android.gms.maps.MapsInitializer
import com.google.android.gms.maps.MapsInitializer.Renderer
import com.google.android.gms.maps.OnMapsSdkInitializedCallback
import com.spiritwisestudios.gpstracker.util.ApiKeyManager
import com.google.android.gms.maps.MapsInitializer.setApiKey
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
        
        // Set Google Maps API key programmatically
        val apiKey = ApiKeyManager.getInstance(this).getGoogleMapsApiKey()
        setApiKey(apiKey)
        
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