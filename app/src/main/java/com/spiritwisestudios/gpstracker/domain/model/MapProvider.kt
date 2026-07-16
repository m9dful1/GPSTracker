package com.spiritwisestudios.gpstracker.domain.model

/**
 * Which mapping stack renders the map and answers place, search, and route
 * queries. A user setting: OpenStreetMap services (MapLibre/OpenFreeMap,
 * Overpass, Photon, Valhalla) are free and keyless; Google Maps (Maps SDK,
 * Places API, Routes API) needs a MAPS_API_KEY in local.properties.
 */
enum class MapProvider {
    OPEN_STREET_MAP,
    GOOGLE;

    companion object {
        /** Stored names from any app version fall back to OpenStreetMap. */
        fun fromStorage(name: String?): MapProvider =
            entries.firstOrNull { it.name == name } ?: OPEN_STREET_MAP
    }
}
