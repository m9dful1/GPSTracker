package com.spiritwisestudios.gpstracker.data.repository

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
}
