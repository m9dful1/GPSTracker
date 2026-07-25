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
     * A queued narration goes stale once its place is this far behind the
     * listener. Just past (a block or so) still reads naturally as "just
     * behind you"; beyond this the moment is gone.
     */
    const val STALE_BEHIND_METERS = 250f

    /**
     * Whether a queued narration should be dropped because its place has
     * already fallen behind the listener. A guide previews what's coming;
     * narrating something the car left a half mile back is the classic
     * audio-tour failure ("don't play stale you-are-passing audio late").
     * Unknown direction or distance never counts as stale.
     */
    fun narrationIsStale(direction: RelativeDirection?, distanceMeters: Float?): Boolean {
        return direction == RelativeDirection.BEHIND &&
            distanceMeters != null && distanceMeters > STALE_BEHIND_METERS
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
     * Spoken welcome when a planned Take a Tour drive begins. A real tour
     * opens by naming the tour and setting expectations (the welcome/
     * orientation phase every guiding curriculum teaches), not with the
     * generic route line.
     */
    fun tourWelcomeAnnouncement(tourName: String, placeCount: Int): String {
        val opening = "Welcome to your $tourName tour."
        return when {
            placeCount <= 0 ->
                "$opening I'm still looking for interesting places along the loop — I'll point them out as we go."
            placeCount == 1 ->
                "$opening There's 1 interesting place along the way, and I'll tell you about it as we get close."
            else ->
                "$opening There are $placeCount interesting places along the way — I'll point out each one as we reach it. Enjoy the drive."
        }
    }

    /**
     * Short spoken bridge to the next queued place, appended after a
     * narration ends. Tour scripting calls this a suffix story: telling
     * the listener what's coming kills the is-the-app-broken silence and
     * gives them something to watch for.
     */
    fun upNextPhrase(nextPlaceName: String?): String? {
        return nextPlaceName?.let { "Up next: $it." }
    }

    /**
     * How memorable a narrated place is for the end-of-drive summary:
     * intrinsic category interest dominates, crowd rating breaks ties.
     */
    fun highlightWorthiness(category: String, rating: Double?): Int {
        return categoryInterestWeight(category) * 2 + ratingPoints(rating)
    }

    /**
     * Closing line spoken on arrival, summarizing the drive's tour, with a
     * callback to its most memorable place — guides close by paying off the
     * highlight, not just counting stops. Null when nothing was narrated —
     * stay quiet rather than announce an empty tour.
     */
    fun tripSummaryPhrase(narratedCount: Int, highlightName: String? = null): String? {
        return when {
            narratedCount <= 0 -> null
            narratedCount == 1 -> "That concludes today's tour: you heard about 1 place along the way."
            highlightName != null ->
                "That concludes today's tour: you heard about $narratedCount places along the way, including $highlightName."
            else -> "That concludes today's tour: you heard about $narratedCount places along the way."
        }
    }

    /**
     * Breathing room between back-to-back narrations. Guides are trained to
     * stay quiet between places — time to look, time to talk — instead of
     * lecturing wall to wall; only the gap is scheduled, never a cutoff.
     */
    const val INTER_NARRATION_PAUSE_MS = 8_000L

    /**
     * How many consecutive speech failures the delivery chain tolerates
     * before it stops pulling from the queue. A muted or broken engine fails
     * the instant it is asked to speak, so retrying without a limit empties a
     * full queue in one pass and loses every story in it. Giving up after a
     * few leaves the rest queued for the next trigger to try.
     */
    const val MAX_CONSECUTIVE_DELIVERY_ERRORS = 3

    /**
     * Whether delivery should try the next queued narration after
     * [consecutiveErrors] failures in a row (counting the one just seen).
     */
    fun shouldKeepDeliveringAfterError(consecutiveErrors: Int): Boolean {
        return consecutiveErrors < MAX_CONSECUTIVE_DELIVERY_ERRORS
    }

    /**
     * Silence long enough to fill with regional color. Under a few minutes
     * is the deliberate quiet guides plan for; past that, listeners start
     * wondering whether the app broke.
     */
    const val QUIET_STRETCH_MS = 4L * 60L * 1000L

    /** At most one way-of-life segment per this window — rest is also planned. */
    const val WAY_OF_LIFE_COOLDOWN_MS = 10L * 60L * 1000L

    /**
     * Way-of-life filler is a driving-tour device (long empty road); on
     * foot, quiet is just a walk in the park.
     */
    const val WAY_OF_LIFE_MIN_SPEED_MPS = 5f

    /**
     * Whether a quiet stretch has earned a way-of-life segment: the guide
     * has been silent long enough for the quiet to read as breakage, no
     * real narration is playing or queued (sights always outrank filler),
     * the listener is actually driving, and the last filler wasn't recent —
     * coach guides fill long stretches, they don't chatter through every
     * gap.
     */
    fun shouldPlayWayOfLife(
        nowMillis: Long,
        lastSpokenAtMillis: Long,
        lastWayOfLifeAtMillis: Long?,
        speedMetersPerSecond: Float,
        narrationBusy: Boolean
    ): Boolean {
        if (narrationBusy) return false
        if (speedMetersPerSecond < WAY_OF_LIFE_MIN_SPEED_MPS) return false
        if (nowMillis - lastSpokenAtMillis < QUIET_STRETCH_MS) return false
        if (lastWayOfLifeAtMillis != null &&
            nowMillis - lastWayOfLifeAtMillis < WAY_OF_LIFE_COOLDOWN_MS
        ) {
            return false
        }
        return true
    }

    /**
     * Spoken lead-in for a way-of-life segment. Frames the segment as
     * filling quiet — so the listener knows this isn't a sight to look
     * for — and names the region first, like every other narration.
     */
    fun wayOfLifeIntro(regionName: String): String {
        return "While the road is quiet, a little about $regionName."
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
     * A category's intrinsic tour-worthiness. When several places are in
     * range at once, a guide talks about the Civil War fort, not the
     * sandwich shop next to it.
     */
    fun categoryInterestWeight(category: String): Int {
        return when (category.uppercase()) {
            "HISTORICAL", "CULTURAL" -> 3
            "NATURAL", "ARCHITECTURAL" -> 2
            "ENTERTAINMENT" -> 1
            else -> 0
        }
    }

    /**
     * Calculate content delivery priority for a POI. Higher means sooner.
     * Combines the alert-based base priority with the category's intrinsic
     * interest, rating, user category preferences, whether real facts exist
     * to tell ([hasRichContent] — a documented place beats a bare map pin),
     * and whether the place was already visited.
     */
    fun contentPriorityFor(
        poi: PointOfInterest,
        preferences: UserPreferences,
        basePriority: Int,
        hasRichContent: Boolean = false
    ): Int {
        var priority = basePriority

        // Landmarks outrank lunch spots
        priority += categoryInterestWeight(poi.category)

        // Places with a real story to tell beat bare map pins
        if (hasRichContent) {
            priority += 2
        }

        // POI rating (0-5 scale, add 0-3 priority points)
        priority += ratingPoints(poi.rating)

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

    /** A crowd rating on the 0-5 scale as 0-3 priority points. */
    internal fun ratingPoints(rating: Double?): Int {
        return when {
            rating == null -> 0
            rating >= 4.5 -> 3
            rating >= 4.0 -> 2
            rating >= 3.5 -> 1
            else -> 0
        }
    }
}
