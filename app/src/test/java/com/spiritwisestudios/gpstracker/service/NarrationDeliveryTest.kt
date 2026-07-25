package com.spiritwisestudios.gpstracker.service

import com.spiritwisestudios.gpstracker.data.service.ContentDeliveryQueue
import com.spiritwisestudios.gpstracker.domain.model.LatLng
import com.spiritwisestudios.gpstracker.domain.model.PointOfInterest
import com.spiritwisestudios.gpstracker.domain.model.TourContent
import com.spiritwisestudios.gpstracker.domain.model.UserPreferences
import com.spiritwisestudios.gpstracker.domain.service.AudioService
import com.spiritwisestudios.gpstracker.domain.service.ContentService
import com.spiritwisestudios.gpstracker.util.TourLogic
import com.spiritwisestudios.gpstracker.util.TourSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The delivery loop, run against fakes. Everything here used to be reachable
 * only by starting a foreground service and driving somewhere.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NarrationDeliveryTest {

    // --- fakes ---

    /** A queue-backed content service; the generation half is never reached. */
    private class FakeContentService : ContentService {
        val queue = ContentDeliveryQueue()
        val delivered = mutableListOf<String>()

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
            throw UnsupportedOperationException("delivery never generates content")

        override suspend fun getContentForPlace(
            pointOfInterest: PointOfInterest,
            userPreferences: UserPreferences
        ): TourContent = throw UnsupportedOperationException("delivery never fetches content")

        override suspend fun prefetchContent(
            pointsOfInterest: List<PointOfInterest>,
            userPreferences: UserPreferences
        ) = throw UnsupportedOperationException("delivery never prefetches")

        override suspend fun getWayOfLifeContent(
            regionName: String,
            location: LatLng,
            userPreferences: UserPreferences
        ): TourContent? = throw UnsupportedOperationException("delivery tells places, not regions")

        override suspend fun pruneStoryCache(): Int =
            throw UnsupportedOperationException("delivery never prunes")

        override suspend fun clearStoryCache() =
            throw UnsupportedOperationException("delivery never clears the cache")
    }

    /**
     * Speaks by script: each call takes the next outcome. A null outcome is an
     * utterance that ended without finishing — stopped, or flushed by a newer
     * one — which is what the loop sees when something else takes the audio.
     */
    private class FakeAudioService(
        private val outcomes: MutableList<AudioService.SpeakingStatus?>
    ) : AudioService {
        val spoken = mutableListOf<String>()

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
        override fun isSpeaking(): Boolean = false
        override fun updateVoiceSettings(preferences: UserPreferences) = Unit
        override fun shutdown() = Unit
    }

    /** Records what the service would have shown and stored. */
    private open class RecordingHost(
        private val places: Map<String, PointOfInterest> = emptyMap(),
        private val geometry: Pair<TourLogic.RelativeDirection?, Float?> = null to null
    ) : NarrationDelivery.Host {
        val narrated = mutableListOf<String>()
        val recorded = mutableListOf<String>()
        var narrationVisible = false

        override suspend fun placeFor(poiId: String): PointOfInterest? = places[poiId]

        override suspend fun geometryFor(
            poi: PointOfInterest?
        ): Pair<TourLogic.RelativeDirection?, Float?> = geometry

        override fun spokenNarration(
            poi: PointOfInterest?,
            content: TourContent,
            direction: TourLogic.RelativeDirection?,
            distanceMeters: Float?
        ): String = "spoken: ${content.title}"

        override fun onNarrating(poi: PointOfInterest?, content: TourContent) {
            narrated += content.poiId
            narrationVisible = true
        }

        override fun onNothingNarrating() {
            narrationVisible = false
        }

        override suspend fun onNarrated(poi: PointOfInterest?, content: TourContent) {
            recorded += content.poiId
        }
    }

    // --- helpers ---

    private fun content(poiId: String) = TourContent(
        id = "content-$poiId",
        poiId = poiId,
        title = "About $poiId",
        content = "The story of $poiId",
        summary = "summary"
    )

    private fun place(id: String) = PointOfInterest(
        id = id,
        name = id,
        latLng = LatLng(45.0, -93.0),
        address = "",
        category = "historic"
    )

    private fun delivery(
        contentService: FakeContentService,
        audioService: FakeAudioService,
        host: RecordingHost,
        session: TourSession = TourSession()
    ) = NarrationDelivery(session, contentService, audioService, host)

    // --- telling the queue ---

    @Test
    fun `every queued story is told in turn`() = runTest {
        val contents = FakeContentService().apply {
            queue.offer(content("mill"), priority = 3)
            queue.offer(content("bridge"), priority = 2)
            queue.offer(content("chapel"), priority = 1)
        }
        val host = RecordingHost()

        delivery(contents, FakeAudioService(mutableListOf()), host).deliverQueued()

        assertEquals(listOf("mill", "bridge", "chapel"), host.narrated)
        assertEquals(listOf("mill", "bridge", "chapel"), host.recorded)
    }

    @Test
    fun `a told place is marked so it cannot be queued again`() = runTest {
        val contents = FakeContentService().apply { queue.offer(content("mill"), 0) }

        delivery(contents, FakeAudioService(mutableListOf()), RecordingHost()).deliverQueued()

        assertEquals(listOf("mill"), contents.delivered)
        assertFalse(contents.queue.offer(content("mill"), priority = 5))
    }

    @Test
    fun `an empty queue puts the fact card away`() = runTest {
        val host = RecordingHost()

        delivery(FakeContentService(), FakeAudioService(mutableListOf()), host).deliverQueued()

        assertFalse(host.narrationVisible)
        assertTrue(host.narrated.isEmpty())
    }

    @Test
    fun `the story is spoken with its introduction`() = runTest {
        val contents = FakeContentService().apply { queue.offer(content("mill"), 0) }
        val audio = FakeAudioService(mutableListOf())

        delivery(contents, audio, RecordingHost()).deliverQueued()

        assertEquals(listOf("spoken: About mill"), audio.spoken)
    }

    // --- being overtaken by the drive ---

    @Test
    fun `a place already behind the listener is skipped, not told`() = runTest {
        val contents = FakeContentService().apply { queue.offer(content("mill"), 0) }
        val audio = FakeAudioService(mutableListOf())
        // Well behind, and far enough back that its moment has passed
        val behind = RecordingHost(
            places = mapOf("mill" to place("mill")),
            geometry = TourLogic.RelativeDirection.BEHIND to 5_000f
        )

        delivery(contents, audio, behind).deliverQueued()

        assertTrue("nothing was spoken", audio.spoken.isEmpty())
        assertTrue("nothing was recorded as narrated", behind.recorded.isEmpty())
        assertTrue("nothing was marked delivered", contents.delivered.isEmpty())
    }

    @Test
    fun `skipping a stale story does not spend the error budget`() = runTest {
        // One story already behind the car, then as many failing ones as the
        // error cap allows. If the skip counted as a failure, the budget would
        // run out one utterance early and the last story never be attempted.
        val cap = TourLogic.MAX_CONSECUTIVE_DELIVERY_ERRORS
        val contents = FakeContentService().apply {
            queue.offer(content("stale"), priority = 100)
            repeat(cap) { queue.offer(content("place-$it"), priority = cap - it) }
        }
        val audio = FakeAudioService(MutableList(cap) { AudioService.SpeakingStatus.ERROR })
        var staleOnce = true
        val host = object : RecordingHost() {
            override suspend fun geometryFor(
                poi: PointOfInterest?
            ): Pair<TourLogic.RelativeDirection?, Float?> {
                return if (staleOnce) {
                    staleOnce = false
                    TourLogic.RelativeDirection.BEHIND to 5_000f
                } else {
                    null to null
                }
            }
        }

        delivery(contents, audio, host).deliverQueued()

        assertEquals("every story after the skip was still attempted", cap, audio.spoken.size)
    }

    // --- a broken engine ---

    @Test
    fun `a run of speech failures gives up and leaves the queue alone`() = runTest {
        val stories = (1..10).map { content("place-$it") }
        val contents = FakeContentService().apply {
            stories.forEachIndexed { i, story -> queue.offer(story, priority = 10 - i) }
        }
        // A dead engine: every utterance fails instantly
        val audio = FakeAudioService(
            MutableList(10) { AudioService.SpeakingStatus.ERROR }
        )
        val host = RecordingHost()

        delivery(contents, audio, host).deliverQueued()

        assertEquals(
            "gave up after the error cap",
            TourLogic.MAX_CONSECUTIVE_DELIVERY_ERRORS,
            audio.spoken.size
        )
        assertTrue("nothing was recorded as told", host.recorded.isEmpty())
        assertFalse("the fact card was put away", host.narrationVisible)
        assertEquals(
            "the rest of the queue survived for the next trigger",
            10 - TourLogic.MAX_CONSECUTIVE_DELIVERY_ERRORS,
            contents.queue.size()
        )
    }

    @Test
    fun `a success resets the failure run`() = runTest {
        // Two failures, a success, two more failures, a success. Each success
        // resets the run, so the cap of three is never reached and the last
        // story is still told — without the reset the third failure would end
        // the loop and the sixth story would never be attempted.
        val order = listOf("a", "b", "told-1", "d", "e", "told-2")
        val contents = FakeContentService().apply {
            order.forEachIndexed { i, id -> queue.offer(content(id), priority = order.size - i) }
        }
        val audio = FakeAudioService(
            mutableListOf(
                AudioService.SpeakingStatus.ERROR,
                AudioService.SpeakingStatus.ERROR,
                AudioService.SpeakingStatus.COMPLETED,
                AudioService.SpeakingStatus.ERROR,
                AudioService.SpeakingStatus.ERROR,
                AudioService.SpeakingStatus.COMPLETED
            )
        )
        val host = RecordingHost()

        delivery(contents, audio, host).deliverQueued()

        assertEquals("every story was attempted", order.size, audio.spoken.size)
        assertEquals(listOf("told-1", "told-2"), host.recorded)
    }

    // --- interruptions ---

    @Test
    fun `an interruption ends the loop and leaves the queue for later`() = runTest {
        val contents = FakeContentService().apply {
            queue.offer(content("mill"), priority = 2)
            queue.offer(content("bridge"), priority = 1)
        }
        // The first utterance ends without finishing: something else has the audio
        val audio = FakeAudioService(mutableListOf(null))
        val host = RecordingHost()

        delivery(contents, audio, host).deliverQueued()

        assertEquals(listOf("spoken: About mill"), audio.spoken)
        assertTrue(host.recorded.isEmpty())
        assertEquals(1, contents.queue.size())
    }

    @Test
    fun `a skip request moves to the next story instead of standing down`() = runTest {
        val contents = FakeContentService().apply {
            queue.offer(content("mill"), priority = 2)
            queue.offer(content("bridge"), priority = 1)
        }
        val session = TourSession()
        // The interruption is the listener asking for the next story
        val audio = object : AudioService by FakeAudioService(mutableListOf()) {
            val spoken = mutableListOf<String>()
            override fun speak(text: String): Flow<AudioService.SpeakingStatus> {
                spoken += text
                return flow {
                    emit(AudioService.SpeakingStatus.STARTED)
                    if (spoken.size == 1) {
                        // Asked for mid-utterance, exactly as the notification does
                        session.requestSkip()
                    } else {
                        emit(AudioService.SpeakingStatus.COMPLETED)
                    }
                }
            }
        }
        val host = RecordingHost()

        NarrationDelivery(session, contents, audio, host).deliverQueued()

        assertEquals(listOf("spoken: About mill", "spoken: About bridge"), audio.spoken)
        assertEquals("only the second one finished", listOf("bridge"), host.recorded)
    }

    // --- one loop at a time ---

    @Test
    fun `a second delivery does nothing while one is running`() = runTest {
        val contents = FakeContentService().apply { queue.offer(content("mill"), 0) }
        val session = TourSession()
        val host = RecordingHost()
        val loop = delivery(contents, FakeAudioService(mutableListOf()), host, session)

        // Delivery already claimed, as it would be by a geofence that got here
        // first and is still telling its story
        assertTrue("delivery was claimed", session.beginDelivery() != null)

        loop.deliverQueued()

        assertTrue("the running loop kept the queue", host.narrated.isEmpty())
        assertEquals(1, contents.queue.size())
    }

    @Test
    fun `delivery is released again when the loop ends`() = runTest {
        val contents = FakeContentService().apply { queue.offer(content("mill"), 0) }
        val session = TourSession()

        delivery(contents, FakeAudioService(mutableListOf()), RecordingHost(), session)
            .deliverQueued()

        assertFalse(session.isDelivering)
        assertTrue("the next trigger can deliver", session.beginDelivery() != null)
    }
}
