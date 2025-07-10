package com.spiritwisestudios.gpstracker.data.service

import com.spiritwisestudios.gpstracker.domain.model.PointOfInterest
import com.spiritwisestudios.gpstracker.domain.model.TourContent
import com.spiritwisestudios.gpstracker.domain.model.UserPreferences
import com.spiritwisestudios.gpstracker.domain.service.ContentService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import timber.log.Timber
import java.util.LinkedList
import java.util.Queue
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of ContentService that provides mock content for points of interest.
 */
@Singleton
class ContentServiceImpl @Inject constructor() : ContentService {
    
    private val contentCache = mutableMapOf<String, TourContent>()
    private val contentQueue: Queue<TourContent> = LinkedList()
    
    override fun generateContent(
        pointOfInterest: PointOfInterest,
        userPreferences: UserPreferences
    ): Flow<ContentService.ContentGenerationResult> = flow {
        emit(ContentService.ContentGenerationResult.InProgress(0.25f))
        
        // Simulate content generation time
        Thread.sleep(500)
        emit(ContentService.ContentGenerationResult.InProgress(0.5f))
        
        Thread.sleep(500)
        emit(ContentService.ContentGenerationResult.InProgress(0.75f))
        
        Thread.sleep(500)
        
        // Generate mock content based on POI
        val content = createMockContent(pointOfInterest, userPreferences)
        
        // Cache the content
        contentCache[pointOfInterest.id] = content
        
        emit(ContentService.ContentGenerationResult.Success(content))
    }
    
    override suspend fun getContentForPlace(
        pointOfInterest: PointOfInterest,
        userPreferences: UserPreferences
    ): TourContent {
        // Check if content is cached
        val cachedContent = contentCache[pointOfInterest.id]
        if (cachedContent != null) {
            Timber.d("Returning cached content for ${pointOfInterest.name}")
            return cachedContent
        }
        
        // Generate new content
        Timber.d("Generating new content for ${pointOfInterest.name}")
        return createMockContent(pointOfInterest, userPreferences)
    }
    
    override suspend fun prefetchContent(
        pointsOfInterest: List<PointOfInterest>,
        userPreferences: UserPreferences
    ) {
        // Mock prefetching by generating content for each POI not already in cache
        pointsOfInterest.forEach { poi ->
            if (!contentCache.containsKey(poi.id)) {
                val content = createMockContent(poi, userPreferences)
                contentCache[poi.id] = content
                Timber.d("Prefetched content for ${poi.name}")
            }
        }
    }
    
    override fun queueContentForDelivery(content: TourContent, priority: Int): Boolean {
        // In a real implementation, we would sort by priority
        // For this mock, we'll just add to the queue
        contentQueue.offer(content)
        Timber.d("Queued content: ${content.title} with priority $priority")
        return true
    }
    
    override suspend fun getNextContent(): TourContent? {
        return contentQueue.poll()
    }
    
    override fun clearContentQueue() {
        contentQueue.clear()
        Timber.d("Content queue cleared")
    }
    
    /**
     * Create mock content for a point of interest.
     */
    private fun createMockContent(
        pointOfInterest: PointOfInterest,
        userPreferences: UserPreferences
    ): TourContent {
        val detailLevel = when (userPreferences.contentDetailLevel) {
            UserPreferences.DetailLevel.BRIEF -> "brief"
            UserPreferences.DetailLevel.MEDIUM -> "moderate"
            UserPreferences.DetailLevel.DETAILED -> "detailed"
        }
        
        val title = "About ${pointOfInterest.name}"
        
        val description = pointOfInterest.description ?: "No description available"
        
        // Generate some mock content based on the POI category and name
        val content = buildString {
            append("Welcome to ${pointOfInterest.name}. ")
            
            when {
                pointOfInterest.category.contains("HISTORICAL", ignoreCase = true) -> {
                    append("This historical site has a rich past dating back many years. ")
                    if (detailLevel != "brief") {
                        append("The architectural style reflects the period in which it was built. ")
                    }
                    if (detailLevel == "detailed") {
                        append("Many important historical events took place here, shaping the city as we know it today. ")
                    }
                }
                pointOfInterest.category.contains("RESTAURANT", ignoreCase = true) -> {
                    append("This is a popular dining establishment. ")
                    if (detailLevel != "brief") {
                        append("The restaurant is known for its delicious food and welcoming atmosphere. ")
                    }
                    if (detailLevel == "detailed") {
                        append("Many locals recommend trying their specialty dishes and unique flavors. ")
                    }
                }
                pointOfInterest.category.contains("PARK", ignoreCase = true) -> {
                    append("This is a beautiful green space in the city. ")
                    if (detailLevel != "brief") {
                        append("The park offers various recreational activities and space to relax. ")
                    }
                    if (detailLevel == "detailed") {
                        append("It's home to diverse plant species and wildlife, making it a perfect spot for nature lovers. ")
                    }
                }
                else -> {
                    append("This is an interesting point of interest in the area. ")
                    if (detailLevel != "brief") {
                        append("It's worth spending some time exploring here. ")
                    }
                    if (detailLevel == "detailed") {
                        append("There are many unique features that make this location special to locals and visitors alike. ")
                    }
                }
            }
            
            append("Located at ${pointOfInterest.address}. ")
            
            if (pointOfInterest.rating != null) {
                append("This place has a rating of ${pointOfInterest.rating} stars. ")
            }
        }
        
        val summary = "A ${detailLevel} guide to ${pointOfInterest.name}"
        
        return TourContent(
            id = UUID.randomUUID().toString(),
            poiId = pointOfInterest.id,
            title = title,
            content = content,
            summary = summary,
            audioDuration = content.length / 20 // Rough estimate: 1 second per 20 characters
        )
    }
} 