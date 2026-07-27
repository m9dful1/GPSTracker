package com.spiritwisestudios.gpstracker.ads

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import com.google.ads.mediation.admob.AdMobAdapter
import com.google.android.gms.ads.AdRequest
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.spiritwisestudios.gpstracker.BuildConfig
import timber.log.Timber

/**
 * Wraps Google's User Messaging Platform (UMP) consent flow. Ads default
 * to non-personalized and stay that way unless the user grants consent
 * through the UMP form; [buildAdRequest] bakes the current answer into
 * every ad request, so all loads must go through it.
 */
object ConsentManager {

    @Volatile
    private var consentInformation: ConsentInformation? = null

    @Volatile
    private var useNonPersonalizedAds: Boolean = true

    /**
     * Refresh consent info and show the consent form when one is required
     * (e.g. GDPR regions). [onReady] fires whether or not consent was
     * obtained — ads can always be requested, at worst non-personalized.
     */
    fun gatherConsent(activity: Activity, onReady: () -> Unit) {
        try {
            val info = UserMessagingPlatform.getConsentInformation(activity)
            consentInformation = info
            info.requestConsentInfoUpdate(
                activity,
                buildConsentRequestParameters(activity),
                {
                    UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
                        if (formError != null) {
                            Timber.w("Consent form error: ${formError.message}")
                        }
                        useNonPersonalizedAds = ConsentPolicy.useNonPersonalizedAds(stateOf(info))
                        onReady()
                    }
                },
                { updateError ->
                    Timber.w("Consent info update failed: ${updateError.message}")
                    useNonPersonalizedAds = true
                    onReady()
                }
            )
        } catch (e: Exception) {
            Timber.e(e, "Error gathering ad consent")
            useNonPersonalizedAds = true
            onReady()
        }
    }

    fun buildAdRequest(): AdRequest {
        val builder = AdRequest.Builder()
        if (useNonPersonalizedAds) {
            val extras = Bundle().apply { putString("npa", "1") }
            builder.addNetworkExtrasBundle(AdMobAdapter::class.java, extras)
        }
        return builder.build()
    }

    /** Settings entry: let the user change their ad consent at any time. */
    fun showPrivacyOptions(activity: Activity, onComplete: (Boolean) -> Unit = {}) {
        try {
            val info = consentInformation
                ?: UserMessagingPlatform.getConsentInformation(activity).also {
                    consentInformation = it
                }

            // When privacy options are required (the user is in a consent
            // region), show that form directly
            if (info.privacyOptionsRequirementStatus ==
                ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
            ) {
                UserMessagingPlatform.showPrivacyOptionsForm(activity) { formError ->
                    useNonPersonalizedAds = ConsentPolicy.useNonPersonalizedAds(stateOf(info))
                    if (formError != null) {
                        Timber.w("Privacy options error: ${formError.message}")
                        onComplete(false)
                    } else {
                        onComplete(true)
                    }
                }
                return
            }

            // Otherwise reset and rerun the standard consent flow so the
            // form reappears if it applies
            try {
                info.reset()
            } catch (resetError: Exception) {
                Timber.w(resetError, "Consent reset failed; requesting update anyway")
            }

            info.requestConsentInfoUpdate(
                activity,
                buildConsentRequestParameters(activity),
                {
                    UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
                        useNonPersonalizedAds = ConsentPolicy.useNonPersonalizedAds(stateOf(info))
                        if (formError != null) {
                            Timber.w("Consent form flow finished with error: ${formError.message}")
                            onComplete(false)
                        } else {
                            onComplete(true)
                        }
                    }
                },
                { updateError ->
                    Timber.w("Consent info update failed after reset: ${updateError.message}")
                    onComplete(false)
                }
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to show ad privacy options")
            onComplete(false)
        }
    }

    /**
     * UMP's status in this app's own terms. The only place the SDK's constants
     * are named, so the compiler checks the mapping and [ConsentPolicy] can
     * stay free of the ad SDK — and testable.
     */
    private fun stateOf(info: ConsentInformation): ConsentPolicy.ConsentState =
        when (info.consentStatus) {
            ConsentInformation.ConsentStatus.OBTAINED -> ConsentPolicy.ConsentState.OBTAINED
            ConsentInformation.ConsentStatus.NOT_REQUIRED -> ConsentPolicy.ConsentState.NOT_REQUIRED
            ConsentInformation.ConsentStatus.REQUIRED -> ConsentPolicy.ConsentState.REQUIRED
            else -> ConsentPolicy.ConsentState.UNKNOWN
        }

    private fun buildConsentRequestParameters(context: Context): ConsentRequestParameters {
        val builder = ConsentRequestParameters.Builder()
        // Provide the AdMob app ID explicitly (besides the manifest) to
        // avoid detection issues on some devices
        try {
            val ai = context.packageManager.getApplicationInfo(
                context.packageName, PackageManager.GET_META_DATA
            )
            val appId = ai.metaData?.getString("com.google.android.gms.ads.APPLICATION_ID")
            if (!appId.isNullOrBlank()) {
                builder.setAdMobAppId(appId)
            }
        } catch (_: Exception) {
            // UMP falls back to the manifest
        }
        // Debug builds can force the EEA consent form, but only for a device
        // that says so: the hashed id comes from UMP_TEST_DEVICE_HASH in
        // local.properties. It used to be one developer's phone, written into
        // the source, which meant every other clone silently got no form at all
        // — the opposite of what the setting is for.
        if (BuildConfig.DEBUG && BuildConfig.UMP_TEST_DEVICE_HASH.isNotBlank()) {
            try {
                val debugSettings = ConsentDebugSettings.Builder(context)
                    .setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA)
                    .addTestDeviceHashedId(BuildConfig.UMP_TEST_DEVICE_HASH)
                    .build()
                builder.setConsentDebugSettings(debugSettings)
            } catch (_: Exception) {
                // Debug-only conveniences; never block consent on them
            }
        }
        return builder.build()
    }
}
