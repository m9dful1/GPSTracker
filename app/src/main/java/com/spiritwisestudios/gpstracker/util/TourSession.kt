package com.spiritwisestudios.gpstracker.util

/**
 * The bookkeeping for one tour: how much the guide has said lately, which
 * regions it has already covered, and whether a narration is being delivered
 * right now.
 *
 * This exists to give that state a single owner. It is read and written from
 * every direction — geofence transitions, proximity alerts (several on one
 * location fix), the quiet-stretch watcher, the notification's buttons, and
 * the tour stopping — and it used to be four plain fields, so a cap check
 * could count a list another coroutine was appending to, and two coroutines
 * could both decide they were the one delivering.
 *
 * Every method here is atomic. The rules themselves stay in [TourLogic].
 */
class TourSession {

    private val lock = Any()

    // When each automatic narration was accepted, for the per-hour cap.
    // Entries older than the window are pruned as new ones arrive.
    private val narrationTimes = mutableListOf<Long>()

    // Way-of-life regions already covered, at most once each per tour
    private val narratedRegions = mutableSetOf<String>()

    private var delivering = false

    // Bumped by every delivery and by reset, so a loop that has been
    // superseded — the tour stopped under it — can't clear the flag for
    // whoever holds it now
    private var deliveryGeneration = 0L

    private var skipRequested = false

    // --- the per-hour narration cap ---

    /**
     * Whether the guide could say something now, without claiming the slot.
     * For skipping work that would be wasted — the content fetch, the region
     * lookup — before [tryReserveNarration] makes it official.
     */
    fun canNarrate(nowMillis: Long, maxPerHour: Int): Boolean = synchronized(lock) {
        prune(nowMillis)
        TourLogic.narrationAllowed(narrationTimes, nowMillis, maxPerHour)
    }

    /**
     * Claim a slot in the hourly cap. Checking and recording in one step is
     * the point: two places coming into range on the same location fix used
     * to be able to both pass a cap of one.
     *
     * @return whether the slot was granted.
     */
    fun tryReserveNarration(nowMillis: Long, maxPerHour: Int): Boolean = synchronized(lock) {
        prune(nowMillis)
        if (!TourLogic.narrationAllowed(narrationTimes, nowMillis, maxPerHour)) {
            false
        } else {
            narrationTimes.add(nowMillis)
            true
        }
    }

    /** Hand a claimed slot back — the narration never happened after all. */
    fun releaseNarration(stampMillis: Long) = synchronized(lock) {
        narrationTimes.remove(stampMillis)
        Unit
    }

    private fun prune(nowMillis: Long) {
        val windowStart = nowMillis - TourLogic.NARRATION_WINDOW_MS
        narrationTimes.removeAll { it <= windowStart }
    }

    // --- way-of-life regions ---

    /**
     * @return true the first time a region is claimed, false once it has
     *   been covered this tour.
     */
    fun markRegionNarrated(regionName: String): Boolean = synchronized(lock) {
        narratedRegions.add(regionName)
    }

    // --- delivery ---

    /** Whether a narration is being delivered (including the pause between). */
    val isDelivering: Boolean
        get() = synchronized(lock) { delivering }

    /**
     * Claim the right to run the delivery loop.
     *
     * @return a token to pass to [endDelivery], or null if another loop
     *   already owns delivery.
     */
    fun beginDelivery(): Long? = synchronized(lock) {
        if (delivering) {
            null
        } else {
            delivering = true
            // A skip asked for while nothing was delivering is answered by
            // this loop's first narration, not by interrupting it
            skipRequested = false
            ++deliveryGeneration
        }
    }

    /** Release delivery, unless this loop has already been superseded. */
    fun endDelivery(token: Long) = synchronized(lock) {
        if (deliveryGeneration == token) {
            delivering = false
        }
    }

    /** The listener asked for the next story. */
    fun requestSkip() = synchronized(lock) {
        skipRequested = true
    }

    /**
     * Forget any pending skip. Called as each narration begins, so a request
     * only ever speaks for the story it interrupted: a skip asked for between
     * stories is already answered by the next one starting, and leaving it set
     * would make some later, unrelated interruption look like a skip.
     */
    fun clearSkipRequest() = synchronized(lock) {
        skipRequested = false
    }

    /**
     * @return whether an interruption was the listener asking to move on,
     *   clearing the request.
     */
    fun consumeSkipRequest(): Boolean = synchronized(lock) {
        val requested = skipRequested
        skipRequested = false
        requested
    }

    /** The tour is over: forget everything and orphan any running loop. */
    fun reset() = synchronized(lock) {
        narrationTimes.clear()
        narratedRegions.clear()
        delivering = false
        skipRequested = false
        deliveryGeneration++
    }
}
