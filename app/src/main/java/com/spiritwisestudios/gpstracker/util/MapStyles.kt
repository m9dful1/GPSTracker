package com.spiritwisestudios.gpstracker.util

/**
 * The map styles offered by the layers sheet, backed by OpenFreeMap's hosted
 * MapLibre styles (free, keyless, OpenStreetMap data). Pure so the
 * normalization and URL mapping can be unit tested on the JVM.
 */
object MapStyles {

    const val DEFAULT = 0 // Liberty — full-detail general-purpose style
    const val BRIGHT = 1
    const val MINIMAL = 2 // Positron — muted grayscale
    const val DARK = 3

    private const val STYLE_BASE = "https://tiles.openfreemap.org/styles"

    /** Stored values from older versions (or garbage) fall back to Default. */
    fun normalize(stored: Int?): Int = when (stored) {
        DEFAULT, BRIGHT, MINIMAL, DARK -> stored
        else -> DEFAULT
    }

    /**
     * Style URL for a selection. The default style follows the system night
     * mode so night drives aren't a full-screen white blast; explicit
     * choices are respected as-is.
     */
    fun styleUrl(style: Int, nightMode: Boolean): String = when (normalize(style)) {
        BRIGHT -> "$STYLE_BASE/bright"
        MINIMAL -> "$STYLE_BASE/positron"
        DARK -> "$STYLE_BASE/dark"
        else -> if (nightMode) "$STYLE_BASE/dark" else "$STYLE_BASE/liberty"
    }
}
