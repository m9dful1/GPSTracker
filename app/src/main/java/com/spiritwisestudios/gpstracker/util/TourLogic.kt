package com.spiritwisestudios.gpstracker.util

import com.spiritwisestudios.gpstracker.domain.model.PointOfInterest
import com.spiritwisestudios.gpstracker.domain.model.UserPreferences

/**
 * Pure tour-mode decision logic, extracted from TourModeService so it can be
 * unit tested.
 */
object TourLogic {

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
