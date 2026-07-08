package com.spiritwisestudios.gpstracker.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.android.gms.maps.GoogleMap
import com.spiritwisestudios.gpstracker.domain.model.PointOfInterest
import com.spiritwisestudios.gpstracker.domain.model.UserPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

// Extension property for Context to create the DataStore
private val Context.userPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "user_preferences"
)

/**
 * Repository for managing user preferences data.
 */
@Singleton
class UserPreferencesRepository @Inject constructor(
    private val context: Context
) {
    // Preference keys
    private object PreferencesKeys {
        val AUDIO_ENABLED = booleanPreferencesKey("audio_enabled")
        val VOICE_SPEED = floatPreferencesKey("voice_speed")
        val VOICE_PITCH = floatPreferencesKey("voice_pitch")
        val VOICE_LANGUAGE = stringPreferencesKey("voice_language")
        val AUTO_PLAY_CONTENT = booleanPreferencesKey("auto_play_content")
        val CONTENT_DETAIL_LEVEL = stringPreferencesKey("content_detail_level")
        val NOTIFY_DISTANCE = intPreferencesKey("notify_distance")
        val MAX_NOTIFICATIONS_PER_HOUR = intPreferencesKey("max_notifications_per_hour")
        val PREFETCH_CONTENT = booleanPreferencesKey("prefetch_content")
        val USE_MOBILE_DATA = booleanPreferencesKey("use_mobile_data")
        val DARK_MODE_ENABLED = booleanPreferencesKey("dark_mode_enabled")
        val PREFERRED_CATEGORIES = stringSetPreferencesKey("preferred_categories")
        val MAP_TYPE = intPreferencesKey("map_type")
        val MAP_TRAFFIC = booleanPreferencesKey("map_traffic")
    }

    companion object {
        /** Categories boosted until the user picks their own. */
        val DEFAULT_PREFERRED_CATEGORIES: Set<PointOfInterest.Category> = setOf(
            PointOfInterest.Category.HISTORICAL,
            PointOfInterest.Category.CULTURAL,
            PointOfInterest.Category.ARCHITECTURAL
        )

        /**
         * Map stored category names back to the enum. Null means the user
         * never saved a choice → defaults. Unknown names (e.g. from a
         * newer/older app version) are skipped, and an empty set is
         * respected as "no preferred categories".
         */
        internal fun parseCategories(names: Set<String>?): Set<PointOfInterest.Category> {
            if (names == null) return DEFAULT_PREFERRED_CATEGORIES
            return names.mapNotNull { name ->
                try {
                    PointOfInterest.Category.valueOf(name)
                } catch (e: IllegalArgumentException) {
                    null
                }
            }.toSet()
        }

        /**
         * Sanitize a stored map type: never saved or not a real GoogleMap
         * constant (e.g. from a different app version) falls back to the
         * normal map rather than a blank MAP_TYPE_NONE screen.
         */
        internal fun normalizeMapType(stored: Int?): Int {
            return when (stored) {
                GoogleMap.MAP_TYPE_NORMAL,
                GoogleMap.MAP_TYPE_SATELLITE,
                GoogleMap.MAP_TYPE_TERRAIN,
                GoogleMap.MAP_TYPE_HYBRID -> stored
                else -> GoogleMap.MAP_TYPE_NORMAL
            }
        }
    }

    /**
     * Get the user preferences as a Flow.
     */
    val userPreferencesFlow: Flow<UserPreferences> = context.userPreferencesDataStore.data
        .map { preferences ->
            // Map the preferences to a UserPreferences object
            UserPreferences(
                audioEnabled = preferences[PreferencesKeys.AUDIO_ENABLED] ?: true,
                voiceSpeed = preferences[PreferencesKeys.VOICE_SPEED] ?: 1.0f,
                voicePitch = preferences[PreferencesKeys.VOICE_PITCH] ?: 1.0f,
                voiceLanguage = preferences[PreferencesKeys.VOICE_LANGUAGE] ?: "en-US",
                autoPlayContent = preferences[PreferencesKeys.AUTO_PLAY_CONTENT] ?: true,
                preferredCategories = parseCategories(preferences[PreferencesKeys.PREFERRED_CATEGORIES]),
                contentDetailLevel = preferences[PreferencesKeys.CONTENT_DETAIL_LEVEL]?.let {
                    UserPreferences.DetailLevel.valueOf(it)
                } ?: UserPreferences.DetailLevel.MEDIUM,
                notifyDistance = preferences[PreferencesKeys.NOTIFY_DISTANCE] ?: 200,
                maxNotificationsPerHour = preferences[PreferencesKeys.MAX_NOTIFICATIONS_PER_HOUR] ?: 10,
                prefetchContent = preferences[PreferencesKeys.PREFETCH_CONTENT] ?: true,
                useMobileData = preferences[PreferencesKeys.USE_MOBILE_DATA] ?: false,
                darkModeEnabled = preferences[PreferencesKeys.DARK_MODE_ENABLED] ?: false
            )
        }

    /**
     * Update user preferences.
     */
    suspend fun updateUserPreferences(userPreferences: UserPreferences) {
        context.userPreferencesDataStore.edit { preferences ->
            preferences[PreferencesKeys.AUDIO_ENABLED] = userPreferences.audioEnabled
            preferences[PreferencesKeys.VOICE_SPEED] = userPreferences.voiceSpeed
            preferences[PreferencesKeys.VOICE_PITCH] = userPreferences.voicePitch
            preferences[PreferencesKeys.VOICE_LANGUAGE] = userPreferences.voiceLanguage
            preferences[PreferencesKeys.AUTO_PLAY_CONTENT] = userPreferences.autoPlayContent
            preferences[PreferencesKeys.CONTENT_DETAIL_LEVEL] = userPreferences.contentDetailLevel.name
            preferences[PreferencesKeys.NOTIFY_DISTANCE] = userPreferences.notifyDistance
            preferences[PreferencesKeys.MAX_NOTIFICATIONS_PER_HOUR] = userPreferences.maxNotificationsPerHour
            preferences[PreferencesKeys.PREFETCH_CONTENT] = userPreferences.prefetchContent
            preferences[PreferencesKeys.USE_MOBILE_DATA] = userPreferences.useMobileData
            preferences[PreferencesKeys.DARK_MODE_ENABLED] = userPreferences.darkModeEnabled
            preferences[PreferencesKeys.PREFERRED_CATEGORIES] =
                userPreferences.preferredCategories.map { it.name }.toSet()
        }
    }

    /**
     * Map display choices (type, traffic) from the layers sheet, restored
     * when the map loads so they survive app restarts.
     */
    val mapDisplayFlow: Flow<MapDisplayPreferences> = context.userPreferencesDataStore.data
        .map { preferences ->
            MapDisplayPreferences(
                mapType = normalizeMapType(preferences[PreferencesKeys.MAP_TYPE]),
                trafficEnabled = preferences[PreferencesKeys.MAP_TRAFFIC] ?: false
            )
        }

    suspend fun setMapType(mapType: Int) {
        context.userPreferencesDataStore.edit { preferences ->
            preferences[PreferencesKeys.MAP_TYPE] = mapType
        }
    }

    suspend fun setMapTrafficEnabled(enabled: Boolean) {
        context.userPreferencesDataStore.edit { preferences ->
            preferences[PreferencesKeys.MAP_TRAFFIC] = enabled
        }
    }

    /**
     * Update audio settings only.
     */
    suspend fun updateAudioSettings(
        audioEnabled: Boolean? = null,
        voiceSpeed: Float? = null,
        voicePitch: Float? = null,
        voiceLanguage: String? = null,
        autoPlayContent: Boolean? = null
    ) {
        context.userPreferencesDataStore.edit { preferences ->
            audioEnabled?.let { preferences[PreferencesKeys.AUDIO_ENABLED] = it }
            voiceSpeed?.let { preferences[PreferencesKeys.VOICE_SPEED] = it }
            voicePitch?.let { preferences[PreferencesKeys.VOICE_PITCH] = it }
            voiceLanguage?.let { preferences[PreferencesKeys.VOICE_LANGUAGE] = it }
            autoPlayContent?.let { preferences[PreferencesKeys.AUTO_PLAY_CONTENT] = it }
        }
    }
}

/**
 * How the map is displayed: the layers-sheet choices, separate from the
 * tour-behavior settings in [UserPreferences].
 */
data class MapDisplayPreferences(
    val mapType: Int,
    val trafficEnabled: Boolean
)