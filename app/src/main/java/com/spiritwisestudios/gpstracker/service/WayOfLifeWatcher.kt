package com.spiritwisestudios.gpstracker.service

import com.spiritwisestudios.gpstracker.data.api.NearbyCityApiService
import com.spiritwisestudios.gpstracker.domain.model.LatLng
import com.spiritwisestudios.gpstracker.domain.model.TourContent
import com.spiritwisestudios.gpstracker.domain.model.UserPreferences
import com.spiritwisestudios.gpstracker.domain.service.AudioService
import com.spiritwisestudios.gpstracker.domain.service.ContentService
import com.spiritwisestudios.gpstracker.util.TourLogic
import com.spiritwisestudios.gpstracker.util.TourSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import timber.log.Timber

/**
 * Fills long quiet stretches with regional color — the coach guide's "way of
 * life" commentary about how people live around here, told between sights when
 * there is no sight to tell.
 *
 * Lives outside [TourModeService] for the reason [NarrationDelivery] does:
 * this decides when the guide speaks unprompted, and it used to be reachable
 * only by starting a foreground service and driving somewhere quiet for five
 * minutes.
 *
 * **Sights always outrank filler.** Anything speaking, delivering or queued
 * postpones a segment — and the check runs a second time after the region
 * lookup, because a sight can arrive while a region is being fetched.
 */
class WayOfLifeWatcher(
    private val session: TourSession,
    private val contentService: ContentService,
    private val audioService: AudioService,
    private val host: Host
) {

    /** What filling a quiet stretch needs from the tour service. */
    interface Host {

        /** The guide's settings, which the listener can edit mid-tour. */
        val preferences: UserPreferences

        /**
         * When the guide last said anything, sights and segments alike. This
         * is shared with every other narration path, which is why it stays
         * with the service rather than moving in here.
         */
        val lastSpokenAtMillis: Long

        /** Where the listener is, or null without a fix. */
        suspend fun currentLocation(): LatLng?

        /** How fast the listener is moving, in m/s, or null. */
        fun currentSpeed(): Float?

        /**
         * Towns near the listener, nearest first, empty when there is nothing
         * around. On the [Host] rather than the constructor because the
         * Overpass client behind it is a concrete final class, and a watcher
         * that can't be given a stand-in for it can't be tested.
         */
        suspend fun regionsNear(location: LatLng, radiusMeters: Int): List<NearbyCityApiService.City>

        /** A segment is starting: show it. */
        fun onNarrating(regionName: String, content: TourContent)

        /** The segment is over: put its fact card away. */
        fun onNarrated()

        /** The guide has just stopped speaking. */
        fun onSpoke()

        /** A sight queued up while the segment played; tell it now. */
        suspend fun deliverQueuedContent()
    }

    // The watcher's own bookkeeping: when the last segment played, and how
    // many lookups in a row found nothing worth saying. Nothing outside the
    // filler ever read either one.
    private var lastWayOfLifeAt: Long? = null
    private var emptyLookups = 0

    /**
     * Consider a segment every [CHECK_INTERVAL_MS] until cancelled.
     */
    suspend fun watch() = coroutineScope {
        while (isActive) {
            delay(CHECK_INTERVAL_MS)
            try {
                maybeSpeak(System.currentTimeMillis())
            } catch (e: CancellationException) {
                // A tour ending is not a filler error
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Error in way-of-life filler")
            }
        }
    }

    /**
     * One pass: play a segment if the stretch has earned it. [watch] calls
     * this on a timer; it takes [nowMillis] rather than reading the clock so
     * a quiet stretch can be described to it.
     */
    suspend fun maybeSpeak(nowMillis: Long) {
        val preferences = host.preferences
        if (!preferences.audioEnabled || !preferences.autoPlayContent) return

        val speed = host.currentSpeed() ?: 0f
        if (!TourLogic.shouldPlayWayOfLife(
                nowMillis,
                host.lastSpokenAtMillis,
                lastWayOfLifeAt,
                speed,
                narrationBusy(),
                TourLogic.wayOfLifeCooldownMs(emptyLookups)
            )
        ) {
            return
        }

        // Filler is still automatic narration: it counts against the hourly
        // cap, and a cap of zero mutes it like everything else automatic.
        // Advisory here, claimed for real once there is something to say.
        if (!session.canNarrate(nowMillis, preferences.maxNotificationsPerHour)) return

        val location = host.currentLocation() ?: return
        val region = host.regionsNear(location, CITY_RADIUS_METERS).firstOrNull()
        if (region == null) {
            // Nothing to talk about here — empty country, or a public API
            // turning us away. Either way, stamp the cooldown and back off:
            // without this, the emptiest roads (exactly what this feature is
            // for) got an Overpass POST every 30 seconds for the whole drive.
            emptyLookups++
            lastWayOfLifeAt = nowMillis
            Timber.d(
                "No region to describe; backing off to " +
                    "${TourLogic.wayOfLifeCooldownMs(emptyLookups) / 60_000}m"
            )
            return
        }
        // Found somewhere: the road isn't empty after all
        emptyLookups = 0

        if (!session.markRegionNarrated(region.name)) {
            // Nearest region already covered this session; wait out a full
            // cooldown instead of re-running the lookup every pass
            lastWayOfLifeAt = nowMillis
            return
        }

        // Speed-capped detail, like place narration
        val effectivePreferences = preferences.copy(
            contentDetailLevel = TourLogic.detailLevelFor(speed, preferences.contentDetailLevel)
        )
        val content = contentService.getWayOfLifeContent(
            region.name, region.latLng, effectivePreferences
        )
        if (content == null) {
            // Undocumented region: the session remembers it, so it isn't
            // retried until the next tour
            lastWayOfLifeAt = nowMillis
            return
        }

        // A sight may have arrived while we were fetching; it wins
        if (narrationBusy()) return

        // Claim the cap slot now, at the point of actually speaking
        if (!session.tryReserveNarration(nowMillis, preferences.maxNotificationsPerHour)) return
        lastWayOfLifeAt = nowMillis
        host.onNarrating(region.name, content)

        audioService.speak("${TourLogic.wayOfLifeIntro(region.name)} ${content.content}")
            .collectLatest { status ->
                when (status) {
                    AudioService.SpeakingStatus.COMPLETED,
                    AudioService.SpeakingStatus.ERROR -> host.onSpoke()
                    else -> {
                        // No action needed for other statuses
                    }
                }
            }

        // Hand the floor back: the card comes down, and if a sight queued up
        // while the segment played, it gets told after the usual breather
        host.onNarrated()
        if (!session.isDelivering && contentService.peekNextContent() != null) {
            delay(TourLogic.INTER_NARRATION_PAUSE_MS)
            if (!session.isDelivering) host.deliverQueuedContent()
        }
    }

    /**
     * Forget this tour's filler history, so the next tour can describe the
     * same region and starts its backoff from scratch.
     */
    fun reset() {
        lastWayOfLifeAt = null
        emptyLookups = 0
    }

    /** Whether real narration owns the audio, is playing, or is waiting. */
    private fun narrationBusy(): Boolean =
        audioService.isSpeaking() || session.isDelivering ||
            contentService.peekNextContent() != null

    companion object {
        /** How often a quiet stretch is reconsidered. */
        internal const val CHECK_INTERVAL_MS = 30_000L

        /** How far to look for a town worth describing. */
        internal const val CITY_RADIUS_METERS = 30_000
    }
}
