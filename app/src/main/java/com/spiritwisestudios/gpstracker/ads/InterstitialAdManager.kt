package com.spiritwisestudios.gpstracker.ads

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.spiritwisestudios.gpstracker.BuildConfig
import timber.log.Timber

/**
 * Keeps one interstitial cached and ready. [AdsInitializer] preloads it at
 * startup and on each return to the foreground; [showAd] displays it at a
 * natural break (the end of a drive) and immediately starts loading the
 * next one. Failed loads retry with exponential backoff.
 */
object InterstitialAdManager {

    /**
     * Account-tier gate, set by [AdsInitializer.install]; when it answers
     * false (premium account) nothing loads and [showAd] is a pass-through.
     */
    var adsAllowed: () -> Boolean = { true }

    private var interstitialAd: InterstitialAd? = null
    private var isLoading = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingRetryRunnable: Runnable? = null
    private val retryPolicy = AdRetryPolicy(
        baseDelayMs = if (BuildConfig.DEBUG) 10_000L else 30_000L,
        maxDelayMs = if (BuildConfig.DEBUG) 120_000L else 300_000L
    )

    /** Pre-load an interstitial. Safe to call any time. */
    fun loadAd(context: Context) {
        if (!adsAllowed() || isLoading || interstitialAd != null) return
        isLoading = true
        try {
            InterstitialAd.load(
                context.applicationContext,
                BuildConfig.INTERSTITIAL_AD_UNIT_ID,
                ConsentManager.buildAdRequest(),
                object : InterstitialAdLoadCallback() {
                    override fun onAdLoaded(ad: InterstitialAd) {
                        Timber.d("Interstitial ad loaded")
                        interstitialAd = ad
                        isLoading = false
                        retryPolicy.reset()
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        Timber.w(
                            "Interstitial load failed: code=${error.code}, " +
                                "domain=${error.domain}, message=${error.message}"
                        )
                        interstitialAd = null
                        isLoading = false
                        scheduleRetry(context.applicationContext)
                    }
                }
            )
        } catch (e: Exception) {
            Timber.e(e, "Exception while loading interstitial ad")
            interstitialAd = null
            isLoading = false
        }
    }

    /**
     * Show the cached interstitial if one is ready. [onFinished] runs when
     * the ad is dismissed — or immediately when nothing is ready, so the
     * caller never waits on an ad. Either way the next ad starts loading.
     */
    fun showAd(activity: Activity, onFinished: () -> Unit = {}) {
        if (!adsAllowed()) {
            onFinished()
            return
        }
        val ad = interstitialAd
        if (ad == null) {
            loadAd(activity)
            onFinished()
            return
        }

        try {
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    interstitialAd = null
                    loadAd(activity)
                    onFinished()
                }

                override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                    Timber.w("Interstitial show failed: code=${adError.code}, message=${adError.message}")
                    interstitialAd = null
                    loadAd(activity)
                    onFinished()
                }
            }
            ad.show(activity)
        } catch (e: Exception) {
            Timber.e(e, "Exception while showing interstitial ad")
            interstitialAd = null
            onFinished()
        }
    }

    /** Preload again whenever the app returns to the foreground. */
    fun registerAppLifecycle(application: Application) {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                loadAd(application)
            }
        })
    }

    private fun scheduleRetry(context: Context) {
        pendingRetryRunnable?.let { mainHandler.removeCallbacks(it) }
        val delay = retryPolicy.nextDelayMs()
        val runnable = Runnable {
            pendingRetryRunnable = null
            loadAd(context)
        }
        pendingRetryRunnable = runnable
        mainHandler.postDelayed(runnable, delay)
    }
}
