package com.spiritwisestudios.gpstracker.data.api

import org.junit.Assert.assertEquals
import org.junit.Test

class PlacesApiServiceTest {

    // --- mapPlaceTypesToCategory ---

    @Test
    fun `museum maps to cultural`() {
        assertEquals("CULTURAL", PlacesApiService.mapPlaceTypesToCategory(listOf("museum", "point_of_interest")))
    }

    @Test
    fun `church maps to historical`() {
        assertEquals("HISTORICAL", PlacesApiService.mapPlaceTypesToCategory(listOf("church", "place_of_worship")))
    }

    @Test
    fun `park maps to natural`() {
        assertEquals("NATURAL", PlacesApiService.mapPlaceTypesToCategory(listOf("park")))
    }

    @Test
    fun `restaurant maps to dining`() {
        assertEquals("DINING", PlacesApiService.mapPlaceTypesToCategory(listOf("restaurant", "food")))
    }

    @Test
    fun `unknown types map to other`() {
        assertEquals("OTHER", PlacesApiService.mapPlaceTypesToCategory(listOf("gas_station", "atm")))
        assertEquals("OTHER", PlacesApiService.mapPlaceTypesToCategory(emptyList()))
    }

    // New Places API type names (not present in the legacy API)

    @Test
    fun `historical landmark maps to historical`() {
        assertEquals("HISTORICAL", PlacesApiService.mapPlaceTypesToCategory(listOf("historical_landmark")))
        assertEquals("HISTORICAL", PlacesApiService.mapPlaceTypesToCategory(listOf("monument")))
    }

    @Test
    fun `national park and garden map to natural`() {
        assertEquals("NATURAL", PlacesApiService.mapPlaceTypesToCategory(listOf("national_park")))
        assertEquals("NATURAL", PlacesApiService.mapPlaceTypesToCategory(listOf("botanical_garden")))
    }

    @Test
    fun `performing arts theater maps to cultural`() {
        assertEquals("CULTURAL", PlacesApiService.mapPlaceTypesToCategory(listOf("performing_arts_theater")))
    }

    @Test
    fun `cultural match wins over dining when both present`() {
        // A museum with a cafe inside should narrate as a museum
        assertEquals("CULTURAL", PlacesApiService.mapPlaceTypesToCategory(listOf("cafe", "museum")))
    }
}
