package com.spiritwisestudios.gpstracker.domain.model

/**
 * Neutral coordinate pair — a drop-in replacement for the Google Maps SDK
 * LatLng the codebase used before the MapLibre migration. Gson serializes
 * the same field names, so POIs cached in Room under the old type still
 * deserialize.
 */
data class LatLng(
    val latitude: Double,
    val longitude: Double
) {
    override fun toString(): String = "lat/lng: ($latitude,$longitude)"
}
