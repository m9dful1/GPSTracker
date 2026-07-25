package com.spiritwisestudios.gpstracker.util

import com.spiritwisestudios.gpstracker.domain.model.LatLng

/**
 * Where a drive has got to along its route, and which maneuver comes next.
 *
 * The expensive part is matching a point to the route: a city route carries a
 * couple of thousand polyline points, and asking that question once per
 * instruction per location fix is tens of thousands of distance computations
 * every few seconds. Maneuver points don't move, so [instructionRouteIndices]
 * answers it once per route and the per-fix work becomes proportional to the
 * number of instructions instead of instructions × route points.
 */
object RouteProgress {

    /** Index of the route point nearest [point], or -1 for an empty route. */
    fun closestPointIndex(point: LatLng, route: List<LatLng>): Int {
        if (route.isEmpty()) return -1

        var closestIndex = 0
        var minDistance = Float.MAX_VALUE
        for (i in route.indices) {
            val distance = GeoUtils.distanceMeters(point, route[i])
            if (distance < minDistance) {
                minDistance = distance
                closestIndex = i
            }
        }
        return closestIndex
    }

    /**
     * Where each maneuver sits along the route. Computed once per route —
     * these only change when the route does.
     */
    fun instructionRouteIndices(maneuverPoints: List<LatLng>, route: List<LatLng>): List<Int> {
        return maneuverPoints.map { closestPointIndex(it, route) }
    }

    /**
     * Metres still to drive along [route], from [fromIndex] to the end.
     * Ignores the short hop from the driver's actual position to that point.
     */
    fun remainingRouteDistance(route: List<LatLng>, fromIndex: Int): Float {
        if (fromIndex < 0 || fromIndex >= route.size - 1) return 0f

        var meters = 0f
        for (i in fromIndex until route.size - 1) {
            meters += GeoUtils.distanceMeters(route[i], route[i + 1])
        }
        return meters
    }

    /**
     * How long is left, from the routing service's own figures scaled by the
     * fraction of the route still to drive.
     *
     * The planner knows what the roads are: a motorway leg and a city block
     * of the same length take very different times, and that knowledge is in
     * the route's total duration. Recomputing from one hardcoded average
     * speed threw it away and made every long drive read wrong.
     *
     * @param plannedDistanceMeters the route's total length as planned.
     * @param plannedDurationMs the route's total duration as planned.
     * @param fallbackSpeedMps used only when there is no planned duration to
     *   scale — a straight-line guess, and marked as one.
     */
    fun remainingTimeMs(
        remainingDistanceMeters: Float,
        plannedDistanceMeters: Float,
        plannedDurationMs: Long,
        fallbackSpeedMps: Float = FALLBACK_SPEED_MPS
    ): Long {
        if (remainingDistanceMeters <= 0f) return 0L

        if (plannedDistanceMeters > 0f && plannedDurationMs > 0L) {
            val fraction = remainingDistanceMeters / plannedDistanceMeters
            return (plannedDurationMs * fraction).toLong().coerceAtLeast(0L)
        }

        return (remainingDistanceMeters / fallbackSpeedMps * 1000).toLong()
    }

    /** About 30 mph — only for a route the planner never priced. */
    const val FALLBACK_SPEED_MPS = 13.4f

    /**
     * The maneuver to announce next: the nearest one still ahead on the route.
     * When nothing is ahead — off route, or past the last turn — the nearest
     * one overall, so guidance says something rather than nothing.
     *
     * @param routeIndices [instructionRouteIndices] for the same instructions,
     *   in the same order.
     * @param progressIndex where the driver is, from [closestPointIndex].
     * @return the index into [maneuverPoints], or -1 when there is nothing to
     *   announce or no idea where we are.
     */
    fun nextInstructionIndex(
        location: LatLng,
        maneuverPoints: List<LatLng>,
        routeIndices: List<Int>,
        progressIndex: Int
    ): Int {
        if (maneuverPoints.isEmpty() || progressIndex < 0) return -1

        var aheadIndex = -1
        var aheadDistance = Float.MAX_VALUE
        var nearestIndex = -1
        var nearestDistance = Float.MAX_VALUE

        for (i in maneuverPoints.indices) {
            val distance = GeoUtils.distanceMeters(location, maneuverPoints[i])
            if (distance < nearestDistance) {
                nearestDistance = distance
                nearestIndex = i
            }

            val routeIndex = routeIndices.getOrElse(i) { -1 }
            if (routeIndex > progressIndex && distance < aheadDistance) {
                aheadDistance = distance
                aheadIndex = i
            }
        }

        return if (aheadIndex >= 0) aheadIndex else nearestIndex
    }
}
