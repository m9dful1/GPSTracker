package com.spiritwisestudios.gpstracker.util

import com.spiritwisestudios.gpstracker.domain.service.LocationAwarenessService.AlertType
import java.util.concurrent.ConcurrentHashMap

/**
 * Rate limits proximity alerts, one place at a time.
 *
 * Every location fix re-measures every monitored place, so without a gate a
 * place stays "approaching" for as long as it is in range and alerts on each
 * fix — and the tour service answers an alert by fetching content. A place is
 * worth alerting about when something changed:
 *
 * - its alert type moved on (nearby → approaching → arrived), or
 * - the same state has held long enough to be worth repeating.
 *
 * Leaving the radius forgets the place, so coming back alerts immediately
 * rather than waiting out a cooldown.
 */
class ProximityAlertGate(private val repeatAfterMs: Long = DEFAULT_REPEAT_AFTER_MS) {

    private data class Last(val type: AlertType, val atMillis: Long)

    // Written from the location callback and cleared from the tour service's
    // coroutines. The worst a lost race costs is one extra alert.
    private val lastByPoi = ConcurrentHashMap<String, Last>()

    /**
     * @param type the alert this fix produced for the place, or null when the
     *   place is out of range.
     * @return whether to raise the alert.
     */
    fun shouldAlert(poiId: String, type: AlertType?, now: Long): Boolean {
        if (type == null) {
            lastByPoi.remove(poiId)
            return false
        }

        val last = lastByPoi[poiId]
        if (last != null && last.type == type && now - last.atMillis < repeatAfterMs) {
            return false
        }

        lastByPoi[poiId] = Last(type, now)
        return true
    }

    /** Stop tracking one place — it is no longer monitored. */
    fun forget(poiId: String) {
        lastByPoi.remove(poiId)
    }

    /** Stop tracking everything: monitoring has ended. */
    fun reset() {
        lastByPoi.clear()
    }

    companion object {
        /**
         * How long the same state has to hold before it is announced again.
         * Long, because a repeat says nothing new: the interesting cases are
         * state changes and places re-entered, and both bypass this.
         */
        const val DEFAULT_REPEAT_AFTER_MS = 10 * 60 * 1000L
    }
}
