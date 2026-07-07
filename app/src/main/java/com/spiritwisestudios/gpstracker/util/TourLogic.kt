package com.spiritwisestudios.gpstracker.util

import com.spiritwisestudios.gpstracker.domain.model.PointOfInterest
import com.spiritwisestudios.gpstracker.domain.model.UserPreferences

/**
 * Pure tour-mode decision logic, extracted from TourModeService so it can be
 * unit tested.
 */
object TourLogic {

    /** Minimum speed before a GPS bearing is steady enough to narrate from. */
    const val MIN_HEADING_SPEED_MPS = 1.0f

    /**
     * Where a POI sits relative to the direction of travel.
     */
    enum class RelativeDirection { AHEAD, RIGHT, BEHIND, LEFT }

    /**
     * Classify a POI's bearing relative to the travel heading into the
     * quadrant a tour guide would call out. Each quadrant is 90° wide,
     * centered on the nose, right door, tail, and left door.
     */
    fun relativeDirection(travelHeading: Float, bearingToPoi: Float): RelativeDirection {
        val delta = ((bearingToPoi - travelHeading) % 360f + 360f) % 360f
        return when {
            delta < 45f || delta >= 315f -> RelativeDirection.AHEAD
            delta < 135f -> RelativeDirection.RIGHT
            delta < 225f -> RelativeDirection.BEHIND
            else -> RelativeDirection.LEFT
        }
    }

    /**
     * Spoken introduction for a POI narration. Falls back to a neutral
     * phrase when the travel direction is unknown (e.g. stationary).
     */
    fun narrationIntroFor(poiName: String, direction: RelativeDirection?): String {
        return when (direction) {
            RelativeDirection.AHEAD -> "Just ahead: $poiName."
            RelativeDirection.RIGHT -> "On your right: $poiName."
            RelativeDirection.BEHIND -> "Just behind you: $poiName."
            RelativeDirection.LEFT -> "On your left: $poiName."
            null -> "Coming up: $poiName."
        }
    }

    /**
     * Closing line spoken on arrival, summarizing the drive's tour.
     * Null when nothing was narrated — stay quiet rather than announce
     * an empty tour.
     */
    fun tripSummaryPhrase(narratedCount: Int): String? {
        return when {
            narratedCount <= 0 -> null
            narratedCount == 1 -> "That concludes today's tour: you heard about 1 place along the way."
            else -> "That concludes today's tour: you heard about $narratedCount places along the way."
        }
    }

    /**
     * Cap the narration detail level by travel speed: fast travel leaves
     * less time per place (and more places per minute), so facts get
     * shorter. Never exceeds the user's preferred level.
     */
    fun detailLevelFor(
        speedMetersPerSecond: Float,
        preferred: UserPreferences.DetailLevel
    ): UserPreferences.DetailLevel {
        val speedKmh = speedMetersPerSecond * 3.6f
        val speedCap = when {
            speedKmh < 15.0f -> UserPreferences.DetailLevel.DETAILED // on foot or cycling
            speedKmh < 80.0f -> UserPreferences.DetailLevel.MEDIUM   // city driving
            else -> UserPreferences.DetailLevel.BRIEF                 // highway
        }
        // Enum order is BRIEF < MEDIUM < DETAILED, so min() picks the shorter
        return minOf(preferred, speedCap)
    }

    /**
     * Calculate an appropriate geofence radius based on movement speed.
     * Faster speeds require larger geofences to provide timely notifications.
     */
    fun geofenceRadiusFor(speedMetersPerSecond: Float, baseRadius: Int): Int {
        val speedKmh = speedMetersPerSecond * 3.6f

        return when {
            speedKmh < 2.0f -> baseRadius // Walking slowly or stationary
            speedKmh < 7.0f -> (baseRadius * 1.5).toInt() // Walking
            speedKmh < 15.0f -> (baseRadius * 2.0).toInt() // Jogging or cycling
            speedKmh < 40.0f -> (baseRadius * 3.0).toInt() // Driving in city
            speedKmh < 80.0f -> (baseRadius * 5.0).toInt() // Driving on highway
            else -> (baseRadius * 8.0).toInt() // Very fast movement
        }
    }

    /**
     * Calculate content delivery priority for a POI. Higher means sooner.
     * Combines the alert-based base priority with rating, user category
     * preferences, and whether the place was already visited.
     */
    fun contentPriorityFor(
        poi: PointOfInterest,
        preferences: UserPreferences,
        basePriority: Int
    ): Int {
        var priority = basePriority

        // POI rating (0-5 scale, add 0-3 priority points)
        poi.rating?.let { rating ->
            priority += when {
                rating >= 4.5 -> 3
                rating >= 4.0 -> 2
                rating >= 3.5 -> 1
                else -> 0
            }
        }

        // Preferred categories get a boost
        val poiCategory = try {
            PointOfInterest.Category.valueOf(poi.category.uppercase())
        } catch (e: IllegalArgumentException) {
            null
        }
        if (poiCategory != null && preferences.preferredCategories.contains(poiCategory)) {
            priority += 2
        }

        // Already-visited places drop down the queue
        if (poi.isVisited) {
            priority -= 3
        }

        return priority.coerceAtLeast(0)
    }
}
