package com.spiritwisestudios.gpstracker.domain.service

import com.spiritwisestudios.gpstracker.domain.model.LatLng
import com.spiritwisestudios.gpstracker.domain.model.PointOfInterest
import com.spiritwisestudios.gpstracker.domain.model.TourContent
import com.spiritwisestudios.gpstracker.domain.model.UserPreferences
import kotlinx.coroutines.flow.Flow

/**
 * Service interface for AI content generation and management.
 */
interface ContentService {
    
    /**
     * Generate descriptive content for a point of interest using AI.
     * 
     * @param pointOfInterest The point of interest to generate content for
     * @param userPreferences User preferences for content customization
     * @return Flow emitting the content generation status and result
     */
    fun generateContent(
        pointOfInterest: PointOfInterest, 
        userPreferences: UserPreferences
    ): Flow<ContentGenerationResult>
    
    /**
     * Get content for a specific point of interest.
     * Will retrieve from cache if available, otherwise generate new content.
     * 
     * @param pointOfInterest The point of interest
     * @param userPreferences User preferences for content customization
     * @return The tour content
     */
    suspend fun getContentForPlace(
        pointOfInterest: PointOfInterest,
        userPreferences: UserPreferences
    ): TourContent
    
    /**
     * Pre-fetch content for points of interest that may be encountered soon.
     * 
     * @param pointsOfInterest Points of interest to pre-fetch
     * @param userPreferences User preferences for content customization
     */
    suspend fun prefetchContent(
        pointsOfInterest: List<PointOfInterest>,
        userPreferences: UserPreferences
    )
    
    /**
     * Regional "way of life" color about the area around the listener,
     * used to fill long quiet stretches between sights.
     *
     * @param regionName Name of the city or town the listener is near
     * @param location The region's coordinates, to validate the lookup
     * @param userPreferences User preferences for content customization
     * @return The content, or null when nothing documents the region
     */
    suspend fun getWayOfLifeContent(
        regionName: String,
        location: LatLng,
        userPreferences: UserPreferences
    ): TourContent?

    /**
     * Queue content for delivery based on priority and relevance.
     *
     * @param content Tour content to queue
     * @param priority Priority level (higher values have higher priority)
     * @return True if successfully queued
     */
    fun queueContentForDelivery(content: TourContent, priority: Int): Boolean
    
    /**
     * Get the next piece of content from the delivery queue.
     * 
     * @return The next tour content or null if queue is empty
     */
    suspend fun getNextContent(): TourContent?

    /**
     * Look at the next queued content without removing it.
     *
     * @return The next tour content or null if queue is empty
     */
    fun peekNextContent(): TourContent?

    /**
     * Record that a place's story has been told, so the same place isn't
     * queued again for the rest of the tour. Called when a narration
     * finishes, not when it is dequeued: content dropped for being behind
     * the listener was never actually told.
     *
     * @param poiId The place whose content was narrated
     */
    fun markContentDelivered(poiId: String)

    /**
     * Clear the content delivery queue, and with it the record of what has
     * been told. Ends the tour session.
     */
    fun clearContentQueue()

    /**
     * Drop cached stories that are too old or too many, per
     * [com.spiritwisestudios.gpstracker.util.StoryCachePolicy]. The Tour
     * Journal is untouched: it is the user's history, not a cache.
     *
     * @return How many cached stories were dropped.
     */
    suspend fun pruneStoryCache(): Int

    /**
     * Forget every cached story, so the next telling is fetched fresh. The
     * user's own control over the cache; the journal survives it.
     */
    suspend fun clearStoryCache()
    
    /**
     * Result class for content generation operations.
     */
    sealed class ContentGenerationResult {
        data class Success(val content: TourContent) : ContentGenerationResult()
        data class InProgress(val progress: Float) : ContentGenerationResult()
        data class Error(val message: String) : ContentGenerationResult()
        data object Queued : ContentGenerationResult()
    }
} 