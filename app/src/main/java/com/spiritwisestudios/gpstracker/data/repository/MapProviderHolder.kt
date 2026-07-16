package com.spiritwisestudios.gpstracker.data.repository

import com.spiritwisestudios.gpstracker.domain.model.MapProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The map provider currently in effect, readable synchronously anywhere in
 * the app. Seeded from the persisted preference at startup (see
 * GPSTrackerApplication) and updated when the user flips the settings
 * toggle, so the provider-switching services never need an async preference
 * read on the hot path.
 */
@Singleton
class MapProviderHolder @Inject constructor() {

    @Volatile
    var current: MapProvider = MapProvider.OPEN_STREET_MAP
        private set

    fun set(provider: MapProvider) {
        current = provider
    }
}
