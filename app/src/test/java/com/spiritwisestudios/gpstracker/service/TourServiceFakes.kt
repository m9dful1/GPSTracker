package com.spiritwisestudios.gpstracker.service

import com.spiritwisestudios.gpstracker.data.service.ContentDeliveryQueue
import com.spiritwisestudios.gpstracker.domain.model.LatLng
import com.spiritwisestudios.gpstracker.domain.model.PointOfInterest
import com.spiritwisestudios.gpstracker.domain.model.TourContent
import com.spiritwisestudios.gpstracker.domain.model.UserPreferences
import com.spiritwisestudios.gpstracker.domain.service.AudioService
import com.spiritwisestudios.gpstracker.domain.service.ContentService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow

/**
 * Fakes shared by the tests for the pieces lifted out of [TourModeService] —
 * [NarrationDelivery] and [WayOfLifeWatcher]. Both need a content queue and a
 * scripted voice, and these interfaces are wide enough that a second copy of
 * each fake goes stale the first time one of them grows a member.
 */

/**
 * A queue-backed content service. Generation is unsupported except for the
 * one region lookup the quiet-stretch filler makes, which answers with
 * [wayOfLifeContent].
 */
internal class FakeContentService : ContentService {
    val queue = ContentDeliveryQueue()
    val delivered = mutableListOf<String>()

    /** What a region lookup returns; null means nothing documents the place. */
    var wayOfLifeContent: TourContent? = null

    /** Region names looked up, and the detail level asked for each time. */
    val wayOfLifeLookups = mutableListOf<Pair<String, UserPreferences.DetailLevel>>()

    override suspend fun getNextContent(): TourContent? = queue.poll()
    override fun peekNextContent(): TourContent? = queue.peek()
    override fun queueContentForDelivery(content: TourContent, priority: Int): Boolean =
        queue.offer(content, priority)

    override fun markContentDelivered(poiId: String) {
        delivered += poiId
        queue.markDelivered(poiId)
    }

    override fun clearContentQueue() = queue.clear()

    override fun generateContent(
        pointOfInterest: PointOfInterest,
        userPreferences: UserPreferences
    ): Flow<ContentService.ContentGenerationResult> =
        throw UnsupportedOperationException("these tests never generate content")

    override suspend fun getContentForPlace(
        pointOfInterest: PointOfInterest,
        userPreferences: UserPreferences
    ): TourContent = throw UnsupportedOperationException("these tests never fetch place content")

    override suspend fun prefetchContent(
        pointsOfInterest: List<PointOfInterest>,
        userPreferences: UserPreferences
    ) = throw UnsupportedOperationException("these tests never prefetch")

    override suspend fun getWayOfLifeContent(
        regionName: String,
        location: LatLng,
        userPreferences: UserPreferences
    ): TourContent? {
        wayOfLifeLookups += regionName to userPreferences.contentDetailLevel
        return wayOfLifeContent
    }

    override suspend fun pruneStoryCache(): Int =
        throw UnsupportedOperationException("these tests never prune")

    override suspend fun clearStoryCache() =
        throw UnsupportedOperationException("these tests never clear the cache")
}

/**
 * Speaks by script: each call takes the next outcome. A null outcome is an
 * utterance that ended without finishing — stopped, or flushed by a newer one
 * — which is what a caller sees when something else takes the audio.
 */
internal class FakeAudioService(
    private val outcomes: MutableList<AudioService.SpeakingStatus?> = mutableListOf()
) : AudioService {
    val spoken = mutableListOf<String>()

    /** Whether real narration currently owns the voice. */
    var speaking = false

    override fun speak(text: String): Flow<AudioService.SpeakingStatus> {
        spoken += text
        val outcome = if (outcomes.isEmpty()) {
            AudioService.SpeakingStatus.COMPLETED
        } else {
            outcomes.removeAt(0)
        }
        return flow {
            emit(AudioService.SpeakingStatus.STARTED)
            if (outcome != null) emit(outcome)
        }
    }

    override val speechProgress: StateFlow<Float> = MutableStateFlow(0f)
    override val voiceAvailability: StateFlow<AudioService.VoiceAvailability> =
        MutableStateFlow(AudioService.VoiceAvailability.READY)
    override val isPlaying: StateFlow<Boolean> = MutableStateFlow(false)

    override suspend fun initialize(userPreferences: UserPreferences): Boolean = true
    override fun speak(content: TourContent): Flow<AudioService.SpeakingStatus> =
        speak(content.content)

    override fun speakPriority(text: String): Flow<AudioService.SpeakingStatus> = speak(text)
    override fun pause(): Boolean = false
    override fun resume(): Boolean = false
    override fun stop() = Unit
    override fun isSpeaking(): Boolean = speaking
    override fun updateVoiceSettings(preferences: UserPreferences) = Unit
    override fun shutdown() = Unit
}
