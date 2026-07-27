package com.spiritwisestudios.gpstracker.service

import com.spiritwisestudios.gpstracker.data.api.NearbyCityApiService
import com.spiritwisestudios.gpstracker.domain.model.LatLng
import com.spiritwisestudios.gpstracker.domain.model.TourContent
import com.spiritwisestudios.gpstracker.domain.model.UserPreferences
import com.spiritwisestudios.gpstracker.util.TourLogic
import com.spiritwisestudios.gpstracker.util.TourSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The quiet-stretch filler, run against fakes.
 *
 * Every case here used to need a foreground service, a location provider and
 * four silent minutes on a road above 18 km/h — which is why the feature
 * shipped with its policy tested (`TourLogic.shouldPlayWayOfLife`) and its
 * behaviour not.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WayOfLifeWatcherTest {

    private val reno = NearbyCityApiService.City("Reno", LatLng(39.53, -119.81), population = 264_165)

    /** A stretch that has earned a segment: quiet long enough, moving. */
    private val now = 10L * 60L * 1000L
    private val lastSpoke = now - TourLogic.QUIET_STRETCH_MS - 1

    private fun region(name: String) = TourContent(
        id = "wol-$name",
        poiId = "region:$name",
        title = "About $name",
        content = "$name grew up around the railroad.",
        summary = "$name",
        metadata = mapOf("sourceUrl" to "https://en.wikipedia.org/?curid=1")
    )

    /** Records what the service would have shown, and answers the lookups. */
    private open class RecordingHost(
        override val preferences: UserPreferences = UserPreferences(),
        override val lastSpokenAtMillis: Long = 0L,
        private val speed: Float? = 20f,
        private val location: LatLng? = LatLng(39.5, -119.8),
        private var regions: List<NearbyCityApiService.City> = emptyList(),
        /** Runs when a lookup happens, so a test can have a sight arrive mid-fetch. */
        private val onLookup: () -> Unit = {}
    ) : WayOfLifeWatcher.Host {

        val shown = mutableListOf<String>()
        val lookups = mutableListOf<LatLng>()
        var cardVisible = false
        var spokeCallbacks = 0
        var deliveredQueued = 0

        override suspend fun currentLocation(): LatLng? = location

        override fun currentSpeed(): Float? = speed

        override suspend fun regionsNear(
            location: LatLng,
            radiusMeters: Int
        ): List<NearbyCityApiService.City> {
            lookups += location
            onLookup()
            return regions
        }

        override fun onNarrating(regionName: String, content: TourContent) {
            shown += regionName
            cardVisible = true
        }

        override fun onNarrated() {
            cardVisible = false
        }

        override fun onSpoke() {
            spokeCallbacks++
        }

        override suspend fun deliverQueuedContent() {
            deliveredQueued++
        }
    }

    private fun watcher(
        host: WayOfLifeWatcher.Host,
        contents: FakeContentService,
        audio: FakeAudioService,
        session: TourSession = TourSession()
    ) = WayOfLifeWatcher(session, contents, audio, host)

    @Test
    fun `a long quiet stretch on the open road earns a segment`() = runTest {
        val host = RecordingHost(lastSpokenAtMillis = lastSpoke, regions = listOf(reno))
        val contents = FakeContentService().apply { wayOfLifeContent = region("Reno") }
        val audio = FakeAudioService()

        watcher(host, contents, audio).maybeSpeak(now)

        assertEquals(listOf("Reno"), host.shown)
        assertEquals(
            listOf("${TourLogic.wayOfLifeIntro("Reno")} ${region("Reno").content}"),
            audio.spoken
        )
        // The card goes up for the segment and comes down after it
        assertFalse(host.cardVisible)
        // And the quiet-stretch clock restarts, or the next pass would fire
        // again the moment the cooldown expires
        assertEquals(1, host.spokeCallbacks)
    }

    @Test
    fun `a sight already queued outranks the filler`() = runTest {
        val host = RecordingHost(lastSpokenAtMillis = lastSpoke, regions = listOf(reno))
        val contents = FakeContentService().apply {
            wayOfLifeContent = region("Reno")
            queue.offer(region("a place"), priority = 1)
        }
        val audio = FakeAudioService()

        watcher(host, contents, audio).maybeSpeak(now)

        assertTrue(audio.spoken.isEmpty())
        // Not even a lookup: a queued sight settles it before the network
        assertTrue(host.lookups.isEmpty())
    }

    @Test
    fun `a sight arriving during the region lookup wins, and keeps its cap slot`() = runTest {
        // The gap this closes: the busy check happens before a network call,
        // and a geofence can fire while it is in flight.
        val audio = FakeAudioService()
        val host = RecordingHost(
            lastSpokenAtMillis = lastSpoke,
            regions = listOf(reno),
            onLookup = { audio.speaking = true }
        )
        val contents = FakeContentService().apply { wayOfLifeContent = region("Reno") }
        val session = TourSession()

        watcher(host, contents, audio, session).maybeSpeak(now)

        assertTrue(audio.spoken.isEmpty())
        assertTrue(host.shown.isEmpty())
        // The hourly cap was never charged for a segment that didn't play
        assertTrue(session.canNarrate(now, maxPerHour = 1))
        // But the region was claimed before we knew we would speak, so this
        // tour won't describe it again. Pinned as it stands, not endorsed:
        // markRegionNarrated doubles as the "don't retry this" guard, and
        // separating the two is a behaviour change, not a refactor.
        assertFalse(session.markRegionNarrated("Reno"))
    }

    @Test
    fun `empty country backs off instead of asking again every pass`() = runTest {
        // The A10 bug: the emptiest roads are exactly what this feature is
        // for, and they got an Overpass POST every 30 seconds all drive.
        val host = RecordingHost(lastSpokenAtMillis = lastSpoke, regions = emptyList())
        val contents = FakeContentService()
        val watcher = watcher(host, contents, FakeAudioService())

        watcher.maybeSpeak(now)
        assertEquals(1, host.lookups.size)

        // A pass a minute later finds the cooldown still running
        watcher.maybeSpeak(now + 60_000)
        assertEquals(1, host.lookups.size)

        // Once the (now doubled) backoff has passed, it tries again
        watcher.maybeSpeak(now + TourLogic.wayOfLifeCooldownMs(1) + 1)
        assertEquals(2, host.lookups.size)
    }

    @Test
    fun `a region already described this session is not described again`() = runTest {
        val host = RecordingHost(lastSpokenAtMillis = lastSpoke, regions = listOf(reno))
        val contents = FakeContentService().apply { wayOfLifeContent = region("Reno") }
        val session = TourSession().apply { markRegionNarrated("Reno") }

        watcher(host, contents, FakeAudioService(), session).maybeSpeak(now)

        // The lookup happened, but nothing was fetched or spoken for it
        assertEquals(1, host.lookups.size)
        assertTrue(contents.wayOfLifeLookups.isEmpty())
    }

    @Test
    fun `an undocumented region costs a cooldown, not a repeat`() = runTest {
        val host = RecordingHost(lastSpokenAtMillis = lastSpoke, regions = listOf(reno))
        val contents = FakeContentService() // wayOfLifeContent stays null
        val watcher = watcher(host, contents, FakeAudioService())

        watcher.maybeSpeak(now)
        assertEquals(1, contents.wayOfLifeLookups.size)

        // Nothing documents it, so the next pass waits out a full cooldown
        watcher.maybeSpeak(now + 60_000)
        assertEquals(1, host.lookups.size)
    }

    @Test
    fun `highway speed asks for a shorter segment than the setting`() = runTest {
        val host = RecordingHost(
            preferences = UserPreferences(contentDetailLevel = UserPreferences.DetailLevel.DETAILED),
            lastSpokenAtMillis = lastSpoke,
            speed = 30f, // ~108 km/h
            regions = listOf(reno)
        )
        val contents = FakeContentService().apply { wayOfLifeContent = region("Reno") }

        watcher(host, contents, FakeAudioService()).maybeSpeak(now)

        assertEquals(
            listOf("Reno" to UserPreferences.DetailLevel.BRIEF),
            contents.wayOfLifeLookups
        )
    }

    @Test
    fun `a narration cap of zero mutes the filler like everything else automatic`() = runTest {
        val host = RecordingHost(
            preferences = UserPreferences(maxNotificationsPerHour = 0),
            lastSpokenAtMillis = lastSpoke,
            regions = listOf(reno)
        )
        val contents = FakeContentService().apply { wayOfLifeContent = region("Reno") }
        val audio = FakeAudioService()

        watcher(host, contents, audio).maybeSpeak(now)

        assertTrue(audio.spoken.isEmpty())
        assertTrue(host.lookups.isEmpty())
    }

    @Test
    fun `audio off or auto-play off means no filler at all`() = runTest {
        val contents = FakeContentService().apply { wayOfLifeContent = region("Reno") }

        for (preferences in listOf(
            UserPreferences(audioEnabled = false),
            UserPreferences(autoPlayContent = false)
        )) {
            val host = RecordingHost(
                preferences = preferences,
                lastSpokenAtMillis = lastSpoke,
                regions = listOf(reno)
            )
            val audio = FakeAudioService()

            watcher(host, contents, audio).maybeSpeak(now)

            assertTrue(audio.spoken.isEmpty())
            assertTrue(host.lookups.isEmpty())
        }
    }

    @Test
    fun `standing still means no filler, however long the quiet`() = runTest {
        // A parked car isn't on a quiet stretch, it's parked
        val host = RecordingHost(lastSpokenAtMillis = lastSpoke, speed = 0f, regions = listOf(reno))
        val audio = FakeAudioService()

        watcher(host, FakeContentService(), audio).maybeSpeak(now)

        assertTrue(audio.spoken.isEmpty())
        assertTrue(host.lookups.isEmpty())
    }

    @Test
    fun `no fix means no segment`() = runTest {
        val host = RecordingHost(
            lastSpokenAtMillis = lastSpoke,
            location = null,
            regions = listOf(reno)
        )

        watcher(host, FakeContentService(), FakeAudioService()).maybeSpeak(now)

        assertTrue(host.lookups.isEmpty())
    }

    @Test
    fun `a sight that queued while the segment played is told after it`() = runTest {
        val contents = FakeContentService().apply { wayOfLifeContent = region("Reno") }
        // A geofence fires while the segment is being spoken, which is the
        // case the filler has to hand the floor back for
        val host = object : RecordingHost(lastSpokenAtMillis = lastSpoke, regions = listOf(reno)) {
            override fun onSpoke() {
                super.onSpoke()
                contents.queue.offer(region("a place"), priority = 1)
            }
        }

        watcher(host, contents, FakeAudioService()).maybeSpeak(now)

        assertEquals(listOf("Reno"), host.shown)
        assertEquals(1, host.deliveredQueued)
    }

    @Test
    fun `reset lets the next tour start its backoff over`() = runTest {
        val host = RecordingHost(lastSpokenAtMillis = lastSpoke, regions = emptyList())
        val watcher = watcher(host, FakeContentService(), FakeAudioService())

        watcher.maybeSpeak(now)
        assertEquals(1, host.lookups.size)

        // Without the reset this pass is inside the backoff; the tour ending
        // has to clear it, or a new tour inherits the old one's silence
        watcher.reset()
        watcher.maybeSpeak(now + 60_000)
        assertEquals(2, host.lookups.size)
    }
}
