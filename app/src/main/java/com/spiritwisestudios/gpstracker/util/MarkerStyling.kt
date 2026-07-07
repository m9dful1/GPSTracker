package com.spiritwisestudios.gpstracker.util

/**
 * Pure mapping from POI attributes to map-marker styling, kept free of Maps
 * SDK classes so it can be unit tested on the JVM.
 */
object MarkerStyling {

    // Same degree values as BitmapDescriptorFactory.HUE_* (which live in the
    // Maps SDK and would drag the Android framework into unit tests)
    const val HUE_ORANGE = 30f
    const val HUE_YELLOW = 60f
    const val HUE_GREEN = 120f
    const val HUE_CYAN = 180f
    const val HUE_AZURE = 210f
    const val HUE_VIOLET = 270f
    const val HUE_ROSE = 330f

    /** Markers for already-narrated places fade into the background. */
    const val VISITED_ALPHA = 0.45f
    const val DEFAULT_ALPHA = 1.0f

    /**
     * Marker hue for a POI category, so the map reads at a glance:
     * green = nature, violet = culture, orange = history, ...
     */
    fun hueFor(category: String): Float {
        return when (category.uppercase()) {
            "CULTURAL" -> HUE_VIOLET
            "HISTORICAL", "ARCHITECTURAL" -> HUE_ORANGE
            "NATURAL" -> HUE_GREEN
            "ENTERTAINMENT" -> HUE_YELLOW
            "DINING" -> HUE_ROSE
            "SHOPPING" -> HUE_CYAN
            else -> HUE_AZURE
        }
    }

    fun alphaFor(isVisited: Boolean): Float {
        return if (isVisited) VISITED_ALPHA else DEFAULT_ALPHA
    }
}
