package com.spiritwisestudios.gpstracker.data.repository

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.spiritwisestudios.gpstracker.domain.model.PointOfInterest
import com.spiritwisestudios.gpstracker.domain.model.UserPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    // --- toUserPreferences ---

    @Test
    fun `nothing stored yields the same settings the app ships with`() {
        // This is what a file the app couldn't read is answered with, so it
        // has to be a usable tour rather than a zeroed one.
        val defaults = UserPreferencesRepository.toUserPreferences(emptyPreferences())

        assertEquals(UserPreferences(), defaults)
        assertTrue("the guide can speak", defaults.audioEnabled)
        assertTrue("narration plays on its own", defaults.autoPlayContent)
        assertTrue("something is narratable per hour", defaults.maxNotificationsPerHour > 0)
        assertEquals(UserPreferences.DetailLevel.MEDIUM, defaults.contentDetailLevel)
    }

    @Test
    fun `stored settings are read back`() {
        val stored = mutablePreferencesOf(
            booleanPreferencesKey("audio_enabled") to false,
            floatPreferencesKey("voice_speed") to 1.4f,
            stringPreferencesKey("voice_language") to "fr-FR",
            intPreferencesKey("max_notifications_per_hour") to 3,
            stringPreferencesKey("content_detail_level") to "DETAILED"
        )

        val preferences = UserPreferencesRepository.toUserPreferences(stored)

        assertEquals(false, preferences.audioEnabled)
        assertEquals(1.4f, preferences.voiceSpeed, 0.001f)
        assertEquals("fr-FR", preferences.voiceLanguage)
        assertEquals(3, preferences.maxNotificationsPerHour)
        assertEquals(UserPreferences.DetailLevel.DETAILED, preferences.contentDetailLevel)
    }
}
