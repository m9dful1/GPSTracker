package com.spiritwisestudios.gpstracker.data.service

import com.spiritwisestudios.gpstracker.domain.model.TourContent
import java.util.PriorityQueue

/**
 * Thread-safe priority queue for narration delivery. Higher priority is served
 * first; equal priorities are served FIFO.
 *
 * One place, one telling. Three paths queue the story for a single place —
 * the geofence `enter`, the `dwell` half a minute later, and a proximity
 * alert — and a guide who repeats itself is the loudest complaint about tours
 * like this one. The queue holds at most one entry per place, and remembers
 * what it has already handed over so a re-alert can't queue it again.
 */
class ContentDeliveryQueue {

    private data class Entry(val priority: Int, val sequence: Long, val content: TourContent)

    private val lock = Any()
    private var nextSequence = 0L
    private val queue = PriorityQueue(
        compareByDescending<Entry> { it.priority }.thenBy { it.sequence }
    )

    // Places whose story has actually been told, for the length of a tour
    private val delivered = mutableSetOf<String>()

    /**
     * @return whether the content was queued. False when this place is
     *   already waiting with at least as strong a claim, or has been told.
     */
    fun offer(content: TourContent, priority: Int): Boolean {
        synchronized(lock) {
            if (content.poiId in delivered) return false

            val existing = queue.firstOrNull { it.content.poiId == content.poiId }
            if (existing != null) {
                if (priority <= existing.priority) return false

                // A stronger claim on the same place — an arrival after an
                // approach — takes its turn rather than queueing a second
                // telling behind the first
                queue.remove(existing)
            }
            return queue.offer(Entry(priority, nextSequence++, content))
        }
    }

    /**
     * Take the next content. Deliberately does *not* count as told: content
     * can be polled and then dropped for being behind the listener, and a
     * later pass should still be able to tell it. Callers report a telling
     * with [markDelivered].
     */
    fun poll(): TourContent? = synchronized(lock) {
        queue.poll()?.content
    }

    fun peek(): TourContent? = synchronized(lock) {
        queue.peek()?.content
    }

    /** This place's story has been told; don't queue it again this tour. */
    fun markDelivered(poiId: String) = synchronized(lock) {
        delivered.add(poiId)
        Unit
    }

    /** Ends the tour: nothing pending, and nothing told yet. */
    fun clear() = synchronized(lock) {
        queue.clear()
        delivered.clear()
    }

    fun size(): Int = synchronized(lock) { queue.size }
}
