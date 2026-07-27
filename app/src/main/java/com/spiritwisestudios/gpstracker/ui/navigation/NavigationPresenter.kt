package com.spiritwisestudios.gpstracker.ui.navigation

import com.spiritwisestudios.gpstracker.domain.service.NavigationService
import com.spiritwisestudios.gpstracker.util.DistanceFormatter
import com.spiritwisestudios.gpstracker.util.VoicePromptGate
import java.util.concurrent.TimeUnit

/**
 * The navigation screen's state machine: which phase a drive is in, and what
 * each status update from [NavigationService] means for the route card, the
 * turn instructions, the voice and the camera.
 *
 * Pure by design, and deliberately *not* shaped like [NarrationDelivery]'s
 * host interface. Those own a loop and call out to the service repeatedly;
 * this owns no loop — the activity collects the status flow and asks what to
 * do with each update. So the presenter answers with data and the activity
 * renders it, which needs no fakes to test and keeps the order of the view
 * calls exactly where it was.
 *
 * Everything Android-shaped stays outside: string resources, the device's
 * clock format, the map and the fragments.
 */
class NavigationPresenter(
    private val promptGate: VoicePromptGate = VoicePromptGate()
) {

    /**
     * A drive is previewed before it is driven. `PREVIEW` shows the route and
     * a live ETA but stays quiet: no voice, no turn cards, no chase camera.
     */
    enum class Phase { NONE, PREVIEW, GUIDING }

    var phase: Phase = Phase.NONE
        private set

    /** What the two buttons on the route card should look like. */
    data class Buttons(val startVisible: Boolean, val stopEndsGuidance: Boolean)

    /** How long is left, decomposed but not yet worded. */
    sealed class Eta {
        /** No estimate yet — the route is still being calculated. */
        data object Calculating : Eta()

        data class Remaining(
            val hours: Long,
            val minutes: Long,
            val arrivalAtMillis: Long
        ) : Eta()
    }

    /** Everything the route card shows for one status update. */
    data class RouteCard(
        /** "Navigating to X" while guiding, "Route to X" while previewing. */
        val guiding: Boolean,
        val destinationName: String,
        val eta: Eta,
        val distanceText: String,
        /** 0..1000, for the card's thin progress bar. */
        val etaProgress: Int
    )

    /**
     * A destination was chosen. The drive starts as a preview, and everything
     * the last drive said is forgotten — its turns and its arrival are all
     * unspoken again, even if it ended at the same place.
     */
    fun preview(): Buttons {
        phase = Phase.PREVIEW
        promptGate.reset()
        return buttons()
    }

    /**
     * Start was tapped.
     *
     * @return true when guidance actually began. False for a tap that arrives
     *   without a preview behind it — a second tap, or one after the drive
     *   was cancelled.
     */
    fun beginGuidance(): Boolean {
        if (phase != Phase.PREVIEW) return false
        phase = Phase.GUIDING
        return true
    }

    /**
     * The drive ended, by arrival, by Cancel, or by failing to route.
     *
     * @return whether it had reached guidance — the difference between a
     *   drive that happened and a preview the user backed out of.
     */
    fun end(): Boolean {
        val wasGuiding = phase == Phase.GUIDING
        phase = Phase.NONE
        promptGate.reset()
        return wasGuiding
    }

    fun buttons(): Buttons = Buttons(
        startVisible = phase == Phase.PREVIEW,
        stopEndsGuidance = phase == Phase.GUIDING
    )

    /** The card for one status update. [nowMillis] anchors the arrival time. */
    fun card(
        status: NavigationService.NavigationStatus,
        destinationName: String,
        nowMillis: Long
    ): RouteCard = RouteCard(
        guiding = phase == Phase.GUIDING,
        destinationName = destinationName,
        eta = etaFor(status.timeRemaining, nowMillis),
        distanceText = DistanceFormatter.format(status.distanceRemaining),
        etaProgress = etaProgress(status.timeRemaining)
    )

    /**
     * The instruction to put on screen, or null when there is none to show.
     * A preview is a plan, not a drive: it shows the route and the ETA and
     * says nothing about the next turn.
     */
    fun instructionToShow(
        status: NavigationService.NavigationStatus
    ): NavigationService.NavigationInstruction? =
        if (phase == Phase.GUIDING) status.nextInstruction else null

    /**
     * What to say for an instruction, or null to stay quiet.
     *
     * Quiet covers three cases: not guiding, a timing that isn't an
     * announcement (`ADVANCE` shows on the card but isn't spoken over the
     * tour), and a prompt already given — status updates arrive every few
     * seconds carrying the same maneuver, and a parked car keeps reporting
     * its arrival.
     */
    fun promptFor(
        instruction: NavigationService.NavigationInstruction,
        timing: NavigationService.AnnouncementTiming,
        details: NavigationService.ManeuverDetails
    ): String? {
        if (phase != Phase.GUIDING) return null
        if (timing != NavigationService.AnnouncementTiming.IMMEDIATE &&
            timing != NavigationService.AnnouncementTiming.APPROACHING
        ) {
            return null
        }

        val isArrival = instruction.type == NavigationService.InstructionType.ARRIVE
        // The same turn can still be announced twice at different distances,
        // so the key carries the timing as well as the maneuver
        val key = "${instruction.maneuverPoint}|${instruction.type}|$timing"
        if (!promptGate.shouldSpeak(key, isArrival)) return null

        return spokenInstruction(instruction, timing, details)
    }

    /** Whether this prompt is the one that ends the drive. */
    fun isArrival(instruction: NavigationService.NavigationInstruction): Boolean =
        instruction.type == NavigationService.InstructionType.ARRIVE

    /**
     * Whether a new fix should move the camera. Only the guidance phase
     * drives it, and not once the user has panned away.
     */
    fun shouldFollowCamera(isFollowingUser: Boolean): Boolean =
        phase == Phase.GUIDING && isFollowingUser

    /**
     * Whether a newly calculated route should be handed to the tour guide as
     * a narration corridor. A preview hasn't committed to a drive, so the
     * guide stays on the places around the listener.
     */
    fun shouldRegisterCorridor(): Boolean = phase == Phase.GUIDING

    companion object {

        /** Longest drive the progress bar can represent; beyond it, it's full-left. */
        internal const val PROGRESS_HORIZON_MS = 60L * 60L * 1000L

        internal const val PROGRESS_MAX = 1000

        internal fun etaFor(timeRemainingMs: Long, nowMillis: Long): Eta {
            if (timeRemainingMs <= 0) return Eta.Calculating
            val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(timeRemainingMs)
            return Eta.Remaining(
                hours = totalMinutes / 60,
                minutes = totalMinutes % 60,
                arrivalAtMillis = nowMillis + timeRemainingMs
            )
        }

        /**
         * How far along the bar sits: full at arrival, empty an hour out or
         * more. A rough sense of progress, not a measurement — the route's
         * total length isn't known here, only what is left of it.
         */
        internal fun etaProgress(timeRemainingMs: Long): Int {
            if (timeRemainingMs <= 0) return PROGRESS_MAX
            val fractionLeft =
                timeRemainingMs.coerceAtMost(PROGRESS_HORIZON_MS).toDouble() / PROGRESS_HORIZON_MS
            return (PROGRESS_MAX * (1.0 - fractionLeft)).toInt().coerceIn(0, PROGRESS_MAX)
        }

        /**
         * The words for a maneuver. "Turn left now" when it is happening, and
         * the distance first when it is coming up — the way a passenger would
         * say it, so the driver hears *when* before *what*.
         */
        internal fun spokenInstruction(
            instruction: NavigationService.NavigationInstruction,
            timing: NavigationService.AnnouncementTiming,
            details: NavigationService.ManeuverDetails
        ): String {
            val distanceText = DistanceFormatter.spoken(instruction.distance)
            return when (timing) {
                NavigationService.AnnouncementTiming.IMMEDIATE ->
                    "${details.primaryInstruction} now"

                NavigationService.AnnouncementTiming.APPROACHING,
                NavigationService.AnnouncementTiming.ADVANCE ->
                    "In $distanceText, ${details.primaryInstruction.lowercase()}"

                else -> details.primaryInstruction
            }
        }
    }
}
