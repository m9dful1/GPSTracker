package com.spiritwisestudios.gpstracker.data.repository

import com.spiritwisestudios.gpstracker.domain.model.PointOfInterest
import com.spiritwisestudios.gpstracker.domain.model.TourContent
import com.spiritwisestudios.gpstracker.domain.model.UserPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import timber.log.Timber
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for managing tour content.
 * In this phase, we're implementing a basic mock implementation
 * that will generate placeholder content for places.
 */
@Singleton
class TourContentRepository @Inject constructor() {
    
    // In-memory cache of tour content
    private val contentCache = ConcurrentHashMap<String, TourContent>()
    
    /**
     * Get content for a place, generating it if needed.
     */
    suspend fun getContentForPlace(
        pointOfInterest: PointOfInterest,
        userPreferences: UserPreferences
    ): TourContent {
        // Check if content is already in cache
        val cachedContent = contentCache[pointOfInterest.id]
        if (cachedContent != null) {
            return cachedContent
        }
        
        // Generate new content (in a real app, this would call an AI service)
        val content = generateMockContent(pointOfInterest, userPreferences)
        contentCache[pointOfInterest.id] = content
        return content
    }
    
    /**
     * Generate content for a place with simulated progress.
     */
    fun generateContent(
        pointOfInterest: PointOfInterest,
        userPreferences: UserPreferences
    ): Flow<ContentGenerationResult> = flow {
        emit(ContentGenerationResult.Queued)
        
        // Simulate processing time
        emit(ContentGenerationResult.InProgress(0.3f))
        delay(500)
        emit(ContentGenerationResult.InProgress(0.7f))
        delay(500)
        
        try {
            val content = generateMockContent(pointOfInterest, userPreferences)
            contentCache[pointOfInterest.id] = content
            emit(ContentGenerationResult.Success(content))
        } catch (e: Exception) {
            Timber.e(e, "Failed to generate content for place: ${pointOfInterest.name}")
            emit(ContentGenerationResult.Error("Failed to generate content: ${e.message}"))
        }
    }
    
    /**
     * Generate mock content for a place.
     * In a real implementation, this would call an AI service.
     */
    private fun generateMockContent(
        pointOfInterest: PointOfInterest, 
        userPreferences: UserPreferences
    ): TourContent {
        // Different content detail based on user preference
        val contentDetail = when (userPreferences.contentDetailLevel) {
            UserPreferences.DetailLevel.BRIEF -> "brief"
            UserPreferences.DetailLevel.MEDIUM -> "somewhat detailed"
            UserPreferences.DetailLevel.DETAILED -> "very detailed"
        }
        
        // Generate mock content based on the type of place
        val description = when {
            pointOfInterest.category.contains("HISTORICAL", ignoreCase = true) -> 
                "This is a $contentDetail description of the historical site ${pointOfInterest.name}. " +
                "This location has significant historical importance to the area, dating back many years. " +
                "Visitors can learn about local history and cultural heritage here."
                
            pointOfInterest.category.contains("CULTURAL", ignoreCase = true) -> 
                "This is a $contentDetail description of the cultural attraction ${pointOfInterest.name}. " +
                "This place showcases the rich cultural traditions of the region. " +
                "Visitors can experience authentic local arts, music, and performances."
                
            pointOfInterest.category.contains("NATURAL", ignoreCase = true) -> 
                "This is a $contentDetail description of the natural landmark ${pointOfInterest.name}. " +
                "This beautiful natural feature is home to diverse wildlife and plant species. " +
                "The area offers stunning views and opportunities for outdoor activities."
                
            pointOfInterest.category.contains("ARCHITECTURAL", ignoreCase = true) -> 
                "This is a $contentDetail description of the architectural marvel ${pointOfInterest.name}. " +
                "This building exemplifies the architectural style of its period. " +
                "Notice the intricate details and the innovative structural elements."
                
            else -> 
                "This is a $contentDetail description of ${pointOfInterest.name}. " +
                "This location is a popular destination in the area. " +
                "Visitors come here for its unique atmosphere and offerings."
        }
        
        return TourContent(
            id = UUID.randomUUID().toString(),
            poiId = pointOfInterest.id,
            title = "About ${pointOfInterest.name}",
            content = description,
            summary = description.split(".").first(),
            createdAt = Date(),
            updatedAt = Date(),
            source = TourContent.ContentSource.AI_GENERATED,
            audioDuration = description.length / 20 * 1000 // Rough estimate: 20 chars per second
        )
    }
    
    /**
     * Clear the content cache.
     */
    fun clearCache() {
        contentCache.clear()
    }
    
    /**
     * Pre-fetch content for a list of places.
     */
    suspend fun prefetchContent(
        pointsOfInterest: List<PointOfInterest>,
        userPreferences: UserPreferences
    ) {
        for (poi in pointsOfInterest) {
            if (!contentCache.containsKey(poi.id)) {
                try {
                    val content = generateMockContent(poi, userPreferences)
                    contentCache[poi.id] = content
                } catch (e: Exception) {
                    Timber.e(e, "Failed to prefetch content for place: ${poi.name}")
                }
            }
        }
    }
    
    /**
     * Result class for content generation.
     */
    sealed class ContentGenerationResult {
        data class Success(val content: TourContent) : ContentGenerationResult()
        data class InProgress(val progress: Float) : ContentGenerationResult()
        data class Error(val message: String) : ContentGenerationResult()
        object Queued : ContentGenerationResult()
    }
} 