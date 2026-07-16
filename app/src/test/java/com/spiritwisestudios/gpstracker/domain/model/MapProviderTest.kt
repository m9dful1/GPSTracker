package com.spiritwisestudios.gpstracker.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MapProviderTest {

    @Test
    fun `stored names round-trip`() {
        assertEquals(MapProvider.GOOGLE, MapProvider.fromStorage("GOOGLE"))
        assertEquals(MapProvider.OPEN_STREET_MAP, MapProvider.fromStorage("OPEN_STREET_MAP"))
    }

    @Test
    fun `unknown or missing names fall back to OpenStreetMap`() {
        assertEquals(MapProvider.OPEN_STREET_MAP, MapProvider.fromStorage(null))
        assertEquals(MapProvider.OPEN_STREET_MAP, MapProvider.fromStorage(""))
        assertEquals(MapProvider.OPEN_STREET_MAP, MapProvider.fromStorage("BING"))
    }
}
