package com.spiritwisestudios.gpstracker.util

import com.spiritwisestudios.gpstracker.domain.model.LatLng
import com.spiritwisestudios.gpstracker.domain.model.PointOfInterest
import com.spiritwisestudios.gpstracker.domain.model.UserPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TourLogicTest {

    private fun poi(
        category: String = "CULTURAL",
        rating: Double? = null,
        isVisited: Boolean = false
    ) = PointOfInterest(
        id = "test-id",
        name = "Test Place",
        latLng = LatLng(0.0, 0.0),
        address = "123 Test St",
        category = category,
        rating = rating,
        isVisited = isVisited
    )

    // --- geofenceRadiusFor ---

    @Test
    fun `stationary user keeps base radius`() {
        assertEquals(200, TourLogic.geofenceRadiusFor(0f, 200))
    }

    @Test
    fun `walking speed grows radius by half`() {
        // 1.5 m/s = 5.4 km/h → walking bucket
        assertEquals(300, TourLogic.geofenceRadiusFor(1.5f, 200))
    }

    @Test
    fun `city driving triples radius`() {
        // 10 m/s = 36 km/h → city-driving bucket
        assertEquals(600, TourLogic.geofenceRadiusFor(10f, 200))
    }

    @Test
    fun `highway driving quintuples radius`() {
        // 20 m/s = 72 km/h → highway bucket
        assertEquals(1000, TourLogic.geofenceRadiusFor(20f, 200))
    }

    @Test
    fun `radius grows monotonically with speed`() {
        val radii = listOf(0f, 1f, 3f, 10f, 20f, 30f).map {
            TourLogic.geofenceRadiusFor(it, 100)
        }
        assertEquals(radii, radii.sorted())
    }

    // --- contentPriorityFor ---

    @Test
    fun `high rating adds three points`() {
        val prefs = UserPreferences(preferredCategories = emptySet())
        // SHOPPING carries no intrinsic category weight, isolating the rating
        assertEquals(3, TourLogic.contentPriorityFor(poi(category = "SHOPPING", rating = 4.8), prefs, 0))
    }

    @Test
    fun `preferred category adds two points`() {
        val prefs = UserPreferences(preferredCategories = setOf(PointOfInterest.Category.SHOPPING))
        assertEquals(2, TourLogic.contentPriorityFor(poi(category = "SHOPPING"), prefs, 0))
    }

    @Test
    fun `visited place is penalized`() {
        val prefs = UserPreferences(preferredCategories = emptySet())
        val fresh = TourLogic.contentPriorityFor(poi(rating = 4.8), prefs, 0)
        val visited = TourLogic.contentPriorityFor(poi(rating = 4.8, isVisited = true), prefs, 0)
        assertTrue(visited < fresh)
    }

    @Test
    fun `priority never drops below zero`() {
        val prefs = UserPreferences(preferredCategories = emptySet())
        assertEquals(0, TourLogic.contentPriorityFor(poi(category = "SHOPPING", isVisited = true), prefs, 0))
    }

    @Test
    fun `landmarks outrank lunch spots`() {
        val prefs = UserPreferences(preferredCategories = emptySet())
        val fort = TourLogic.contentPriorityFor(poi(category = "HISTORICAL"), prefs, 0)
        val sandwichShop = TourLogic.contentPriorityFor(poi(category = "DINING"), prefs, 0)
        assertTrue(fort > sandwichShop)
    }

    @Test
    fun `a place with real facts outranks a bare map pin`() {
        val prefs = UserPreferences(preferredCategories = emptySet())
        val documented = TourLogic.contentPriorityFor(poi(), prefs, 0, hasRichContent = true)
        val barePin = TourLogic.contentPriorityFor(poi(), prefs, 0, hasRichContent = false)
        assertTrue(documented > barePin)
    }

    @Test
    fun `category interest weights rank guide-worthiness`() {
        assertTrue(
            TourLogic.categoryInterestWeight("HISTORICAL") >
                TourLogic.categoryInterestWeight("ENTERTAINMENT")
        )
        assertTrue(
            TourLogic.categoryInterestWeight("ENTERTAINMENT") >
                TourLogic.categoryInterestWeight("SHOPPING")
        )
        assertEquals(0, TourLogic.categoryInterestWeight("no-such-category"))
    }

    @Test
    fun `unknown category string does not crash`() {
        val prefs = UserPreferences()
        assertEquals(1, TourLogic.contentPriorityFor(poi(category = "no-such-category"), prefs, 1))
    }

    // --- shouldPrefetchContent ---

    @Test
    fun `prefetch waits for wifi when mobile data is off`() {
        assertTrue(TourLogic.shouldPrefetchContent(allowMobileData = false, onUnmeteredNetwork = true))
        assertTrue(!TourLogic.shouldPrefetchContent(allowMobileData = false, onUnmeteredNetwork = false))
    }

    @Test
    fun `opting into mobile data allows prefetch anywhere`() {
        assertTrue(TourLogic.shouldPrefetchContent(allowMobileData = true, onUnmeteredNetwork = false))
        assertTrue(TourLogic.shouldPrefetchContent(allowMobileData = true, onUnmeteredNetwork = true))
    }

    // --- tourStartAnnouncement / corridorAnnouncement ---

    @Test
    fun `tour start always says something, even with no places`() {
        assertTrue(TourLogic.tourStartAnnouncement(0).isNotBlank())
        assertTrue(TourLogic.tourStartAnnouncement(0).contains("keeping an eye out"))
    }

    @Test
    fun `tour start counts the nearby places`() {
        assertTrue(TourLogic.tourStartAnnouncement(1).contains("1 interesting place"))
        assertTrue(TourLogic.tourStartAnnouncement(12).contains("12 interesting places"))
    }

    @Test
    fun `empty corridor stays quiet`() {
        assertEquals(null, TourLogic.corridorAnnouncement(0))
    }

    @Test
    fun `corridor announcement counts the route's places`() {
        assertTrue(TourLogic.corridorAnnouncement(1)!!.contains("1 interesting place"))
        assertTrue(TourLogic.corridorAnnouncement(8)!!.contains("8 interesting places"))
    }

    // --- tourWelcomeAnnouncement ---

    @Test
    fun `tour welcome names the tour`() {
        val welcome = TourLogic.tourWelcomeAnnouncement("Las Vegas Strip", 8)
        assertTrue(welcome.contains("Welcome to your Las Vegas Strip tour"))
        assertTrue(welcome.contains("8 interesting places"))
    }

    @Test
    fun `tour welcome handles one or no places`() {
        assertTrue(TourLogic.tourWelcomeAnnouncement("Hoover Dam", 1).contains("1 interesting place"))
        assertTrue(TourLogic.tourWelcomeAnnouncement("Hoover Dam", 0).contains("still looking"))
    }

    // --- tripSummaryPhrase ---

    @Test
    fun `no narrations means no summary`() {
        assertEquals(null, TourLogic.tripSummaryPhrase(0))
        assertEquals(null, TourLogic.tripSummaryPhrase(-1))
    }

    @Test
    fun `single narration is phrased in the singular`() {
        val phrase = TourLogic.tripSummaryPhrase(1)!!
        assertTrue(phrase.contains("1 place "))
    }

    @Test
    fun `multiple narrations are counted in the summary`() {
        val phrase = TourLogic.tripSummaryPhrase(7)!!
        assertTrue(phrase.contains("7 places"))
    }

    @Test
    fun `summary calls back the drive's highlight`() {
        val phrase = TourLogic.tripSummaryPhrase(7, "Fort Point")!!
        assertTrue(phrase.contains("including Fort Point"))
    }

    @Test
    fun `a single-place summary skips the redundant highlight`() {
        // "1 place, including it" would name the same place twice
        val phrase = TourLogic.tripSummaryPhrase(1, "Fort Point")!!
        assertFalse(phrase.contains("including"))
    }

    // --- highlightWorthiness ---

    @Test
    fun `the fort outranks the equally rated sandwich shop as highlight`() {
        assertTrue(
            TourLogic.highlightWorthiness("HISTORICAL", 4.8) >
                TourLogic.highlightWorthiness("DINING", 4.8)
        )
    }

    @Test
    fun `rating breaks highlight ties within a category`() {
        assertTrue(
            TourLogic.highlightWorthiness("CULTURAL", 4.8) >
                TourLogic.highlightWorthiness("CULTURAL", 3.0)
        )
    }

    // --- upNextPhrase ---

    @Test
    fun `up next bridge names the queued place`() {
        assertEquals("Up next: Fort Point.", TourLogic.upNextPhrase("Fort Point"))
        assertEquals(null, TourLogic.upNextPhrase(null))
    }

    // --- narrationIsStale ---

    @Test
    fun `a place well behind the listener is stale`() {
        assertTrue(TourLogic.narrationIsStale(TourLogic.RelativeDirection.BEHIND, 800f))
    }

    @Test
    fun `a place just behind is still worth narrating`() {
        assertFalse(TourLogic.narrationIsStale(TourLogic.RelativeDirection.BEHIND, 200f))
    }

    @Test
    fun `places ahead or beside are never stale`() {
        assertFalse(TourLogic.narrationIsStale(TourLogic.RelativeDirection.AHEAD, 5_000f))
        assertFalse(TourLogic.narrationIsStale(TourLogic.RelativeDirection.LEFT, 5_000f))
    }

    @Test
    fun `unknown direction or distance is never stale`() {
        assertFalse(TourLogic.narrationIsStale(null, 5_000f))
        assertFalse(TourLogic.narrationIsStale(TourLogic.RelativeDirection.BEHIND, null))
    }

    // --- shouldKeepDeliveringAfterError ---

    @Test
    fun `a single speech failure retries the next narration`() {
        assertTrue(TourLogic.shouldKeepDeliveringAfterError(1))
    }

    @Test
    fun `a run of failures short of the cap keeps trying`() {
        assertTrue(
            TourLogic.shouldKeepDeliveringAfterError(TourLogic.MAX_CONSECUTIVE_DELIVERY_ERRORS - 1)
        )
    }

    @Test
    fun `a dead engine stops the chain instead of draining the queue`() {
        assertFalse(
            TourLogic.shouldKeepDeliveringAfterError(TourLogic.MAX_CONSECUTIVE_DELIVERY_ERRORS)
        )
        assertFalse(
            TourLogic.shouldKeepDeliveringAfterError(TourLogic.MAX_CONSECUTIVE_DELIVERY_ERRORS + 5)
        )
    }

    // --- shouldPlayWayOfLife ---

    private val quietLongEnough get() = now - TourLogic.QUIET_STRETCH_MS - 1L
    private val drivingSpeed = 15f // m/s, ~54 km/h

    @Test
    fun `a long quiet driving stretch earns filler`() {
        assertTrue(
            TourLogic.shouldPlayWayOfLife(
                nowMillis = now,
                lastSpokenAtMillis = quietLongEnough,
                lastWayOfLifeAtMillis = null,
                speedMetersPerSecond = drivingSpeed,
                narrationBusy = false
            )
        )
    }

    @Test
    fun `recent speech postpones filler`() {
        assertFalse(
            TourLogic.shouldPlayWayOfLife(
                nowMillis = now,
                lastSpokenAtMillis = now - 60_000L,
                lastWayOfLifeAtMillis = null,
                speedMetersPerSecond = drivingSpeed,
                narrationBusy = false
            )
        )
    }

    @Test
    fun `sights always outrank filler`() {
        assertFalse(
            TourLogic.shouldPlayWayOfLife(
                nowMillis = now,
                lastSpokenAtMillis = quietLongEnough,
                lastWayOfLifeAtMillis = null,
                speedMetersPerSecond = drivingSpeed,
                narrationBusy = true
            )
        )
    }

    @Test
    fun `filler is a driving device, not a walking one`() {
        assertFalse(
            TourLogic.shouldPlayWayOfLife(
                nowMillis = now,
                lastSpokenAtMillis = quietLongEnough,
                lastWayOfLifeAtMillis = null,
                speedMetersPerSecond = 1.5f, // walking
                narrationBusy = false
            )
        )
    }

    @Test
    fun `one filler per cooldown window`() {
        assertFalse(
            TourLogic.shouldPlayWayOfLife(
                nowMillis = now,
                lastSpokenAtMillis = quietLongEnough,
                lastWayOfLifeAtMillis = now - TourLogic.WAY_OF_LIFE_COOLDOWN_MS + 1L,
                speedMetersPerSecond = drivingSpeed,
                narrationBusy = false
            )
        )
        assertTrue(
            TourLogic.shouldPlayWayOfLife(
                nowMillis = now,
                lastSpokenAtMillis = quietLongEnough,
                lastWayOfLifeAtMillis = now - TourLogic.WAY_OF_LIFE_COOLDOWN_MS,
                speedMetersPerSecond = drivingSpeed,
                narrationBusy = false
            )
        )
    }

    @Test
    fun `way of life intro names the region and frames the quiet`() {
        val intro = TourLogic.wayOfLifeIntro("Reno")
        assertEquals("While the road is quiet, a little about Reno.", intro)
    }

    // --- wayOfLifeCooldownMs ---

    @Test
    fun `a fruitful lookup keeps the ordinary cooldown`() {
        assertEquals(TourLogic.WAY_OF_LIFE_COOLDOWN_MS, TourLogic.wayOfLifeCooldownMs(0))
    }

    @Test
    fun `each empty lookup doubles the wait`() {
        // Empty country, or a public API turning us away: asking again in 30
        // seconds — for the whole drive — is what this prevents.
        assertEquals(TourLogic.WAY_OF_LIFE_COOLDOWN_MS, TourLogic.wayOfLifeCooldownMs(1))
        assertEquals(TourLogic.WAY_OF_LIFE_COOLDOWN_MS * 2, TourLogic.wayOfLifeCooldownMs(2))
        assertEquals(TourLogic.WAY_OF_LIFE_COOLDOWN_MS * 4, TourLogic.wayOfLifeCooldownMs(3))
    }

    @Test
    fun `the backoff stops growing at the maximum`() {
        assertEquals(TourLogic.WAY_OF_LIFE_MAX_COOLDOWN_MS, TourLogic.wayOfLifeCooldownMs(4))
        assertEquals(TourLogic.WAY_OF_LIFE_MAX_COOLDOWN_MS, TourLogic.wayOfLifeCooldownMs(40))
        assertEquals(TourLogic.WAY_OF_LIFE_MAX_COOLDOWN_MS, TourLogic.wayOfLifeCooldownMs(4_000))
    }

    @Test
    fun `a longer backoff actually holds filler back`() {
        val backedOff = TourLogic.wayOfLifeCooldownMs(3)

        // Past the ordinary cooldown, but not past the backed-off one
        assertFalse(
            TourLogic.shouldPlayWayOfLife(
                nowMillis = now,
                lastSpokenAtMillis = quietLongEnough,
                lastWayOfLifeAtMillis = now - TourLogic.WAY_OF_LIFE_COOLDOWN_MS,
                speedMetersPerSecond = drivingSpeed,
                narrationBusy = false,
                cooldownMs = backedOff
            )
        )
        assertTrue(
            TourLogic.shouldPlayWayOfLife(
                nowMillis = now,
                lastSpokenAtMillis = quietLongEnough,
                lastWayOfLifeAtMillis = now - backedOff,
                speedMetersPerSecond = drivingSpeed,
                narrationBusy = false,
                cooldownMs = backedOff
            )
        )
    }

    // --- detailLevelFor ---

    @Test
    fun `stationary user keeps preferred detail level`() {
        assertEquals(
            UserPreferences.DetailLevel.DETAILED,
            TourLogic.detailLevelFor(0f, UserPreferences.DetailLevel.DETAILED)
        )
    }

    @Test
    fun `walking keeps full detail`() {
        // 2 m/s = 7.2 km/h
        assertEquals(
            UserPreferences.DetailLevel.DETAILED,
            TourLogic.detailLevelFor(2f, UserPreferences.DetailLevel.DETAILED)
        )
    }

    @Test
    fun `city driving caps detailed down to medium`() {
        // 10 m/s = 36 km/h
        assertEquals(
            UserPreferences.DetailLevel.MEDIUM,
            TourLogic.detailLevelFor(10f, UserPreferences.DetailLevel.DETAILED)
        )
    }

    @Test
    fun `highway speed caps everything to brief`() {
        // 25 m/s = 90 km/h
        assertEquals(
            UserPreferences.DetailLevel.BRIEF,
            TourLogic.detailLevelFor(25f, UserPreferences.DetailLevel.DETAILED)
        )
        assertEquals(
            UserPreferences.DetailLevel.BRIEF,
            TourLogic.detailLevelFor(25f, UserPreferences.DetailLevel.MEDIUM)
        )
    }

    @Test
    fun `speed never raises detail above the user preference`() {
        assertEquals(
            UserPreferences.DetailLevel.BRIEF,
            TourLogic.detailLevelFor(0f, UserPreferences.DetailLevel.BRIEF)
        )
        assertEquals(
            UserPreferences.DetailLevel.MEDIUM,
            TourLogic.detailLevelFor(2f, UserPreferences.DetailLevel.MEDIUM)
        )
    }

    // --- relativeDirection ---

    @Test
    fun `poi on the nose is ahead`() {
        assertEquals(TourLogic.RelativeDirection.AHEAD, TourLogic.relativeDirection(0f, 0f))
    }

    @Test
    fun `poi at ninety degrees is on the right`() {
        assertEquals(TourLogic.RelativeDirection.RIGHT, TourLogic.relativeDirection(0f, 90f))
    }

    @Test
    fun `poi at one eighty is behind`() {
        assertEquals(TourLogic.RelativeDirection.BEHIND, TourLogic.relativeDirection(0f, 180f))
    }

    @Test
    fun `poi at two seventy is on the left`() {
        assertEquals(TourLogic.RelativeDirection.LEFT, TourLogic.relativeDirection(0f, 270f))
    }

    @Test
    fun `direction is relative to travel heading not north`() {
        // Heading east, POI due north → over the left shoulder
        assertEquals(TourLogic.RelativeDirection.LEFT, TourLogic.relativeDirection(90f, 0f))
    }

    @Test
    fun `quadrant wraps around north`() {
        // Heading 350°, POI bearing 10° → only 20° off the nose
        assertEquals(TourLogic.RelativeDirection.AHEAD, TourLogic.relativeDirection(350f, 10f))
    }

    @Test
    fun `quadrant boundaries fall clockwise`() {
        assertEquals(TourLogic.RelativeDirection.RIGHT, TourLogic.relativeDirection(0f, 45f))
        assertEquals(TourLogic.RelativeDirection.BEHIND, TourLogic.relativeDirection(0f, 135f))
        assertEquals(TourLogic.RelativeDirection.LEFT, TourLogic.relativeDirection(0f, 225f))
        assertEquals(TourLogic.RelativeDirection.AHEAD, TourLogic.relativeDirection(0f, 315f))
    }

    // --- narrationAllowed / narrationCapLabel ---

    private val now = 10_000_000_000L // arbitrary fixed "now"

    @Test
    fun `first narration of the hour is allowed`() {
        assertTrue(TourLogic.narrationAllowed(emptyList(), now, maxPerHour = 10))
    }

    @Test
    fun `narration is blocked once the hourly cap is reached`() {
        val recent = listOf(now - 1_000L, now - 2_000L, now - 3_000L)
        assertFalse(TourLogic.narrationAllowed(recent, now, maxPerHour = 3))
        assertTrue(TourLogic.narrationAllowed(recent, now, maxPerHour = 4))
    }

    @Test
    fun `narrations older than an hour age out of the window`() {
        val stale = listOf(now - TourLogic.NARRATION_WINDOW_MS - 1L)
        assertTrue(TourLogic.narrationAllowed(stale, now, maxPerHour = 1))
    }

    @Test
    fun `narration exactly an hour old no longer counts`() {
        val boundary = listOf(now - TourLogic.NARRATION_WINDOW_MS)
        assertTrue(TourLogic.narrationAllowed(boundary, now, maxPerHour = 1))
    }

    @Test
    fun `cap of zero silences automatic narration`() {
        assertFalse(TourLogic.narrationAllowed(emptyList(), now, maxPerHour = 0))
    }

    @Test
    fun `cap label spells out the muted state`() {
        assertEquals("10 per hour", TourLogic.narrationCapLabel(10))
        assertEquals("0 per hour — automatic narration off", TourLogic.narrationCapLabel(0))
    }

    // --- shouldSkipNarration ---

    @Test
    fun `unvisited places are never skipped`() {
        assertFalse(TourLogic.shouldSkipNarration(isVisited = false, visitedDate = null, nowMillis = now))
        assertFalse(TourLogic.shouldSkipNarration(isVisited = false, visitedDate = now - 1L, nowMillis = now))
    }

    @Test
    fun `recently narrated place is skipped`() {
        val yesterday = now - 24L * 60L * 60L * 1000L
        assertTrue(TourLogic.shouldSkipNarration(isVisited = true, visitedDate = yesterday, nowMillis = now))
    }

    @Test
    fun `the guide's memory fades after the cooldown`() {
        val longAgo = now - TourLogic.NARRATION_REVISIT_COOLDOWN_MS - 1L
        assertFalse(TourLogic.shouldSkipNarration(isVisited = true, visitedDate = longAgo, nowMillis = now))
    }

    @Test
    fun `visit exactly one cooldown old is eligible again`() {
        val boundary = now - TourLogic.NARRATION_REVISIT_COOLDOWN_MS
        assertFalse(TourLogic.shouldSkipNarration(isVisited = true, visitedDate = boundary, nowMillis = now))
    }

    @Test
    fun `visited place with unknown date stays skipped`() {
        assertTrue(TourLogic.shouldSkipNarration(isVisited = true, visitedDate = null, nowMillis = now))
    }

    // --- narrationIntroFor ---

    @Test
    fun `intro names the direction`() {
        assertEquals(
            "On your left: Fort Point.",
            TourLogic.narrationIntroFor("Fort Point", TourLogic.RelativeDirection.LEFT)
        )
    }

    @Test
    fun `unknown direction falls back to neutral intro`() {
        assertEquals(
            "Coming up: Fort Point.",
            TourLogic.narrationIntroFor("Fort Point", null)
        )
    }

    @Test
    fun `intro weaves in the distance when known`() {
        assertEquals(
            "On your right, about 500 feet: Fort Point.",
            TourLogic.narrationIntroFor("Fort Point", TourLogic.RelativeDirection.RIGHT, "about 500 feet")
        )
        assertEquals(
            "Coming up, about 1000 feet: Fort Point.",
            TourLogic.narrationIntroFor("Fort Point", null, "about 1000 feet")
        )
    }

    // --- distancePhrase ---

    @Test
    fun `very close places get no distance callout`() {
        assertEquals(null, TourLogic.distancePhrase(0f))
        assertEquals(null, TourLogic.distancePhrase(74f))
    }

    @Test
    fun `near distances round to a hundred feet`() {
        assertEquals("about 500 feet", TourLogic.distancePhrase(160f))
        assertEquals("about 300 feet", TourLogic.distancePhrase(80f))
    }

    @Test
    fun `mid distances round to quarter miles`() {
        assertEquals("about a quarter mile", TourLogic.distancePhrase(400f))
        assertEquals("about half a mile", TourLogic.distancePhrase(800f))
        assertEquals("about three quarters of a mile", TourLogic.distancePhrase(1_200f))
    }

    @Test
    fun `far distances round to half miles`() {
        assertEquals("about 1 mile", TourLogic.distancePhrase(1_600f))
        assertEquals("about 1.5 miles", TourLogic.distancePhrase(2_400f))
        assertEquals("about 2 miles", TourLogic.distancePhrase(3_200f))
    }
}
