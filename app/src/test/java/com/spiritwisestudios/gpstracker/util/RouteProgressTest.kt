package com.spiritwisestudios.gpstracker.util

import com.spiritwisestudios.gpstracker.domain.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Test

class RouteProgressTest {

    // A straight run east along one latitude, ~80 m between points
    private val route = (0..10).map { LatLng(45.0, -93.0 + it * 0.001) }

    private fun routePoint(index: Int) = route[index]

    // --- closestPointIndex ---

    @Test
    fun `a point on the route matches its own index`() {
        assertEquals(0, RouteProgress.closestPointIndex(routePoint(0), route))
        assertEquals(5, RouteProgress.closestPointIndex(routePoint(5), route))
        assertEquals(10, RouteProgress.closestPointIndex(routePoint(10), route))
    }

    @Test
    fun `a point beside the route matches the nearest one`() {
        // Just north of route point 3, and a shade past it
        val offRoute = LatLng(45.0002, -93.0 + 3 * 0.001 + 0.0002)
        assertEquals(3, RouteProgress.closestPointIndex(offRoute, route))
    }

    @Test
    fun `an empty route has no closest point`() {
        assertEquals(-1, RouteProgress.closestPointIndex(routePoint(0), emptyList()))
    }

    // --- instructionRouteIndices ---

    @Test
    fun `each maneuver is placed along the route`() {
        val maneuvers = listOf(routePoint(2), routePoint(6), routePoint(9))
        assertEquals(listOf(2, 6, 9), RouteProgress.instructionRouteIndices(maneuvers, route))
    }

    @Test
    fun `no maneuvers means no indices`() {
        assertEquals(emptyList<Int>(), RouteProgress.instructionRouteIndices(emptyList(), route))
    }

    // --- nextInstructionIndex ---

    @Test
    fun `the next maneuver is the nearest one still ahead`() {
        val maneuvers = listOf(routePoint(2), routePoint(6), routePoint(9))
        val indices = RouteProgress.instructionRouteIndices(maneuvers, route)

        // Standing at route point 4: the turn at 6 is next, not the one at 2
        // that is already behind, nor the further one at 9
        assertEquals(
            1,
            RouteProgress.nextInstructionIndex(routePoint(4), maneuvers, indices, progressIndex = 4)
        )
    }

    @Test
    fun `a maneuver at the current point is behind us`() {
        // Strictly ahead: sitting on the turn means it is no longer next
        val maneuvers = listOf(routePoint(4), routePoint(8))
        val indices = RouteProgress.instructionRouteIndices(maneuvers, route)

        assertEquals(
            1,
            RouteProgress.nextInstructionIndex(routePoint(4), maneuvers, indices, progressIndex = 4)
        )
    }

    @Test
    fun `past the last maneuver it falls back to the nearest`() {
        // Nothing ahead any more; guidance should still name something rather
        // than going blank.
        val maneuvers = listOf(routePoint(2), routePoint(6))
        val indices = RouteProgress.instructionRouteIndices(maneuvers, route)

        assertEquals(
            1,
            RouteProgress.nextInstructionIndex(routePoint(10), maneuvers, indices, progressIndex = 10)
        )
    }

    @Test
    fun `an unknown position yields no maneuver`() {
        val maneuvers = listOf(routePoint(2))
        val indices = RouteProgress.instructionRouteIndices(maneuvers, route)

        assertEquals(
            -1,
            RouteProgress.nextInstructionIndex(routePoint(0), maneuvers, indices, progressIndex = -1)
        )
    }

    @Test
    fun `no maneuvers yields nothing to announce`() {
        assertEquals(
            -1,
            RouteProgress.nextInstructionIndex(routePoint(0), emptyList(), emptyList(), progressIndex = 0)
        )
    }

    @Test
    fun `missing indices are treated as behind rather than crashing`() {
        // A shorter index list than the instruction list shouldn't throw; the
        // unmatched ones simply can't be "ahead".
        val maneuvers = listOf(routePoint(3), routePoint(7))

        assertEquals(
            0,
            RouteProgress.nextInstructionIndex(
                routePoint(1),
                maneuvers,
                routeIndices = listOf(3),
                progressIndex = 1
            )
        )
    }
}
