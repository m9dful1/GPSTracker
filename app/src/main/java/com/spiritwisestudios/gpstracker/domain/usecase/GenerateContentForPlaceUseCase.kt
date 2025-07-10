package com.spiritwisestudios.gpstracker.domain.usecase

import com.spiritwisestudios.gpstracker.domain.model.PointOfInterest
import com.spiritwisestudios.gpstracker.domain.model.TourContent
import com.spiritwisestudios.gpstracker.domain.model.UserPreferences
import com.spiritwisestudios.gpstracker.domain.repository.ContentRepository
import com.spiritwisestudios.gpstracker.domain.service.ContentService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import java.util.UUID
import javax.inject.Inject

/**
 * Use case for generating AI content for a point of interest.
 */
class GenerateContentForPlaceUseCase @Inject constructor(
    private val contentService: ContentService,
    private val contentRepository: ContentRepository
) {
    /**
     * Generate content for a point of interest.
     *
     * @param pointOfInterest The point of interest to generate content for
     * @param userPreferences User preferences for content customization
     * @return Flow emitting content generation status and result
     */
    operator fun invoke(
        pointOfInterest: PointOfInterest,
        userPreferences: UserPreferences
    ): Flow<ContentService.ContentGenerationResult> {
        return contentService.generateContent(
            pointOfInterest = pointOfInterest,
            userPreferences = userPreferences
        ).onEach { result ->
            // When content generation is successful, save it to the repository
            if (result is ContentService.ContentGenerationResult.Success) {
                contentRepository.saveContent(result.content)
            }
        }
    }

    /**
     * Create a placeholder content while waiting for AI generation.
     *
     * @param pointOfInterest The point of interest
     * @return Placeholder tour content
     */
    fun createPlaceholderContent(pointOfInterest: PointOfInterest): TourContent {
        return TourContent(
            id = UUID.randomUUID().toString(),
            poiId = pointOfInterest.id,
            title = "About ${pointOfInterest.name}",
            content = "Generating interesting information about ${pointOfInterest.name}...",
            summary = "Generating content...",
            source = TourContent.ContentSource.AI_GENERATED
        )
    }
} 