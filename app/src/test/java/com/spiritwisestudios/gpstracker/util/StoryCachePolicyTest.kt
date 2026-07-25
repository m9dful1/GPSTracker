package com.spiritwisestudios.gpstracker.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryCachePolicyTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `the cutoff is one maximum age behind now`() {
        assertEquals(now - StoryCachePolicy.MAX_AGE_MS, StoryCachePolicy.cutoffMillis(now))
    }

    @Test
    fun `a story cached today survives the cutoff`() {
        val cachedToday = now - 12 * 60 * 60 * 1000L
        assertTrue(cachedToday > StoryCachePolicy.cutoffMillis(now))
    }

    @Test
    fun `a story older than the maximum age falls before the cutoff`() {
        val cachedLongAgo = now - StoryCachePolicy.MAX_AGE_MS - 1
        assertTrue(cachedLongAgo < StoryCachePolicy.cutoffMillis(now))
    }

    @Test
    fun `a cache within the cap is left alone`() {
        assertFalse(StoryCachePolicy.shouldTrim(0))
        assertFalse(StoryCachePolicy.shouldTrim(StoryCachePolicy.MAX_ENTRIES - 1))
        assertFalse(StoryCachePolicy.shouldTrim(StoryCachePolicy.MAX_ENTRIES))
    }

    @Test
    fun `a cache over the cap is trimmed`() {
        // Even stories too fresh to age out are bounded: an install that
        // drives daily for years shouldn't grow a database without limit.
        assertTrue(StoryCachePolicy.shouldTrim(StoryCachePolicy.MAX_ENTRIES + 1))
        assertTrue(StoryCachePolicy.shouldTrim(10_000))
    }
}
