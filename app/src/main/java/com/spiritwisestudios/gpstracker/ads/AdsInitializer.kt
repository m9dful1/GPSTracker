package com.spiritwisestudios.gpstracker.ads

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.spiritwisestudios.gpstracker.BuildConfig
import timber.log.Timber

/**
 * Starts the Mobile Ads SDK after the first rendered frame so ads never
 * slow down app launch. Once the SDK is up it preloads the interstitial
 * and runs anything queued through [whenInitialized] (the banner load,
 * which needs an activity).
 */
object AdsInitializer {

    private var hasInstalled = false
    private var hasRun = false

    @Volatile
    var isInitialized = false
        private set

    private val pending = mutableListOf<() -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Call once from Application.onCreate. */
    fun install(application: Application) {
        if (hasInstalled) return
        hasInstalled = true

        ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (!hasRun && (event == Lifecycle.Event.ON_START || event == Lifecycle.Event.ON_RESUME)) {
                hasRun = true
                Choreographer.getInstance().postFrameCallback {
                    mainHandler.post { initializeAds(application) }
                }
            }
        })
    }

    /**
     * Run [action] on the main thread once the ads SDK is ready —
     * immediately if it already is.
     */
    fun whenInitialized(action: () -> Unit) {
        synchronized(pending) {
            if (!isInitialized) {
                pending.add(action)
                return
            }
        }
        mainHandler.post(action)
    }

    private fun initializeAds(application: Application) {
        try {
            if (BuildConfig.DEBUG) {
                // Development devices always get test ads, even against
                // real ad unit IDs
                MobileAds.setRequestConfiguration(
                    RequestConfiguration.Builder()
                        .setTestDeviceIds(
                            listOf(
                                AdRequest.DEVICE_ID_EMULATOR,
                                "E4E74455C045F960BC0B3FA74CA59764"
                            )
                        )
                        .build()
                )
            }

            MobileAds.initialize(application) {
                Timber.d("Mobile Ads SDK initialized")
                InterstitialAdManager.registerAppLifecycle(application)
                InterstitialAdManager.loadAd(application)

                val callbacks = synchronized(pending) {
                    isInitialized = true
                    val queued = pending.toList()
                    pending.clear()
                    queued
                }
                callbacks.forEach { mainHandler.post(it) }
            }
        } catch (t: Throwable) {
            Timber.w(t, "Mobile Ads initialization failed")
        }
    }
}
