package com.spiritwisestudios.gpstracker.util

import org.junit.Assert.assertEquals
import org.junit.Test

class GoogleMapStylesTest {

    @Test
    fun `known styles pass through normalization`() {
        assertEquals(GoogleMapStyles.DEFAULT, GoogleMapStyles.normalize(GoogleMapStyles.DEFAULT))
        assertEquals(GoogleMapStyles.SATELLITE, GoogleMapStyles.normalize(GoogleMapStyles.SATELLITE))
        assertEquals(GoogleMapStyles.TERRAIN, GoogleMapStyles.normalize(GoogleMapStyles.TERRAIN))
    }

    @Test
    fun `unknown stored values fall back to default`() {
        assertEquals(GoogleMapStyles.DEFAULT, GoogleMapStyles.normalize(null))
        assertEquals(GoogleMapStyles.DEFAULT, GoogleMapStyles.normalize(-1))
        assertEquals(GoogleMapStyles.DEFAULT, GoogleMapStyles.normalize(99))
    }

    @Test
    fun `styles map onto GoogleMap map types`() {
        assertEquals(1, GoogleMapStyles.mapType(GoogleMapStyles.DEFAULT)) // NORMAL
        // Satellite renders as hybrid: imagery without labels is useless
        // for finding your way
        assertEquals(4, GoogleMapStyles.mapType(GoogleMapStyles.SATELLITE)) // HYBRID
        assertEquals(3, GoogleMapStyles.mapType(GoogleMapStyles.TERRAIN)) // TERRAIN
        assertEquals(1, GoogleMapStyles.mapType(42)) // garbage → NORMAL
    }
}
