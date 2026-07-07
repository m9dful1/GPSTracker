package com.spiritwisestudios.gpstracker.data.repository

import com.spiritwisestudios.gpstracker.domain.model.PointOfInterest
import com.spiritwisestudios.gpstracker.domain.model.TourContent
import com.spiritwisestudios.gpstracker.domain.model.UserPreferences
import com.spiritwisestudios.gpstracker.domain.service.ContentService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository facade over [ContentService] for the UI layer. Content comes from
 * Wikipedia (cached in Room) with a template fallback — see ContentServiceImpl.
 */
@Singleton
class TourContentRepository @Inject constructor(
    private val contentService: ContentService
) {

    /**
     * Get content for a place, generating it if needed.
     */
    suspend fun getContentForPlace(
        pointOfInterest: PointOfInterest,
        userPreferences: UserPreferences
    ): TourContent {
        return contentService.getContentForPlace(pointOfInterest, userPreferences)
    }

    /**
     * Generate content for a place with progress updates.
     */
    fun generateContent(
        pointOfInterest: PointOfInterest,
        userPreferences: UserPreferences
    ): Flow<ContentGenerationResult> {
        return contentService.generateContent(pointOfInterest, userPreferences)
            .map { result ->
                when (result) {
                    is ContentService.ContentGenerationResult.Success ->
                        ContentGenerationResult.Success(result.content)
                    is ContentService.ContentGenerationResult.InProgress ->
                        ContentGenerationResult.InProgress(result.progress)
                    is ContentService.ContentGenerationResult.Error ->
                        ContentGenerationResult.Error(result.message)
                    is ContentService.ContentGenerationResult.Queued ->
                        ContentGenerationResult.Queued
                }
            }
    }

    /**
     * Pre-fetch content for a list of places.
     */
    suspend fun prefetchContent(
        pointsOfInterest: List<PointOfInterest>,
        userPreferences: UserPreferences
    ) {
        contentService.prefetchContent(pointsOfInterest, userPreferences)
    }

    /**
     * Result class for content generation, kept for UI-layer compatibility.
     */
    sealed class ContentGenerationResult {
        data class Success(val content: TourContent) : ContentGenerationResult()
        data class InProgress(val progress: Float) : ContentGenerationResult()
        data class Error(val message: String) : ContentGenerationResult()
        object Queued : ContentGenerationResult()
    }
}
