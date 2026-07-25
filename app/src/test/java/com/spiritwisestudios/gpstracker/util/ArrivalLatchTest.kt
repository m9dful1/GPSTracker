package com.spiritwisestudios.gpstracker.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrivalLatchTest {

    @Test
    fun `arriving is reported once`() {
        val latch = ArrivalLatch()

        assertFalse(latch.onDistanceToDestination(800f))
        assertTrue(latch.onDistanceToDestination(30f))
    }

    @Test
    fun `a parked car does not keep arriving`() {
        // The bug this exists for: a fix every 5 seconds, all inside the
        // radius, each one announcing "you have arrived" again.
        val latch = ArrivalLatch()
        latch.onDistanceToDestination(800f)
        latch.onDistanceToDestination(30f)

        val laterArrivals = (1..20).count { latch.onDistanceToDestination(28f) }
        assertEquals(0, laterArrivals)
    }

    @Test
    fun `a loop drive does not arrive at its own starting point`() {
        // Take a Tour ends where it began, so the first fixes are already
        // inside the radius; that is the start of the drive, not the end.
        val latch = ArrivalLatch()

        assertFalse(latch.onDistanceToDestination(10f))
        assertFalse(latch.onDistanceToDestination(35f))
    }

    @Test
    fun `a loop drive arrives when it comes back`() {
        val latch = ArrivalLatch()
        latch.onDistanceToDestination(10f)
        assertFalse(latch.onDistanceToDestination(2_400f))
        assertTrue(latch.onDistanceToDestination(20f))
    }

    @Test
    fun `passing near the destination early does not arrive`() {
        // The radius edge is exclusive on the way in: 50 m out is still
        // driving, not arriving.
        val latch = ArrivalLatch()
        latch.onDistanceToDestination(400f)
        assertFalse(latch.onDistanceToDestination(ArrivalLatch.DEFAULT_RADIUS_METERS))
        assertTrue(latch.onDistanceToDestination(ArrivalLatch.DEFAULT_RADIUS_METERS - 1f))
    }

    @Test
    fun `hasArrived reflects the latch`() {
        val latch = ArrivalLatch()
        assertFalse(latch.hasArrived)

        latch.onDistanceToDestination(600f)
        assertFalse(latch.hasArrived)

        latch.onDistanceToDestination(5f)
        assertTrue(latch.hasArrived)
    }

    @Test
    fun `the next drive can arrive again`() {
        val latch = ArrivalLatch()
        latch.onDistanceToDestination(600f)
        latch.onDistanceToDestination(5f)

        latch.reset()
        assertFalse(latch.hasArrived)
        assertFalse(latch.onDistanceToDestination(5f))
        latch.onDistanceToDestination(600f)
        assertTrue(latch.onDistanceToDestination(5f))
    }

    @Test
    fun `a custom radius is respected`() {
        val latch = ArrivalLatch(radiusMeters = 150f)
        latch.onDistanceToDestination(900f)
        assertTrue(latch.onDistanceToDestination(120f))
    }
}
