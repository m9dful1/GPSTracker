package com.spiritwisestudios.gpstracker.ads

import org.junit.Assert.assertEquals
import org.junit.Test

class AdRetryPolicyTest {

    @Test
    fun `delays double on each failure and cap at the maximum`() {
        val policy = AdRetryPolicy(baseDelayMs = 30_000L, maxDelayMs = 300_000L)

        assertEquals(30_000L, policy.nextDelayMs())
        assertEquals(60_000L, policy.nextDelayMs())
        assertEquals(120_000L, policy.nextDelayMs())
        assertEquals(240_000L, policy.nextDelayMs())
        assertEquals(300_000L, policy.nextDelayMs()) // capped
        assertEquals(300_000L, policy.nextDelayMs()) // stays capped
    }

    @Test
    fun `a successful load resets the backoff`() {
        val policy = AdRetryPolicy(baseDelayMs = 10_000L, maxDelayMs = 120_000L)
        policy.nextDelayMs()
        policy.nextDelayMs()

        policy.reset()

        assertEquals(10_000L, policy.nextDelayMs())
    }
}
