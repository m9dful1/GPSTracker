package com.spiritwisestudios.gpstracker.data.service

import com.spiritwisestudios.gpstracker.data.api.GeminiApiService
import com.spiritwisestudios.gpstracker.data.api.WikipediaApiService
import com.spiritwisestudios.gpstracker.data.db.dao.TourContentDao
import com.spiritwisestudios.gpstracker.data.db.entity.TourContentEntity
import com.spiritwisestudios.gpstracker.data.repository.AccountTierHolder
import com.spiritwisestudios.gpstracker.domain.model.LatLng
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
 * for a place, then (for premium accounts with a Gemini key configured) has
 * the model rewrite those facts into a brief tour-guide script — the most
 * interesting details, spoken style. Results are cached in Room. On the
 * standard tier, without a key, or when the model fails, the parsed article
 * intro is narrated directly; with no article at all, a simple place-details
 * template is used. The AI never writes without reference facts, so it can't
 * invent history for undocumented places.
 */
class ContentServiceImpl(
    private val wikipediaApiService: WikipediaApiService,
    private val geminiApiService: GeminiApiService,
    private val tourContentDao: TourContentDao,
    private val connectivityChecker: ConnectivityChecker,
    private val accountTierHolder: AccountTierHolder
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
         * Cache key for regional way-of-life content. Rides the same table
         * as POI narration (the poi_id column has no foreign key), with a
         * prefix no real place id uses.
         */
        internal fun regionCacheId(regionName: String) = "region:$regionName"

        /**
         * Whether a cached item matches what the account tier would
         * generate today. AI scripts belong to premium narration and
         * parsed/template content to standard, so a tier change (or a
         * newly added Gemini key) regenerates instead of serving the
         * other tier's cache.
         */
        internal fun cacheMatchesTier(
            cachedSource: TourContent.ContentSource,
            aiNarration: Boolean
        ): Boolean {
            return (cachedSource == TourContent.ContentSource.AI_GENERATED) == aiNarration
        }

        /**
         * Make encyclopedia text speakable. Wikipedia intros are written for
         * the page, not the ear: pronunciation guides in parentheses, IPA,
         * footnote markers. A TTS engine reads all of it aloud, so
         * parenthetical asides and bracketed references are dropped
         * (innermost first, so nested parentheticals unwrap fully) and
         * whitespace is healed around the cuts.
         */
        internal fun cleanForSpeech(text: String): String {
            var cleaned = text
            val parenthetical = Regex("\\([^()]*\\)")
            do {
                val before = cleaned
                cleaned = parenthetical.replace(cleaned, "")
            } while (cleaned != before)
            return cleaned
                .replace(Regex("\\[[^\\[\\]]*]"), "")
                .replace(Regex("\\s+([.,;:!?])"), "$1")
                .replace(Regex("\\s+"), " ")
                .trim()
        }

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
        // Gemini scripts are the premium experience; standard accounts get
        // the parsed article narration even when a key is configured
        val aiNarration = accountTierHolder.isPremium && geminiApiService.isConfigured

        // Serve from the Room cache when possible (stored untrimmed) —
        // unless it was written for the other tier, in which case it is
        // regenerated (and replaces the cached row, keyed by place)
        tourContentDao.getContentForPoi(pointOfInterest.id)?.let { cached ->
            val content = cached.toDomainModel()
            if (cacheMatchesTier(content.source, aiNarration)) {
                Timber.d("Returning cached content for ${pointOfInterest.name}")
                return content.trimmedTo(userPreferences.contentDetailLevel)
            }
            Timber.d("Cached content for ${pointOfInterest.name} is from the other tier; regenerating")
        }

        val article = wikipediaApiService.findArticleFor(
            name = pointOfInterest.name,
            location = pointOfInterest.latLng,
            allowNearestFallback = pointOfInterest.category.uppercase() in LANDMARK_CATEGORIES
        )

        return if (article != null) {
            val content = (if (aiNarration) buildAiContent(pointOfInterest, article) else null)
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
            referenceNotes = cleanForSpeech(article.extract),
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

    override suspend fun getWayOfLifeContent(
        regionName: String,
        location: LatLng,
        userPreferences: UserPreferences
    ): TourContent? {
        val aiNarration = accountTierHolder.isPremium && geminiApiService.isConfigured
        val cacheId = regionCacheId(regionName)

        // Same cache discipline as place narration: serve unless the cached
        // item was written for the other tier
        tourContentDao.getContentForPoi(cacheId)?.let { cached ->
            val content = cached.toDomainModel()
            if (cacheMatchesTier(content.source, aiNarration)) {
                Timber.d("Returning cached way-of-life content for $regionName")
                return content.trimmedTo(userPreferences.contentDetailLevel)
            }
            Timber.d("Cached way-of-life content for $regionName is from the other tier; regenerating")
        }

        // The region's own article, found by title rather than geosearch —
        // downtown geosearch surfaces the buildings, not the city
        val article = wikipediaApiService.findArticleByTitle(regionName, location) ?: return null
        val notes = cleanForSpeech(article.extract)
        if (notes.isBlank()) return null

        val script = if (aiNarration) {
            geminiApiService.writeWayOfLifeScript(regionName, notes)
        } else {
            null
        }
        val body = script ?: notes

        val content = TourContent(
            id = UUID.randomUUID().toString(),
            poiId = cacheId,
            title = "About $regionName",
            content = body,
            summary = trimToDetailLevel(body, UserPreferences.DetailLevel.BRIEF),
            source = if (script != null) {
                TourContent.ContentSource.AI_GENERATED
            } else {
                TourContent.ContentSource.THIRD_PARTY
            },
            metadata = mapOf(
                "sourceUrl" to article.url,
                "wikipediaTitle" to article.title
            ),
            audioDuration = body.length / 20 // ~20 chars per second
        )
        tourContentDao.insertContent(TourContentEntity.fromDomainModel(content))
        return content.trimmedTo(userPreferences.contentDetailLevel)
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
        // No "You are near X" lead-in: the spoken delivery already opens
        // with "On your left: X." and repeating the name back to back is
        // exactly the encyclopedia-being-read-aloud sound a guide avoids
        val body = cleanForSpeech(article.extract)

        return TourContent(
            id = UUID.randomUUID().toString(),
            poiId = poi.id,
            title = "About ${poi.name}",
            content = body,
            summary = trimToDetailLevel(body, UserPreferences.DetailLevel.BRIEF),
            source = TourContent.ContentSource.THIRD_PARTY,
            metadata = mapOf(
                "sourceUrl" to article.url,
                "wikipediaTitle" to article.title
            ),
            audioDuration = body.length / 20 // ~20 chars per second
        )
    }

    /**
     * Template content from place details, used when no article is found.
     */
    private fun buildFallbackContent(poi: PointOfInterest): TourContent {
        val content = buildString {
            // Like the article path, the place name is left to the spoken
            // positional intro rather than repeated here
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
