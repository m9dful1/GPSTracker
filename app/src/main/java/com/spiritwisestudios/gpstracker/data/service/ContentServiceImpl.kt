package com.spiritwisestudios.gpstracker.data.service

import com.spiritwisestudios.gpstracker.data.api.GeminiApiService
import com.spiritwisestudios.gpstracker.data.api.WikipediaApiService
import com.spiritwisestudios.gpstracker.data.db.dao.TourContentDao
import com.spiritwisestudios.gpstracker.data.db.entity.TourContentEntity
import com.spiritwisestudios.gpstracker.domain.model.PointOfInterest
import com.spiritwisestudios.gpstracker.domain.model.TourContent
import com.spiritwisestudios.gpstracker.domain.model.UserPreferences
import com.spiritwisestudios.gpstracker.domain.service.ConnectivityChecker
import com.spiritwisestudios.gpstracker.domain.service.ContentService
import com.spiritwisestudios.gpstracker.util.TourLogic
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import timber.log.Timber
import java.util.UUID

/**
 * Content service that narrates real facts: it looks up the Wikipedia article
 * for a place, then (when a Gemini key is configured) has the model rewrite
 * those facts into a brief tour-guide script — the most interesting details,
 * spoken style. Results are cached in Room. Without a key, or when the model
 * fails, the article intro is narrated directly; with no article at all, a
 * simple place-details template is used. The AI never writes without
 * reference facts, so it can't invent history for undocumented places.
 */
class ContentServiceImpl(
    private val wikipediaApiService: WikipediaApiService,
    private val geminiApiService: GeminiApiService,
    private val tourContentDao: TourContentDao,
    private val connectivityChecker: ConnectivityChecker
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
            val content = buildAiContent(pointOfInterest, article)
                ?: buildWikipediaContent(pointOfInterest, article)
            // Only real articles are worth caching; template fallbacks would
            // pin a boring result even after connectivity returns.
            tourContentDao.insertContent(TourContentEntity.fromDomainModel(content))
            Timber.d("Cached ${content.source} content for ${pointOfInterest.name} (${article.title})")
            content.trimmedTo(userPreferences.contentDetailLevel)
        } else {
            Timber.d("No Wikipedia article for ${pointOfInterest.name}; using fallback")
            buildFallbackContent(pointOfInterest).trimmedTo(userPreferences.contentDetailLevel)
        }
    }

    /**
     * A tour-guide script written by Gemini from the article's facts, or
     * null when the service is unconfigured or the model fails — the plain
     * Wikipedia narration takes over in that case.
     */
    private suspend fun buildAiContent(
        poi: PointOfInterest,
        article: WikipediaApiService.WikiArticle
    ): TourContent? {
        val script = geminiApiService.writeTourScript(
            placeName = poi.name,
            category = poi.category,
            referenceNotes = article.extract.trim(),
            extraDetails = listOfNotNull(
                poi.address.takeIf { it.isNotEmpty() },
                poi.description?.takeIf { it.isNotEmpty() }
            ).joinToString("\n")
        ) ?: return null

        return TourContent(
            id = UUID.randomUUID().toString(),
            poiId = poi.id,
            title = "About ${poi.name}",
            // No "You are near X" lead-in: the spoken delivery already opens
            // with "On your left: X." and the script continues from there
            content = script,
            summary = trimToDetailLevel(script, UserPreferences.DetailLevel.BRIEF),
            source = TourContent.ContentSource.AI_GENERATED,
            metadata = mapOf(
                "sourceUrl" to article.url,
                "wikipediaTitle" to article.title
            ),
            audioDuration = script.length / 20 // ~20 chars per second
        )
    }

    override suspend fun prefetchContent(
        pointsOfInterest: List<PointOfInterest>,
        userPreferences: UserPreferences
    ) {
        // Honor the "use mobile data" setting: speculative batch downloads
        // wait for Wi-Fi unless the user opted in. On-demand narration
        // fetches are unaffected.
        if (!TourLogic.shouldPrefetchContent(
                allowMobileData = userPreferences.useMobileData,
                onUnmeteredNetwork = connectivityChecker.isOnUnmeteredNetwork()
            )
        ) {
            Timber.d("Skipping content prefetch on metered network (use mobile data is off)")
            return
        }

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
