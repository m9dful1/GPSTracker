package com.spiritwisestudios.gpstracker.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TourSessionTest {

    private val t0 = 1_700_000_000_000L

    // --- the per-hour narration cap ---

    @Test
    fun `narrations are allowed up to the cap`() {
        val session = TourSession()

        assertTrue(session.tryReserveNarration(t0, maxPerHour = 2))
        assertTrue(session.tryReserveNarration(t0 + 1_000L, maxPerHour = 2))
        assertFalse(session.tryReserveNarration(t0 + 2_000L, maxPerHour = 2))
    }

    @Test
    fun `reserving is atomic so a cap of one admits one`() {
        // The bug: two places coming into range on the same location fix
        // checked the cap, both passed, and both narrated.
        val session = TourSession()

        val granted = (1..5).count { session.tryReserveNarration(t0, maxPerHour = 1) }
        assertEquals(1, granted)
    }

    @Test
    fun `a cap of zero mutes the guide`() {
        assertFalse(TourSession().tryReserveNarration(t0, maxPerHour = 0))
    }

    @Test
    fun `slots free up as the window rolls past`() {
        val session = TourSession()
        session.tryReserveNarration(t0, maxPerHour = 1)

        assertFalse(session.tryReserveNarration(t0 + TourLogic.NARRATION_WINDOW_MS - 1, maxPerHour = 1))
        assertTrue(session.tryReserveNarration(t0 + TourLogic.NARRATION_WINDOW_MS + 1, maxPerHour = 1))
    }

    @Test
    fun `a released slot can be claimed again`() {
        // The queue turned the content away as a duplicate: nothing was said,
        // so nothing should count against the cap.
        val session = TourSession()
        assertTrue(session.tryReserveNarration(t0, maxPerHour = 1))

        session.releaseNarration(t0)
        assertTrue(session.tryReserveNarration(t0, maxPerHour = 1))
    }

    @Test
    fun `the advisory look does not claim a slot`() {
        val session = TourSession()

        assertTrue(session.canNarrate(t0, maxPerHour = 1))
        assertTrue(session.canNarrate(t0, maxPerHour = 1))
        assertTrue(session.tryReserveNarration(t0, maxPerHour = 1))
        assertFalse(session.canNarrate(t0, maxPerHour = 1))
    }

    // --- way-of-life regions ---

    @Test
    fun `a region is covered once per tour`() {
        val session = TourSession()

        assertTrue(session.markRegionNarrated("Sonoma County"))
        assertFalse(session.markRegionNarrated("Sonoma County"))
        assertTrue(session.markRegionNarrated("Napa County"))
    }

    // --- delivery ---

    @Test
    fun `only one delivery loop runs at a time`() {
        // Two triggers arriving together — a geofence and a proximity alert —
        // must not both start telling stories.
        val session = TourSession()

        val first = session.beginDelivery()
        assertNotNull(first)
        assertNull(session.beginDelivery())
        assertTrue(session.isDelivering)
    }

    @Test
    fun `delivery can be claimed again once it ends`() {
        val session = TourSession()
        val token = session.beginDelivery()!!

        session.endDelivery(token)
        assertFalse(session.isDelivering)
        assertNotNull(session.beginDelivery())
    }

    @Test
    fun `a superseded loop cannot release the flag under a new one`() {
        // The original bug: an interrupted delivery cleared the flag while a
        // newer delivery was already running, leaving two of them going.
        val session = TourSession()
        val stale = session.beginDelivery()!!
        session.reset()
        val fresh = session.beginDelivery()!!

        session.endDelivery(stale)

        assertTrue("the new loop still owns delivery", session.isDelivering)
        assertNull(session.beginDelivery())

        session.endDelivery(fresh)
        assertFalse(session.isDelivering)
    }

    @Test
    fun `a skip request is consumed once`() {
        val session = TourSession()
        session.requestSkip()

        assertTrue(session.consumeSkipRequest())
        assertFalse(session.consumeSkipRequest())
    }

    @Test
    fun `an interruption nobody asked for is not a skip`() {
        assertFalse(TourSession().consumeSkipRequest())
    }

    @Test
    fun `a skip only speaks for the story it interrupted`() {
        // Asked for between stories, the skip is already answered by the next
        // one starting; left set, it would make some later interruption — an
        // on-demand replay flushing the queue — look like a skip.
        val session = TourSession()
        session.requestSkip()

        session.clearSkipRequest()

        assertFalse(session.consumeSkipRequest())
    }

    @Test
    fun `starting a delivery clears a stale skip request`() {
        // A skip asked for while nothing was delivering is answered by the
        // first story of the new loop, not by cutting it off.
        val session = TourSession()
        session.requestSkip()
        session.beginDelivery()

        assertFalse(session.consumeSkipRequest())
    }

    // --- ending the tour ---

    @Test
    fun `reset clears the cap the regions and the delivery flag`() {
        val session = TourSession()
        session.tryReserveNarration(t0, maxPerHour = 1)
        session.markRegionNarrated("Sonoma County")
        session.beginDelivery()
        session.requestSkip()

        session.reset()

        assertTrue(session.tryReserveNarration(t0, maxPerHour = 1))
        assertTrue(session.markRegionNarrated("Sonoma County"))
        assertFalse(session.isDelivering)
        assertFalse(session.consumeSkipRequest())
    }
}
