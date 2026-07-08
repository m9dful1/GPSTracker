package com.spiritwisestudios.gpstracker.data.repository

import com.google.android.gms.maps.GoogleMap
import com.spiritwisestudios.gpstracker.domain.model.PointOfInterest
import org.junit.Assert.assertEquals
import org.junit.Test

class UserPreferencesRepositoryTest {

    @Test
    fun `never-saved categories fall back to the defaults`() {
        assertEquals(
            UserPreferencesRepository.DEFAULT_PREFERRED_CATEGORIES,
            UserPreferencesRepository.parseCategories(null)
        )
    }

    @Test
    fun `saved names map back to their categories`() {
        assertEquals(
            setOf(PointOfInterest.Category.DINING, PointOfInterest.Category.NATURAL),
            UserPreferencesRepository.parseCategories(setOf("DINING", "NATURAL"))
        )
    }

    @Test
    fun `unknown names are skipped instead of crashing`() {
        assertEquals(
            setOf(PointOfInterest.Category.DINING),
            UserPreferencesRepository.parseCategories(setOf("DINING", "SPACE_ELEVATORS"))
        )
    }

    @Test
    fun `deselecting everything is respected, not reset to defaults`() {
        assertEquals(
            emptySet<PointOfInterest.Category>(),
            UserPreferencesRepository.parseCategories(emptySet())
        )
    }

    // --- normalizeMapType ---

    @Test
    fun `never-saved map type falls back to the normal map`() {
        assertEquals(
            GoogleMap.MAP_TYPE_NORMAL,
            UserPreferencesRepository.normalizeMapType(null)
        )
    }

    @Test
    fun `valid map types pass through`() {
        assertEquals(
            GoogleMap.MAP_TYPE_HYBRID,
            UserPreferencesRepository.normalizeMapType(GoogleMap.MAP_TYPE_HYBRID)
        )
        assertEquals(
            GoogleMap.MAP_TYPE_TERRAIN,
            UserPreferencesRepository.normalizeMapType(GoogleMap.MAP_TYPE_TERRAIN)
        )
    }

    @Test
    fun `garbage map type never yields a blank map`() {
        // MAP_TYPE_NONE (0) or values from another app version must not
        // leave the user staring at an empty grid
        assertEquals(GoogleMap.MAP_TYPE_NORMAL, UserPreferencesRepository.normalizeMapType(0))
        assertEquals(GoogleMap.MAP_TYPE_NORMAL, UserPreferencesRepository.normalizeMapType(99))
    }
}
