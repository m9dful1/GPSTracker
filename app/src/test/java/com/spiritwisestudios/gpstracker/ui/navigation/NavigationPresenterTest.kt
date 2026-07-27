package com.spiritwisestudios.gpstracker.ui.navigation

import com.spiritwisestudios.gpstracker.domain.model.LatLng
import com.spiritwisestudios.gpstracker.domain.service.NavigationService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The navigation screen's state machine.
 *
 * All of this used to live in `MainActivity`, where the only way to ask "does
 * a preview speak?" was to search for a destination, tap nothing, and listen.
 */
class NavigationPresenterTest {

    private val turn = NavigationService.NavigationInstruction(
        type = NavigationService.InstructionType.TURN_LEFT,
        distance = 400f,
        description = "Turn left onto Main Street",
        maneuverPoint = LatLng(39.5, -119.8)
    )

    private val arrival = NavigationService.NavigationInstruction(
        type = NavigationService.InstructionType.ARRIVE,
        distance = 0f,
        description = "You have arrived",
        maneuverPoint = LatLng(39.52, -119.82)
    )

    private val details = NavigationService.ManeuverDetails(
        visualIcon = "↰",
        visualColor = 0,
        soundCue = "",
        primaryInstruction = "Turn left onto Main Street",
        secondaryInstruction = ""
    )

    private fun status(
        timeRemaining: Long = 10L * 60_000,
        distanceRemaining: Float = 1_200f,
        instruction: NavigationService.NavigationInstruction? = null,
        timing: NavigationService.AnnouncementTiming = NavigationService.AnnouncementTiming.NONE
    ) = NavigationService.NavigationStatus(
        isActive = true,
        currentLocation = LatLng(39.5, -119.8),
        distanceRemaining = distanceRemaining,
        timeRemaining = timeRemaining,
        nextInstruction = instruction,
        announcementTiming = timing
    )

    private fun guiding() = NavigationPresenter().apply {
        preview()
        beginGuidance()
    }

    // --- the phases ---

    @Test
    fun `a drive is previewed before it is driven`() {
        val nav = NavigationPresenter()
        assertEquals(NavigationPresenter.Phase.NONE, nav.phase)

        val buttons = nav.preview()

        assertEquals(NavigationPresenter.Phase.PREVIEW, nav.phase)
        // Start is offered, and the other button backs out rather than ending
        assertTrue(buttons.startVisible)
        assertFalse(buttons.stopEndsGuidance)
    }

    @Test
    fun `starting guidance hides Start and turns the other button into End`() {
        val nav = NavigationPresenter()
        nav.preview()

        assertTrue(nav.beginGuidance())

        assertEquals(NavigationPresenter.Phase.GUIDING, nav.phase)
        assertFalse(nav.buttons().startVisible)
        assertTrue(nav.buttons().stopEndsGuidance)
    }

    @Test
    fun `Start does nothing without a preview behind it`() {
        // A second tap, or one that arrives after Cancel
        val nav = NavigationPresenter()
        assertFalse(nav.beginGuidance())
        assertEquals(NavigationPresenter.Phase.NONE, nav.phase)

        nav.preview()
        assertTrue(nav.beginGuidance())
        assertFalse(nav.beginGuidance())
    }

    @Test
    fun `ending says whether the drive had actually started`() {
        // The difference decides whether the drive earns an interstitial
        val cancelled = NavigationPresenter().apply { preview() }
        assertFalse(cancelled.end())
        assertEquals(NavigationPresenter.Phase.NONE, cancelled.phase)

        assertTrue(guiding().end())
    }

    // --- a preview is a plan, not a drive ---

    @Test
    fun `a preview shows no turn card and says nothing`() {
        val nav = NavigationPresenter()
        nav.preview()

        assertNull(nav.instructionToShow(status(instruction = turn)))
        assertNull(
            nav.promptFor(turn, NavigationService.AnnouncementTiming.IMMEDIATE, details)
        )
        assertFalse(nav.shouldFollowCamera(isFollowingUser = true))
        assertFalse(nav.shouldRegisterCorridor())
    }

    @Test
    fun `guidance shows the turn card, follows the camera, and takes the corridor`() {
        val nav = guiding()

        assertEquals(turn, nav.instructionToShow(status(instruction = turn)))
        assertTrue(nav.shouldFollowCamera(isFollowingUser = true))
        assertTrue(nav.shouldRegisterCorridor())
        // Unless the user has panned away from their own position
        assertFalse(nav.shouldFollowCamera(isFollowingUser = false))
    }

    // --- what gets said ---

    @Test
    fun `an immediate turn is announced as happening now`() {
        val prompt = guiding()
            .promptFor(turn, NavigationService.AnnouncementTiming.IMMEDIATE, details)

        assertEquals("Turn left onto Main Street now", prompt)
    }

    @Test
    fun `an approaching turn leads with the distance`() {
        val prompt = guiding()
            .promptFor(turn, NavigationService.AnnouncementTiming.APPROACHING, details)

        // The driver hears when before what
        assertNotNull(prompt)
        assertTrue(prompt!!.startsWith("In "))
        assertTrue(prompt.endsWith("turn left onto main street"))
    }

    @Test
    fun `timings that are not announcements stay quiet`() {
        // ADVANCE puts a card on screen, but talking over the tour that early
        // is not what a passenger would do
        val nav = guiding()
        for (timing in listOf(
            NavigationService.AnnouncementTiming.NONE,
            NavigationService.AnnouncementTiming.ADVANCE,
            NavigationService.AnnouncementTiming.PASSED
        )) {
            assertNull(timing.name, nav.promptFor(turn, timing, details))
        }
    }

    @Test
    fun `the same turn announced the same way is only spoken once`() {
        // Status updates arrive every few seconds carrying the same maneuver
        val nav = guiding()

        assertNotNull(nav.promptFor(turn, NavigationService.AnnouncementTiming.APPROACHING, details))
        assertNull(nav.promptFor(turn, NavigationService.AnnouncementTiming.APPROACHING, details))

        // But the same turn at a closer distance is a different announcement
        assertNotNull(nav.promptFor(turn, NavigationService.AnnouncementTiming.IMMEDIATE, details))
    }

    @Test
    fun `arrival is spoken once however many times it is reported`() {
        // A parked car keeps producing fixes, each at a slightly different
        // position, so the maneuver point alone would not stop the repeats
        val nav = guiding()
        val again = arrival.copy(maneuverPoint = LatLng(39.5201, -119.8202))

        assertNotNull(nav.promptFor(arrival, NavigationService.AnnouncementTiming.IMMEDIATE, details))
        assertNull(nav.promptFor(again, NavigationService.AnnouncementTiming.IMMEDIATE, details))
    }

    @Test
    fun `a new drive says everything again`() {
        val nav = guiding()
        nav.promptFor(arrival, NavigationService.AnnouncementTiming.IMMEDIATE, details)
        nav.end()

        nav.preview()
        nav.beginGuidance()

        assertNotNull(nav.promptFor(arrival, NavigationService.AnnouncementTiming.IMMEDIATE, details))
    }

    @Test
    fun `arrival is recognised as the end of the drive`() {
        val nav = NavigationPresenter()
        assertTrue(nav.isArrival(arrival))
        assertFalse(nav.isArrival(turn))
    }

    // --- the route card ---

    @Test
    fun `the card names the drive differently while previewing`() {
        val nav = NavigationPresenter()
        nav.preview()
        assertFalse(nav.card(status(), "The Neon Museum", nowMillis = 0).guiding)

        nav.beginGuidance()
        assertTrue(nav.card(status(), "The Neon Museum", nowMillis = 0).guiding)
    }

    @Test
    fun `an unknown time remaining is still calculating`() {
        val card = guiding().card(status(timeRemaining = 0), "Somewhere", nowMillis = 1_000)

        assertEquals(NavigationPresenter.Eta.Calculating, card.eta)
    }

    @Test
    fun `a long drive splits into hours and minutes with an arrival time`() {
        val ninetyMinutes = 90L * 60_000
        val now = 1_700_000_000_000L

        val eta = guiding().card(status(timeRemaining = ninetyMinutes), "Somewhere", now).eta

        assertEquals(
            NavigationPresenter.Eta.Remaining(
                hours = 1,
                minutes = 30,
                arrivalAtMillis = now + ninetyMinutes
            ),
            eta
        )
    }

    @Test
    fun `the progress bar fills as the drive shortens`() {
        // Full on arrival, empty an hour out or more, half way at half an hour
        assertEquals(1000, NavigationPresenter.etaProgress(0))
        assertEquals(0, NavigationPresenter.etaProgress(NavigationPresenter.PROGRESS_HORIZON_MS))
        assertEquals(0, NavigationPresenter.etaProgress(5L * NavigationPresenter.PROGRESS_HORIZON_MS))
        assertEquals(500, NavigationPresenter.etaProgress(30L * 60_000))
    }

    @Test
    fun `the card carries a formatted distance`() {
        val card = guiding().card(status(distanceRemaining = 1_609.34f), "Somewhere", nowMillis = 0)

        // Whatever the unit, it is words rather than a raw float
        assertTrue(card.distanceText.isNotBlank())
        assertFalse(card.distanceText.contains("1609.34"))
    }
}
