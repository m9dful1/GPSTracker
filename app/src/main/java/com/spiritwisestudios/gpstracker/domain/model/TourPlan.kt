package com.spiritwisestudios.gpstracker.domain.model

/**
 * How far a planned tour should run, door to door. The distances follow
 * real sightseeing tours: hop-on-hop-off city loops run 5–15 miles, scenic
 * drives (Desert View Drive, 17-Mile Drive) 15–25.
 */
enum class TourLength(val meters: Int, val stopCount: Int) {
    /** A quick loop — about 5 miles, roughly half an hour. */
    SHORT(8_000, 6),

    /** City highlights — about 12 miles, roughly an hour. */
    MEDIUM(19_000, 10),

    /** The grand tour — about 25 miles, two hours or more. */
    LONG(40_000, 15)
}

/** What a planned tour leans toward when choosing places. */
enum class TourFocus {
    BALANCED,
    HISTORY_AND_CULTURE,
    NATURE_AND_VIEWS,
    FOOD_AND_FUN
}

/**
 * A planned tour: the chosen stops in driving order, looping back to the
 * tour center. Navigation drives from the user's actual position through
 * the stops to [destination]; narration and scripts follow the stops.
 */
data class TourPlan(
    val name: String,
    val center: LatLng,
    /** The tour's places, in driving order. */
    val stops: List<PointOfInterest>,
    /** Where the loop ends (the tour center). */
    val destination: LatLng
)
