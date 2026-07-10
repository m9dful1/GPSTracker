package com.spiritwisestudios.gpstracker.util

import com.spiritwisestudios.gpstracker.domain.model.PointOfInterest
import com.spiritwisestudios.gpstracker.domain.model.UserPreferences
import kotlin.math.roundToInt

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
     * phrase when the travel direction is unknown (e.g. stationary), and
     * mentions the distance when one is provided ("On your right, about
     * 500 feet: Fort Point.") so the listener knows when to look, not
     * just where.
     */
    fun narrationIntroFor(
        poiName: String,
        direction: RelativeDirection?,
        distancePhrase: String? = null
    ): String {
        val lead = when (direction) {
            RelativeDirection.AHEAD -> "Just ahead"
            RelativeDirection.RIGHT -> "On your right"
            RelativeDirection.BEHIND -> "Just behind you"
            RelativeDirection.LEFT -> "On your left"
            null -> "Coming up"
        }
        return if (distancePhrase != null) {
            "$lead, $distancePhrase: $poiName."
        } else {
            "$lead: $poiName."
        }
    }

    /**
     * A distance rounded for speech, in imperial units. GPS and geofence
     * jitter make precise numbers fake, so values are rounded coarsely;
     * within 75 m (~250 ft) a callout is noise ("you're there"), so null
     * is returned.
     */
    fun distancePhrase(distanceMeters: Float): String? {
        if (distanceMeters < 75f) return null
        val feet = distanceMeters * 3.28084f
        val miles = distanceMeters / 1609.344f
        return when {
            feet < 1000f -> {
                val rounded = (feet / 100f).roundToInt() * 100
                "about $rounded feet"
            }
            miles < 0.875f -> when ((miles * 4f).roundToInt()) {
                1 -> "about a quarter mile"
                2 -> "about half a mile"
                else -> "about three quarters of a mile"
            }
            else -> {
                // Nearest half mile: "about 1 mile", "about 1.5 miles"
                val halfMileUnits = (miles * 2f).roundToInt()
                if (halfMileUnits % 2 == 0) {
                    val wholeMiles = halfMileUnits / 2
                    "about $wholeMiles mile${if (wholeMiles == 1) "" else "s"}"
                } else {
                    "about ${halfMileUnits / 2.0} miles"
                }
            }
        }
    }

    /**
     * Whether bulk content prefetching is allowed right now. On-demand
     * narration fetches are always allowed — this only gates the
     * speculative batch downloads, which are the data-hungry part.
     */
    fun shouldPrefetchContent(allowMobileData: Boolean, onUnmeteredNetwork: Boolean): Boolean {
        return allowMobileData || onUnmeteredNetwork
    }

    /**
     * Spoken confirmation when tour mode starts, so the user hears that
     * audio works and knows what to expect instead of getting silence
     * until the first geofence fires.
     */
    fun tourStartAnnouncement(placeCount: Int): String {
        return when {
            placeCount <= 0 -> "Tour mode is on. No interesting places nearby yet, but I'm keeping an eye out as you move."
            placeCount == 1 -> "Tour mode is on. There's 1 interesting place nearby. I'll tell you about it as you get close."
            else -> "Tour mode is on. I found $placeCount interesting places nearby. I'll tell you about them as we go."
        }
    }

    /**
     * Spoken preview when a navigation route's corridor is registered.
     * Null when the route has nothing to narrate — the navigation prompts
     * are already talking, so don't add noise.
     */
    fun corridorAnnouncement(placeCount: Int): String? {
        return when {
            placeCount <= 0 -> null
            placeCount == 1 -> "Your route passes 1 interesting place. I'll point it out on the way."
            else -> "Your route passes $placeCount interesting places. I'll point them out on the way."
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

    /** Sliding window for the narrations-per-hour cap. */
    const val NARRATION_WINDOW_MS = 60L * 60L * 1000L

    /** How long the guide stays quiet about a place it already narrated. */
    const val NARRATION_REVISIT_COOLDOWN_MS = 14L * 24L * 60L * 60L * 1000L

    /**
     * Whether to skip auto-narrating an already-visited place. A guide that
     * repeats itself daily is annoying, but one that never repeats anything
     * goes permanently silent on a commute — so narrated places become
     * eligible again once the cooldown passes. Visited places without a
     * timestamp stay skipped (their age is unknown).
     */
    fun shouldSkipNarration(isVisited: Boolean, visitedDate: Long?, nowMillis: Long): Boolean {
        if (!isVisited) return false
        if (visitedDate == null) return true
        return nowMillis - visitedDate < NARRATION_REVISIT_COOLDOWN_MS
    }

    /**
     * Whether another automatic narration fits under the user's hourly cap.
     * Only timestamps within the last hour count against the cap. A cap of
     * zero reads literally: no automatic narrations at all (on-demand
     * playback from the place details sheet is unaffected).
     */
    fun narrationAllowed(
        recentNarrationTimes: List<Long>,
        nowMillis: Long,
        maxPerHour: Int
    ): Boolean {
        val windowStart = nowMillis - NARRATION_WINDOW_MS
        return recentNarrationTimes.count { it > windowStart } < maxPerHour
    }

    /**
     * Settings label for the narrations-per-hour slider. Zero is spelled
     * out so the user knows they just muted the tour guide.
     */
    fun narrationCapLabel(maxPerHour: Int): String {
        return if (maxPerHour <= 0) "0 per hour — automatic narration off"
        else "$maxPerHour per hour"
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
