package com.spiritwisestudios.gpstracker

import android.app.Application
import com.spiritwisestudios.gpstracker.ads.AdsInitializer
import com.spiritwisestudios.gpstracker.data.repository.AccountTierHolder
import com.spiritwisestudios.gpstracker.data.repository.MapProviderHolder
import com.spiritwisestudios.gpstracker.data.repository.UserPreferencesRepository
import com.spiritwisestudios.gpstracker.domain.model.MapProvider
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.maplibre.android.MapLibre
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class GPSTrackerApplication : Application() {

    @Inject
    lateinit var userPreferencesRepository: UserPreferencesRepository

    @Inject
    lateinit var mapProviderHolder: MapProviderHolder

    @Inject
    lateinit var accountTierHolder: AccountTierHolder

    override fun onCreate() {
        super.onCreate()

        // Initialize Timber for logging
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // MapLibre must be initialized before any MapView is inflated.
        // The OpenStreetMap tiles it renders need no API key.
        MapLibre.getInstance(this)

        // Seed the provider and tier holders before any activity needs them
        // — a blocking read, but of one small DataStore file, once per
        // process. Google without a key falls back to OpenStreetMap so a
        // removed key can't leave the app on a blank map.
        val (storedProvider, storedTier) = runBlocking {
            userPreferencesRepository.mapProviderFlow.first() to
                userPreferencesRepository.accountTierFlow.first()
        }
        mapProviderHolder.set(
            if (storedProvider == MapProvider.GOOGLE && BuildConfig.MAPS_API_KEY.isBlank()) {
                MapProvider.OPEN_STREET_MAP
            } else {
                storedProvider
            }
        )
        accountTierHolder.set(storedTier)

        // Ads start after the first rendered frame so they never slow
        // launch — and only for standard accounts; premium rides ad-free
        AdsInitializer.install(this) { !accountTierHolder.isPremium }
    }
}
