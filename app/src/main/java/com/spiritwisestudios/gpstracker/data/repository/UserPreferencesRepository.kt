package com.spiritwisestudios.gpstracker.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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
                // For simplicity, we're not storing the preferred categories in preferences yet
                preferredCategories = setOf(
                    PointOfInterest.Category.HISTORICAL,
                    PointOfInterest.Category.CULTURAL,
                    PointOfInterest.Category.ARCHITECTURAL
                ),
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