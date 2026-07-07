package com.spiritwisestudios.gpstracker.data.service

import com.spiritwisestudios.gpstracker.data.api.WikipediaApiService
import com.spiritwisestudios.gpstracker.data.db.dao.TourContentDao
import com.spiritwisestudios.gpstracker.data.db.entity.TourContentEntity
import com.spiritwisestudios.gpstracker.domain.model.PointOfInterest
import com.spiritwisestudios.gpstracker.domain.model.TourContent
import com.spiritwisestudios.gpstracker.domain.model.UserPreferences
import com.spiritwisestudios.gpstracker.domain.service.ContentService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import timber.log.Timber
import java.util.UUID

/**
 * Content service that narrates real facts: it looks up the Wikipedia article
 * for a place and uses its intro as narration, caching results in Room.
 * Falls back to a simple place-details template when no article exists.
 */
class ContentServiceImpl(
    private val wikipediaApiService: WikipediaApiService,
    private val tourContentDao: TourContentDao
) : ContentService {

    private val deliveryQueue = ContentDeliveryQueue()

    companion object {
        // Categories where "nearest Wikipedia article" is a safe guess even
        // without a title match (a park or church usually IS the article).
        private val LANDMARK_CATEGORIES = setOf(
            "HISTORICAL", "CULTURAL", "NATURAL", "ARCHITECTURAL", "ENTERTAINMENT"
        )

        // Cap prefetching so a busy corridor doesn't fire dozens of requests
        private const val MAX_PREFETCH = 10

        /**
         * Trim content to the user's detail level on sentence boundaries.
         */
        internal fun trimToDetailLevel(text: String, level: UserPreferences.DetailLevel): String {
            val sentences = text.split(Regex("(?<=[.!?])\\s+"))
            val keep = when (level) {
                UserPreferences.DetailLevel.BRIEF -> 2
                UserPreferences.DetailLevel.MEDIUM -> 5
                UserPreferences.DetailLevel.DETAILED -> Int.MAX_VALUE
            }
            return sentences.take(keep).joinToString(" ").trim()
        }
    }

    override fun generateContent(
        pointOfInterest: PointOfInterest,
        userPreferences: UserPreferences
    ): Flow<ContentService.ContentGenerationResult> = flow {
        emit(ContentService.ContentGenerationResult.InProgress(0.2f))
        try {
            val content = getContentForPlace(pointOfInterest, userPreferences)
            emit(ContentService.ContentGenerationResult.Success(content))
        } catch (e: Exception) {
            Timber.e(e, "Content generation failed for ${pointOfInterest.name}")
            emit(ContentService.ContentGenerationResult.Error(
                "Could not load facts for ${pointOfInterest.name}: ${e.message}"
            ))
        }
    }

    override suspend fun getContentForPlace(
        pointOfInterest: PointOfInterest,
        userPreferences: UserPreferences
    ): TourContent {
        // Serve from the Room cache when possible (stored untrimmed)
        tourContentDao.getContentForPoi(pointOfInterest.id)?.let { cached ->
            Timber.d("Returning cached content for ${pointOfInterest.name}")
            return cached.toDomainModel().trimmedTo(userPreferences.contentDetailLevel)
        }

        val article = wikipediaApiService.findArticleFor(
            name = pointOfInterest.name,
            location = pointOfInterest.latLng,
            allowNearestFallback = pointOfInterest.category.uppercase() in LANDMARK_CATEGORIES
        )

        return if (article != null) {
            val content = buildWikipediaContent(pointOfInterest, article)
            // Only real articles are worth caching; template fallbacks would
            // pin a boring result even after connectivity returns.
            tourContentDao.insertContent(TourContentEntity.fromDomainModel(content))
            Timber.d("Cached Wikipedia content for ${pointOfInterest.name} (${article.title})")
            content.trimmedTo(userPreferences.contentDetailLevel)
        } else {
            Timber.d("No Wikipedia article for ${pointOfInterest.name}; using fallback")
            buildFallbackContent(pointOfInterest).trimmedTo(userPreferences.contentDetailLevel)
        }
    }

    override suspend fun prefetchContent(
        pointsOfInterest: List<PointOfInterest>,
        userPreferences: UserPreferences
    ) {
        for (poi in pointsOfInterest.take(MAX_PREFETCH)) {
            if (tourContentDao.getContentForPoi(poi.id) != null) continue
            try {
                getContentForPlace(poi, userPreferences)
                Timber.d("Prefetched content for ${poi.name}")
            } catch (e: Exception) {
                Timber.e(e, "Failed to prefetch content for ${poi.name}")
            }
        }
    }

    override fun queueContentForDelivery(content: TourContent, priority: Int): Boolean {
        val queued = deliveryQueue.offer(content, priority)
        Timber.d("Queued content: ${content.title} with priority $priority")
        return queued
    }

    override suspend fun getNextContent(): TourContent? {
        return deliveryQueue.poll()
    }

    override fun peekNextContent(): TourContent? {
        return deliveryQueue.peek()
    }

    override fun clearContentQueue() {
        deliveryQueue.clear()
        Timber.d("Content queue cleared")
    }

    private fun buildWikipediaContent(
        poi: PointOfInterest,
        article: WikipediaApiService.WikiArticle
    ): TourContent {
        val intro = "You are near ${poi.name}. "
        val body = article.extract.trim()

        return TourContent(
            id = UUID.randomUUID().toString(),
            poiId = poi.id,
            title = "About ${poi.name}",
            content = intro + body,
            summary = trimToDetailLevel(body, UserPreferences.DetailLevel.BRIEF),
            source = TourContent.ContentSource.THIRD_PARTY,
            metadata = mapOf(
                "sourceUrl" to article.url,
                "wikipediaTitle" to article.title
            ),
            audioDuration = (intro.length + body.length) / 20 // ~20 chars per second
        )
    }

    /**
     * Template content from place details, used when no article is found.
     */
    private fun buildFallbackContent(poi: PointOfInterest): TourContent {
        val content = buildString {
            append("You are near ${poi.name}. ")

            when (poi.category.uppercase()) {
                "HISTORICAL" -> append("This site has historical significance in the area. ")
                "CULTURAL" -> append("This place showcases the culture of the region. ")
                "NATURAL" -> append("This is a green space worth a look. ")
                "ARCHITECTURAL" -> append("This building is an architectural point of interest. ")
                "ENTERTAINMENT" -> append("This is a popular local attraction. ")
                "DINING" -> append("This is a local dining spot. ")
                "SHOPPING" -> append("This is a local shopping destination. ")
                else -> append("This is a point of interest in the area. ")
            }

            if (poi.address.isNotEmpty()) {
                append("Located at ${poi.address}. ")
            }
            poi.rating?.let { append("Rated $it stars by visitors. ") }
            poi.description?.takeIf { it.isNotEmpty() }?.let { append(it) }
        }

        return TourContent(
            id = UUID.randomUUID().toString(),
            poiId = poi.id,
            title = "About ${poi.name}",
            content = content,
            summary = "A quick note about ${poi.name}",
            source = TourContent.ContentSource.PRE_POPULATED,
            audioDuration = content.length / 20
        )
    }

    private fun TourContent.trimmedTo(level: UserPreferences.DetailLevel): TourContent {
        return copy(content = trimToDetailLevel(content, level))
    }
}
