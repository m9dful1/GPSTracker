package com.spiritwisestudios.gpstracker.util

import org.junit.Assert.assertEquals
import org.junit.Test

class DistanceFormatterTest {

    // --- format ---

    @Test
    fun `short distances format as feet rounded to ten`() {
        assertEquals("100 ft", DistanceFormatter.format(30f))
        assertEquals("490 ft", DistanceFormatter.format(150f))
    }

    @Test
    fun `longer distances format as miles with one decimal`() {
        assertEquals("0.3 mi", DistanceFormatter.format(500f))
        assertEquals("1.0 mi", DistanceFormatter.format(1_609f))
        assertEquals("2.5 mi", DistanceFormatter.format(4_023f))
    }

    @Test
    fun `ten miles and up format as whole miles`() {
        assertEquals("12 mi", DistanceFormatter.format(20_000f))
    }

    // --- spoken ---

    @Test
    fun `short distances speak as feet rounded to fifty`() {
        assertEquals("100 feet", DistanceFormatter.spoken(30f))
        assertEquals("500 feet", DistanceFormatter.spoken(150f))
        assertEquals("50 feet", DistanceFormatter.spoken(5f))
    }

    @Test
    fun `whole miles speak without a decimal`() {
        assertEquals("1 mile", DistanceFormatter.spoken(1_609f))
        assertEquals("2 miles", DistanceFormatter.spoken(3_219f))
    }

    @Test
    fun `fractional miles speak with one decimal`() {
        assertEquals("0.3 miles", DistanceFormatter.spoken(500f))
        assertEquals("2.5 miles", DistanceFormatter.spoken(4_023f))
    }
}
