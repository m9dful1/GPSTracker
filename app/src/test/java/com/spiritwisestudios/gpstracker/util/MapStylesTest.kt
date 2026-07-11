package com.spiritwisestudios.gpstracker.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapStylesTest {

    @Test
    fun `never-saved style falls back to the default`() {
        assertEquals(MapStyles.DEFAULT, MapStyles.normalize(null))
    }

    @Test
    fun `valid styles pass through`() {
        assertEquals(MapStyles.DARK, MapStyles.normalize(MapStyles.DARK))
        assertEquals(MapStyles.MINIMAL, MapStyles.normalize(MapStyles.MINIMAL))
    }

    @Test
    fun `garbage style never yields a blank map`() {
        // Values from another app version (e.g. old GoogleMap constants)
        // must not leave the user staring at an empty grid
        assertEquals(MapStyles.DEFAULT, MapStyles.normalize(99))
        assertEquals(MapStyles.DEFAULT, MapStyles.normalize(-1))
    }

    @Test
    fun `default style follows night mode`() {
        assertTrue(MapStyles.styleUrl(MapStyles.DEFAULT, nightMode = true).endsWith("/dark"))
        assertTrue(MapStyles.styleUrl(MapStyles.DEFAULT, nightMode = false).endsWith("/liberty"))
    }

    @Test
    fun `explicit styles ignore night mode`() {
        assertTrue(MapStyles.styleUrl(MapStyles.BRIGHT, nightMode = true).endsWith("/bright"))
        assertTrue(MapStyles.styleUrl(MapStyles.DARK, nightMode = false).endsWith("/dark"))
    }
}
