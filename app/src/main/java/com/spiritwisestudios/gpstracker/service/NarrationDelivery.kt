package com.spiritwisestudios.gpstracker.service

import com.spiritwisestudios.gpstracker.domain.model.PointOfInterest
import com.spiritwisestudios.gpstracker.domain.model.TourContent
import com.spiritwisestudios.gpstracker.domain.service.AudioService
import com.spiritwisestudios.gpstracker.domain.service.ContentService
import com.spiritwisestudios.gpstracker.util.TourLogic
import com.spiritwisestudios.gpstracker.util.TourSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import timber.log.Timber

/**
 * Tells the queued stories, one after another, until the queue runs dry or
 * something takes the audio away.
 *
 * Lives outside [TourModeService] so it can be run against fakes: this is the
 * loop that decides what the guide says next, and it used to be reachable only
 * by starting a foreground service and driving somewhere.
 *
 * One loop at a time — [TourSession] hands out the right to deliver, so a
 * geofence and a proximity alert arriving together can't both start telling
 * stories. Errors are counted across the loop: a broken or muted engine fails
 * instantly, and retrying without limit would empty the queue in one pass, so
 * the loop gives up and leaves the rest for the next trigger.
 */
class NarrationDelivery(
    private val session: TourSession,
    private val contentService: ContentService,
    private val audioService: AudioService,
    private val host: Host
) {

    /**
     * Everything the loop needs from the tour service around a narration: what
     * a place is, where it sits, how it should be introduced, and what to do
     * when it has been told.
     */
    interface Host {

        /** The place a queued story is about, or null if it can't be found. */
        suspend fun placeFor(poiId: String): PointOfInterest?

        /**
         * Where [poi] sits relative to the listener right now: the quadrant
         * while moving, and the straight-line distance.
         */
        suspend fun geometryFor(
            poi: PointOfInterest?
        ): Pair<TourLogic.RelativeDirection?, Float?>

        /** The words to speak, introduction and all. */
        fun spokenNarration(
            poi: PointOfInterest?,
            content: TourContent,
            direction: TourLogic.RelativeDirection?,
            distanceMeters: Float?
        ): String

        /** A narration is starting: show it. */
        fun onNarrating(poi: PointOfInterest?, content: TourContent)

        /** Nothing is being said: put the fact card away. */
        fun onNothingNarrating()

        /** A narration finished: record the visit and the trip's tally. */
        suspend fun onNarrated(poi: PointOfInterest?, content: TourContent)
    }

    suspend fun deliverQueued() {
        val delivery = session.beginDelivery() ?: return
        try {
            var consecutiveErrors = 0

            while (true) {
                val content = contentService.getNextContent()
                if (content == null) {
                    host.onNothingNarrating()
                    return
                }

                val poi = host.placeFor(content.poiId)

                // Queued narrations can be overtaken by the drive. A guide
                // previews what's coming; a place that's already well behind
                // the listener is a story whose moment has passed — drop it and
                // move on (it stays unvisited, so a future pass can tell it).
                val (direction, distanceMeters) = host.geometryFor(poi)
                if (TourLogic.narrationIsStale(direction, distanceMeters)) {
                    Timber.d("Skipping ${content.title}: already ${distanceMeters?.toInt()}m behind")
                    // A skip is not a failure; the error run carries on unchanged
                    continue
                }

                host.onNarrating(poi, content)

                // Speak the content, introduced like a live tour guide
                // ("On your left: Fort Point. ..."). The loop resumes once the
                // flow finishes, so an interrupted utterance (stopped, or
                // flushed by a newer one) ends the loop cleanly instead of
                // leaving the delivery flag stuck.
                var outcome: AudioService.SpeakingStatus? = null
                session.clearSkipRequest()
                audioService.speak(host.spokenNarration(poi, content, direction, distanceMeters))
                    .collectLatest { status ->
                        when (status) {
                            AudioService.SpeakingStatus.COMPLETED -> {
                                // Told, so nothing queues this place again for
                                // the rest of the tour however often it re-alerts
                                contentService.markContentDelivered(content.poiId)
                                host.onNarrated(poi, content)
                                outcome = status
                            }
                            AudioService.SpeakingStatus.ERROR -> {
                                Timber.e("Error speaking content for ${content.title}")
                                outcome = status
                            }
                            else -> {
                                // No action needed for other statuses
                            }
                        }
                    }

                when (outcome) {
                    AudioService.SpeakingStatus.COMPLETED -> {
                        // A success ends the error run
                        consecutiveErrors = 0

                        // Breathing room before the next story: guides leave
                        // listeners time to look and talk instead of lecturing
                        // wall to wall
                        if (contentService.peekNextContent() != null) {
                            delay(TourLogic.INTER_NARRATION_PAUSE_MS)
                        }
                    }
                    AudioService.SpeakingStatus.ERROR -> {
                        consecutiveErrors++
                        if (!TourLogic.shouldKeepDeliveringAfterError(consecutiveErrors)) {
                            // The engine isn't speaking at all. Leave the rest
                            // of the queue for the next trigger rather than
                            // burning it, and drop the card — nothing is being
                            // said.
                            Timber.w("Giving up delivery after $consecutiveErrors speech failures in a row")
                            host.onNothingNarrating()
                            return
                        }
                    }
                    else -> {
                        // Interrupted. If the listener asked for the next story,
                        // that is exactly what this loop does next; anything
                        // else owns the audio now, so stand down.
                        if (!session.consumeSkipRequest()) return
                        Timber.d("Skipping to the next story on request")
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error delivering content")
        } finally {
            session.endDelivery(delivery)
        }
    }
}
