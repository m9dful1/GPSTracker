package com.spiritwisestudios.gpstracker.util

import com.spiritwisestudios.gpstracker.domain.service.LocationAwarenessService.AlertType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProximityAlertGateTest {

    private val t0 = 1_700_000_000_000L

    @Test
    fun `a place in range alerts the first time`() {
        val gate = ProximityAlertGate()
        assertTrue(gate.shouldAlert("mill", AlertType.APPROACHING, t0))
    }

    @Test
    fun `the same state does not alert on every fix`() {
        // The bug this exists for: a fix every few seconds, each one finding
        // the place still approaching, each one costing a content lookup.
        val gate = ProximityAlertGate()
        gate.shouldAlert("mill", AlertType.APPROACHING, t0)

        val repeats = (1..20).count {
            gate.shouldAlert("mill", AlertType.APPROACHING, t0 + it * 5_000L)
        }
        assertEquals(0, repeats)
    }

    @Test
    fun `moving on to a new state alerts`() {
        val gate = ProximityAlertGate()
        gate.shouldAlert("mill", AlertType.NEARBY, t0)

        assertTrue(gate.shouldAlert("mill", AlertType.APPROACHING, t0 + 5_000L))
        assertTrue(gate.shouldAlert("mill", AlertType.ARRIVED, t0 + 10_000L))
    }

    @Test
    fun `places do not hide each other`() {
        // Two places in range on one fix: both are worth knowing about, and
        // the old shared field kept only whichever came last.
        val gate = ProximityAlertGate()

        assertTrue(gate.shouldAlert("mill", AlertType.APPROACHING, t0))
        assertTrue(gate.shouldAlert("bridge", AlertType.APPROACHING, t0))
        assertTrue(gate.shouldAlert("chapel", AlertType.NEARBY, t0))
    }

    @Test
    fun `a held state is repeated after long enough`() {
        val gate = ProximityAlertGate(repeatAfterMs = 60_000L)
        gate.shouldAlert("mill", AlertType.NEARBY, t0)

        assertFalse(gate.shouldAlert("mill", AlertType.NEARBY, t0 + 59_000L))
        assertTrue(gate.shouldAlert("mill", AlertType.NEARBY, t0 + 60_000L))
    }

    @Test
    fun `leaving the radius clears the place`() {
        // Out of range is not an alert, and it forgets the place so coming
        // back is news again rather than waiting out the cooldown.
        val gate = ProximityAlertGate()
        gate.shouldAlert("mill", AlertType.APPROACHING, t0)

        assertFalse(gate.shouldAlert("mill", null, t0 + 5_000L))
        assertTrue(gate.shouldAlert("mill", AlertType.APPROACHING, t0 + 10_000L))
    }

    @Test
    fun `an unmonitored place is forgotten`() {
        val gate = ProximityAlertGate()
        gate.shouldAlert("mill", AlertType.ARRIVED, t0)

        gate.forget("mill")
        assertTrue(gate.shouldAlert("mill", AlertType.ARRIVED, t0 + 1_000L))
    }

    @Test
    fun `the next tour alerts about the same places again`() {
        val gate = ProximityAlertGate()
        gate.shouldAlert("mill", AlertType.ARRIVED, t0)

        gate.reset()
        assertTrue(gate.shouldAlert("mill", AlertType.ARRIVED, t0 + 1_000L))
    }

    @Test
    fun `departing is announced once as well`() {
        val gate = ProximityAlertGate()
        gate.shouldAlert("mill", AlertType.ARRIVED, t0)

        assertTrue(gate.shouldAlert("mill", AlertType.DEPARTING, t0 + 5_000L))
        assertFalse(gate.shouldAlert("mill", AlertType.DEPARTING, t0 + 10_000L))
    }
}
