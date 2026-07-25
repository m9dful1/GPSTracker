package com.spiritwisestudios.gpstracker.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.spiritwisestudios.gpstracker.domain.model.AccountTier
import com.spiritwisestudios.gpstracker.domain.model.MapProvider
import com.spiritwisestudios.gpstracker.domain.model.PointOfInterest
import com.spiritwisestudios.gpstracker.domain.model.UserPreferences
import com.spiritwisestudios.gpstracker.util.GoogleMapStyles
import com.spiritwisestudios.gpstracker.util.MapStyles
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

// Extension property for Context to create the DataStore
private val Context.userPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "user_preferences",
    // A file that can't be parsed is replaced with an empty one rather than
    // thrown at the reader. Without this a corrupt file — a write killed
    // mid-flight, or a restore from another device, which cloud backup now
    // does — crashes every launch, because the application seeds its holders
    // from a blocking read of this store before any activity exists.
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
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
        val PREFERRED_CATEGORIES = stringSetPreferencesKey("preferred_categories")
        // Deliberately a fresh key: "map_type" stored GoogleMap constants
        // before the MapLibre migration, which don't map onto styles.
        val MAP_STYLE = intPreferencesKey("map_style")
        val MAP_PROVIDER = stringPreferencesKey("map_provider")
        val GOOGLE_MAP_STYLE = intPreferencesKey("google_map_style")
        val MAP_TRAFFIC = booleanPreferencesKey("map_traffic")
        val ACCOUNT_TIER = stringPreferencesKey("account_tier")
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
         * Stored values → the settings the app runs on. Every field has a
         * default, which is what lets an unreadable or empty file be answered
         * with "defaults" instead of an exception.
         */
        internal fun toUserPreferences(preferences: Preferences): UserPreferences {
            return UserPreferences(
                audioEnabled = preferences[PreferencesKeys.AUDIO_ENABLED] ?: true,
                voiceSpeed = preferences[PreferencesKeys.VOICE_SPEED] ?: 1.0f,
                voicePitch = preferences[PreferencesKeys.VOICE_PITCH] ?: 1.0f,
                voiceLanguage = preferences[PreferencesKeys.VOICE_LANGUAGE] ?: "en-US",
                autoPlayContent = preferences[PreferencesKeys.AUTO_PLAY_CONTENT] ?: true,
                preferredCategories = parseCategories(preferences[PreferencesKeys.PREFERRED_CATEGORIES]),
                contentDetailLevel = UserPreferences.DetailLevel.fromStorage(
                    preferences[PreferencesKeys.CONTENT_DETAIL_LEVEL]
                ),
                notifyDistance = preferences[PreferencesKeys.NOTIFY_DISTANCE] ?: 200,
                maxNotificationsPerHour = preferences[PreferencesKeys.MAX_NOTIFICATIONS_PER_HOUR] ?: 10,
                prefetchContent = preferences[PreferencesKeys.PREFETCH_CONTENT] ?: true,
                useMobileData = preferences[PreferencesKeys.USE_MOBILE_DATA] ?: false
            )
        }
    }

    /**
     * The stored preferences, or an empty set when the file can't be read.
     *
     * Losing the file costs the user their settings; letting the exception out
     * costs them the app, since these flows are read during startup. Every
     * value below has a default, so empty is a usable answer.
     */
    private val storedPreferences: Flow<Preferences> = context.userPreferencesDataStore.data
        .catch { e ->
            Timber.e(e, "Could not read stored preferences; falling back to defaults")
            emit(emptyPreferences())
        }

    /**
     * Apply an edit, absorbing a failed write. A preference that didn't save
     * is worth a log line, not a crash in whichever coroutine set it.
     */
    private suspend fun save(transform: (MutablePreferences) -> Unit) {
        try {
            context.userPreferencesDataStore.edit(transform)
        } catch (e: IOException) {
            Timber.e(e, "Could not save preferences")
        }
    }

    /**
     * Get the user preferences as a Flow.
     */
    val userPreferencesFlow: Flow<UserPreferences> = storedPreferences
        .map { preferences -> toUserPreferences(preferences) }

    /**
     * Update user preferences.
     */
    suspend fun updateUserPreferences(userPreferences: UserPreferences) {
        save { preferences ->
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
            preferences[PreferencesKeys.PREFERRED_CATEGORIES] =
                userPreferences.preferredCategories.map { it.name }.toSet()
        }
    }

    /**
     * The layers-sheet map style, restored when the map loads so it
     * survives app restarts. Stored values are sanitized so an unknown
     * value (e.g. from another app version) falls back to the default.
     */
    val mapStyleFlow: Flow<Int> = storedPreferences
        .map { preferences ->
            MapStyles.normalize(preferences[PreferencesKeys.MAP_STYLE])
        }

    suspend fun setMapStyle(style: Int) {
        save { preferences ->
            preferences[PreferencesKeys.MAP_STYLE] = style
        }
    }

    /**
     * Which mapping stack to use — OpenStreetMap unless the user opted into
     * Google Maps. Reads sanitize unknown stored names back to the default.
     */
    val mapProviderFlow: Flow<MapProvider> = storedPreferences
        .map { preferences ->
            MapProvider.fromStorage(preferences[PreferencesKeys.MAP_PROVIDER])
        }

    suspend fun setMapProvider(provider: MapProvider) {
        save { preferences ->
            preferences[PreferencesKeys.MAP_PROVIDER] = provider.name
        }
    }

    /**
     * The account tier: STANDARD (ads, parsed narration) or PREMIUM
     * (no ads, Gemini narration). Unknown stored names fall back to
     * STANDARD.
     */
    val accountTierFlow: Flow<AccountTier> = storedPreferences
        .map { preferences ->
            AccountTier.fromStorage(preferences[PreferencesKeys.ACCOUNT_TIER])
        }

    suspend fun setAccountTier(tier: AccountTier) {
        save { preferences ->
            preferences[PreferencesKeys.ACCOUNT_TIER] = tier.name
        }
    }

    /**
     * Layers-sheet style for the Google map, kept separately from the
     * OpenFreeMap style so switching providers forgets neither choice.
     */
    val googleMapStyleFlow: Flow<Int> = storedPreferences
        .map { preferences ->
            GoogleMapStyles.normalize(preferences[PreferencesKeys.GOOGLE_MAP_STYLE])
        }

    suspend fun setGoogleMapStyle(style: Int) {
        save { preferences ->
            preferences[PreferencesKeys.GOOGLE_MAP_STYLE] = style
        }
    }

    /** The layers-sheet traffic overlay toggle (Google map only). */
    val mapTrafficFlow: Flow<Boolean> = storedPreferences
        .map { preferences ->
            preferences[PreferencesKeys.MAP_TRAFFIC] ?: false
        }

    suspend fun setMapTraffic(enabled: Boolean) {
        save { preferences ->
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
        save { preferences ->
            audioEnabled?.let { preferences[PreferencesKeys.AUDIO_ENABLED] = it }
            voiceSpeed?.let { preferences[PreferencesKeys.VOICE_SPEED] = it }
            voicePitch?.let { preferences[PreferencesKeys.VOICE_PITCH] = it }
            voiceLanguage?.let { preferences[PreferencesKeys.VOICE_LANGUAGE] = it }
            autoPlayContent?.let { preferences[PreferencesKeys.AUTO_PLAY_CONTENT] = it }
        }
    }
}