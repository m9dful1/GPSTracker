package com.spiritwisestudios.gpstracker.ads

/**
 * Exponential backoff for ad load retries (a failed load is usually "no
 * fill", which resolves itself — but not in seconds): each failure doubles
 * the wait up to [maxDelayMs]; a successful load resets it.
 */
class AdRetryPolicy(
    private val baseDelayMs: Long,
    private val maxDelayMs: Long
) {
    private var upcomingDelayMs = baseDelayMs

    /** The delay to wait before the next retry; doubles on each call. */
    fun nextDelayMs(): Long {
        val delay = upcomingDelayMs
        upcomingDelayMs = (upcomingDelayMs * 2).coerceAtMost(maxDelayMs)
        return delay
    }

    fun reset() {
        upcomingDelayMs = baseDelayMs
    }
}
