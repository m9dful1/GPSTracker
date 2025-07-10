package com.spiritwisestudios.gpstracker.domain.model

/**
 * Domain model representing user preferences for the tour guide application.
 */
data class UserPreferences(
    val id: String = "default",
    val audioEnabled: Boolean = true,
    val voiceSpeed: Float = 1.0f,
    val voicePitch: Float = 1.0f,
    val voiceLanguage: String = "en-US",
    val autoPlayContent: Boolean = true,
    val preferredCategories: Set<PointOfInterest.Category> = setOf(
        PointOfInterest.Category.HISTORICAL,
        PointOfInterest.Category.CULTURAL,
        PointOfInterest.Category.ARCHITECTURAL
    ),
    val contentDetailLevel: DetailLevel = DetailLevel.MEDIUM,
    val notifyDistance: Int = 200, // Distance in meters to trigger notification
    val maxNotificationsPerHour: Int = 10,
    val prefetchContent: Boolean = true,
    val useMobileData: Boolean = false,
    val darkModeEnabled: Boolean = false
) {
    /**
     * Level of detail for content
     */
    enum class DetailLevel {
        BRIEF,
        MEDIUM,
        DETAILED
    }
} 