package com.spiritwisestudios.gpstracker.domain.repository

import com.spiritwisestudios.gpstracker.domain.model.PointOfInterest
import com.spiritwisestudios.gpstracker.domain.model.TourContent
import com.spiritwisestudios.gpstracker.domain.model.UserPreferences
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for accessing and managing tour content.
 */
interface ContentRepository {
    
    /**
     * Get content for a specific point of interest.
     * 
     * @param poiId ID of the point of interest
     * @param userPreferences User preferences for content customization
     * @return Tour content for the point of interest
     */
    suspend fun getContentForPoi(
        poiId: String, 
        userPreferences: UserPreferences
    ): Flow<TourContent>
    
    /**
     * Generate content for a point of interest using AI.
     * 
     * @param pointOfInterest The point of interest to generate content for
     * @param userPreferences User preferences for content customization
     * @return Generated tour content
     */
    suspend fun generateContentForPoi(
        pointOfInterest: PointOfInterest,
        userPreferences: UserPreferences
    ): TourContent
    
    /**
     * Save content to local storage.
     * 
     * @param content The content to save
     */
    suspend fun saveContent(content: TourContent)
    
    /**
     * Get locally cached content for a point of interest.
     * 
     * @param poiId ID of the point of interest
     * @return Cached tour content or null if not found
     */
    suspend fun getCachedContentForPoi(poiId: String): TourContent?
    
    /**
     * Pre-fetch content for multiple points of interest.
     * 
     * @param pointsOfInterest List of points of interest to pre-fetch content for
     * @param userPreferences User preferences for content customization
     */
    suspend fun prefetchContent(
        pointsOfInterest: List<PointOfInterest>,
        userPreferences: UserPreferences
    )
    
    /**
     * Get all locally cached content.
     * 
     * @return List of all cached tour content
     */
    suspend fun getAllCachedContent(): List<TourContent>
    
    /**
     * Clear outdated cached content.
     * 
     * @param olderThanDays Remove content older than this many days
     */
    suspend fun clearOutdatedContent(olderThanDays: Int = 30)
} 