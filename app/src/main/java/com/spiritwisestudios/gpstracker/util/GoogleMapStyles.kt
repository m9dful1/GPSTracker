package com.spiritwisestudios.gpstracker.util

/**
 * The map styles the layers sheet offers when Google is the map provider,
 * mirroring [MapStyles] for OpenFreeMap. Pure so the normalization and
 * map-type mapping can be unit tested on the JVM — the GoogleMap constants
 * are inlined for the same reason.
 */
object GoogleMapStyles {

    const val DEFAULT = 0
    // Rendered as hybrid: imagery without road labels is useless for
    // finding your way
    const val SATELLITE = 1
    const val TERRAIN = 2

    // GoogleMap.MAP_TYPE_* values
    private const val MAP_TYPE_NORMAL = 1
    private const val MAP_TYPE_TERRAIN = 3
    private const val MAP_TYPE_HYBRID = 4

    /** Stored values from older versions (or garbage) fall back to Default. */
    fun normalize(stored: Int?): Int = when (stored) {
        DEFAULT, SATELLITE, TERRAIN -> stored
        else -> DEFAULT
    }

    /** The GoogleMap map type that renders a selection. */
    fun mapType(style: Int): Int = when (normalize(style)) {
        SATELLITE -> MAP_TYPE_HYBRID
        TERRAIN -> MAP_TYPE_TERRAIN
        else -> MAP_TYPE_NORMAL
    }
}
