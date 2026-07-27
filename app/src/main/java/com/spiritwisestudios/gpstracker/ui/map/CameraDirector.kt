package com.spiritwisestudios.gpstracker.ui.map

import com.spiritwisestudios.gpstracker.domain.model.LatLng
import com.spiritwisestudios.gpstracker.util.CameraLogic
import com.spiritwisestudios.gpstracker.util.GeoUtils

/**
 * What the map camera should do about a location fix.
 *
 * `CameraLogic` already answers the arithmetic — how far to lead, what zoom a
 * speed deserves, when the driving view engages and lets go. What was still
 * spread across four of `MainActivity`'s callbacks is the part that decides
 * *which* of those applies: the first fix, a drive, a car that has come to
 * rest, a listener who has panned away, and a navigation session that drives
 * the camera itself.
 *
 * This holds that, and the state it needs — the speed and bearing of the last
 * fix, a short position history for when the GPS course is missing, whether
 * the view is currently tilted, and whether the listener is still being
 * followed. It returns a [Move] and touches no map.
 */
class CameraDirector {

    /** A camera move for the caller to perform, or [None]. */
    sealed class Move {
        /** Nothing to do: navigation owns the camera, or the user panned away. */
        data object None : Move()

        /** The map has never been placed; put it on the user with a zoom. */
        data class FirstFix(val target: LatLng) : Move()

        /** Heading-up, tilted, aimed ahead of the fix by one ease of travel. */
        data class Driving(
            val target: LatLng,
            val zoom: Float,
            val bearing: Float,
            val durationMs: Int = CameraLogic.CAMERA_EASE_MS
        ) : Move()

        /** Flat and north-up again, after a drive ends. */
        data class TopDown(val target: LatLng) : Move()

        /** Stay as we are and slide to the new position. */
        data class Follow(
            val target: LatLng,
            val durationMs: Int = CameraLogic.CAMERA_EASE_MS
        ) : Move()
    }

    private val history = ArrayDeque<LatLng>(HISTORY_SIZE)
    private val drivingGate = CameraLogic.DrivingCameraGate()

    private var speedMps = 0f
    private var gpsBearing: Float? = null
    private var awaitingFirstFix = true
    private var tilted = false

    /**
     * Whether the camera still follows the listener. A pan or a zoom hands
     * control over to them until they ask for it back.
     */
    var isFollowingUser = true
        private set

    /** Speed of the last fix, in m/s — the driving camera's zoom follows it. */
    val lastSpeedMps: Float get() = speedMps

    /** Whether the heading-up driving view is engaged outside navigation. */
    val isDriving: Boolean get() = drivingGate.isDriving

    /** The listener panned or zoomed: stop moving the camera under them. */
    fun onUserGesture() {
        isFollowingUser = false
    }

    /** Follow again without moving the camera — guidance starting, say. */
    fun followAgain() {
        isFollowingUser = true
    }

    /** Whether the map has yet to be placed on the listener at all. */
    val awaitingFirstMove: Boolean get() = awaitingFirstFix

    /**
     * Record what a fix says about how the listener is moving.
     *
     * Separate from [moveFor] on purpose: every fix feeds the driving gate's
     * hysteresis and the bearing, including the ones that arrive before the
     * map is ready to be moved.
     */
    fun onLocation(speedMps: Float?, bearing: Float?) {
        this.speedMps = speedMps ?: 0f
        // A GPS course is only meaningful in motion; the last good one is
        // kept through stops rather than snapping the view to noise
        if (bearing != null && this.speedMps >= MIN_SPEED_FOR_BEARING_MPS) {
            gpsBearing = bearing
        }
        drivingGate.onSpeed(this.speedMps)
    }

    /**
     * What the camera should do about the listener now being at [location].
     *
     * @param navigating whether a route preview or guidance owns the camera —
     *   during those this answers [Move.None], because a preview holds the
     *   route overview and guidance drives its own camera.
     */
    fun moveFor(location: LatLng, navigating: Boolean): Move {
        if (awaitingFirstFix) {
            awaitingFirstFix = false
            return Move.FirstFix(location)
        }
        if (navigating || !isFollowingUser) return Move.None

        return when {
            drivingGate.isDriving -> driving(location)
            // The drive ended while the view was still tilted; settle it back
            tilted -> topDown(location)
            else -> Move.Follow(location)
        }
    }

    /**
     * The camera move for a navigation status update — guidance always drives
     * heading-up, and always from the fix it was given.
     */
    fun navigationMove(location: LatLng): Move = driving(location)

    /**
     * The listener asked for the camera back. Heading-up if they are driving
     * or being guided, flat otherwise.
     */
    fun recenter(location: LatLng, navigating: Boolean): Move {
        isFollowingUser = true
        return if (navigating || drivingGate.isDriving) driving(location) else topDown(location)
    }

    /** A drive ended: forget the trail, and let the view settle on the next fix. */
    fun onNavigationEnded() {
        history.clear()
    }

    /** Building a route overview leaves the camera flat and north-up. */
    fun onRouteOverview() {
        tilted = false
    }

    private fun driving(location: LatLng): Move.Driving {
        tilted = true
        history.addLast(location)
        while (history.size > HISTORY_SIZE) history.removeFirst()

        // A fix shows where the listener was; by the time the ease lands they
        // are one ease further down the road. Aim there, not at the fix, so
        // the view doesn't permanently trail reality — the position dot itself
        // stays on the true fix.
        val bearing = bearing()
        val lead = CameraLogic.cameraLeadMeters(speedMps)
        val target = if (lead > 0f) GeoUtils.offsetMeters(location, bearing, lead) else location

        return Move.Driving(target, CameraLogic.zoomForSpeed(speedMps), bearing)
    }

    private fun topDown(location: LatLng): Move.TopDown {
        tilted = false
        return Move.TopDown(location)
    }

    /**
     * Which way the listener is facing: the GPS course when there is one, and
     * otherwise the line between the last two positions.
     */
    private fun bearing(): Float {
        gpsBearing?.let { return it }
        if (history.size >= 2) {
            val recent = history.toList().takeLast(2)
            return GeoUtils.bearingDegrees(recent[0], recent[1])
        }
        return 0f
    }

    companion object {
        /** Positions kept as the bearing fallback when the GPS course is missing. */
        internal const val HISTORY_SIZE = 5

        /** Below this the GPS course is noise, not a heading. */
        internal const val MIN_SPEED_FOR_BEARING_MPS = 1f
    }
}
