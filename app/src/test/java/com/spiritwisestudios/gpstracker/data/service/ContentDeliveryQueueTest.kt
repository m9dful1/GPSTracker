package com.spiritwisestudios.gpstracker.data.service

import com.spiritwisestudios.gpstracker.domain.model.TourContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentDeliveryQueueTest {

    private fun content(title: String, poiId: String = "poi-$title") = TourContent(
        id = title,
        poiId = poiId,
        title = title,
        content = "content of $title",
        summary = "summary"
    )

    @Test
    fun `higher priority is served first`() {
        val queue = ContentDeliveryQueue()
        queue.offer(content("low"), priority = 1)
        queue.offer(content("high"), priority = 5)
        queue.offer(content("medium"), priority = 3)

        assertEquals("high", queue.poll()?.title)
        assertEquals("medium", queue.poll()?.title)
        assertEquals("low", queue.poll()?.title)
    }

    @Test
    fun `equal priorities are served in insertion order`() {
        val queue = ContentDeliveryQueue()
        queue.offer(content("first"), priority = 2)
        queue.offer(content("second"), priority = 2)
        queue.offer(content("third"), priority = 2)

        assertEquals("first", queue.poll()?.title)
        assertEquals("second", queue.poll()?.title)
        assertEquals("third", queue.poll()?.title)
    }

    @Test
    fun `poll on empty queue returns null`() {
        assertNull(ContentDeliveryQueue().poll())
    }

    @Test
    fun `clear empties the queue`() {
        val queue = ContentDeliveryQueue()
        queue.offer(content("a"), priority = 1)
        queue.clear()

        assertEquals(0, queue.size())
        assertNull(queue.poll())
    }

    @Test
    fun `peek shows the highest priority entry without removing it`() {
        val queue = ContentDeliveryQueue()
        queue.offer(content("low"), priority = 1)
        queue.offer(content("high"), priority = 5)

        assertEquals("high", queue.peek()?.title)
        assertEquals(2, queue.size())
        assertEquals("high", queue.poll()?.title)
    }

    @Test
    fun `peek on empty queue returns null`() {
        assertNull(ContentDeliveryQueue().peek())
    }

    // --- one place, one telling ---

    @Test
    fun `a place already waiting is not queued twice`() {
        // The geofence enter and the dwell 30 seconds later both queue the
        // same place; the listener should hear about it once.
        val queue = ContentDeliveryQueue()

        assertTrue(queue.offer(content("enter", poiId = "mill"), priority = 0))
        assertFalse(queue.offer(content("dwell", poiId = "mill"), priority = 0))
        assertEquals(1, queue.size())
    }

    @Test
    fun `a stronger claim on a waiting place replaces it`() {
        // Arriving outranks approaching: the story should move up the queue
        // rather than be told a second time behind the first.
        val queue = ContentDeliveryQueue()
        queue.offer(content("other"), priority = 3)
        queue.offer(content("approaching", poiId = "mill"), priority = 1)

        assertTrue(queue.offer(content("arrived", poiId = "mill"), priority = 5))
        assertEquals(2, queue.size())
        assertEquals("arrived", queue.poll()?.title)
        assertEquals("other", queue.poll()?.title)
    }

    @Test
    fun `a weaker claim on a waiting place is dropped`() {
        val queue = ContentDeliveryQueue()
        queue.offer(content("arrived", poiId = "mill"), priority = 5)

        assertFalse(queue.offer(content("approaching", poiId = "mill"), priority = 1))
        assertEquals(1, queue.size())
        assertEquals("arrived", queue.poll()?.title)
    }

    @Test
    fun `a told place is not queued again`() {
        // The re-alert case: the guide moves on, the place alerts again, and
        // the story would be told a second time.
        val queue = ContentDeliveryQueue()
        queue.offer(content("first telling", poiId = "mill"), priority = 0)
        queue.poll()
        queue.markDelivered("mill")

        assertFalse(queue.offer(content("again", poiId = "mill"), priority = 5))
        assertEquals(0, queue.size())
    }

    @Test
    fun `content dropped without being told can be queued again`() {
        // Polled and then discarded for being behind the listener: that place
        // was never actually narrated, so a later pass may still tell it.
        val queue = ContentDeliveryQueue()
        queue.offer(content("overtaken", poiId = "mill"), priority = 0)
        queue.poll()

        assertTrue(queue.offer(content("second chance", poiId = "mill"), priority = 0))
    }

    @Test
    fun `other places are unaffected by one being told`() {
        val queue = ContentDeliveryQueue()
        queue.markDelivered("mill")

        assertTrue(queue.offer(content("bridge"), priority = 0))
    }

    @Test
    fun `the next tour can tell the same places again`() {
        val queue = ContentDeliveryQueue()
        queue.offer(content("first telling", poiId = "mill"), priority = 0)
        queue.poll()
        queue.markDelivered("mill")

        queue.clear()

        assertTrue(queue.offer(content("new tour", poiId = "mill"), priority = 0))
    }
}
