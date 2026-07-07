package com.spiritwisestudios.gpstracker.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkerStylingTest {

    @Test
    fun `each narratable category gets a distinct hue`() {
        val categories = listOf("CULTURAL", "HISTORICAL", "NATURAL", "ENTERTAINMENT", "DINING", "SHOPPING")
        val hues = categories.map { MarkerStyling.hueFor(it) }
        assertEquals(hues.size, hues.distinct().size)
    }

    @Test
    fun `unknown categories fall back to azure`() {
        assertEquals(MarkerStyling.HUE_AZURE, MarkerStyling.hueFor("OTHER"))
        assertEquals(MarkerStyling.HUE_AZURE, MarkerStyling.hueFor("no-such-category"))
    }

    @Test
    fun `category matching ignores case`() {
        assertEquals(MarkerStyling.hueFor("NATURAL"), MarkerStyling.hueFor("natural"))
    }

    @Test
    fun `hues are valid color wheel degrees`() {
        listOf("CULTURAL", "HISTORICAL", "NATURAL", "ENTERTAINMENT", "DINING", "SHOPPING", "OTHER").forEach {
            val hue = MarkerStyling.hueFor(it)
            assertTrue("hue $hue out of range for $it", hue >= 0f && hue < 360f)
        }
    }

    @Test
    fun `visited markers are dimmed but still visible`() {
        assertTrue(MarkerStyling.alphaFor(isVisited = true) < MarkerStyling.alphaFor(isVisited = false))
        assertTrue(MarkerStyling.alphaFor(isVisited = true) > 0f)
        assertEquals(1.0f, MarkerStyling.alphaFor(isVisited = false))
    }
}
