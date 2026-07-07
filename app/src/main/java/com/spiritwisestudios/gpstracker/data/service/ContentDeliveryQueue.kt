package com.spiritwisestudios.gpstracker.data.service

import com.spiritwisestudios.gpstracker.domain.model.TourContent
import java.util.PriorityQueue

/**
 * Thread-safe priority queue for narration delivery. Higher priority is served
 * first; equal priorities are served FIFO.
 */
class ContentDeliveryQueue {

    private data class Entry(val priority: Int, val sequence: Long, val content: TourContent)

    private val lock = Any()
    private var nextSequence = 0L
    private val queue = PriorityQueue(
        compareByDescending<Entry> { it.priority }.thenBy { it.sequence }
    )

    fun offer(content: TourContent, priority: Int): Boolean = synchronized(lock) {
        queue.offer(Entry(priority, nextSequence++, content))
    }

    fun poll(): TourContent? = synchronized(lock) {
        queue.poll()?.content
    }

    fun peek(): TourContent? = synchronized(lock) {
        queue.peek()?.content
    }

    fun clear() = synchronized(lock) {
        queue.clear()
    }

    fun size(): Int = synchronized(lock) { queue.size }
}
