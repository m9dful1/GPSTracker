package com.spiritwisestudios.gpstracker.data.service

import com.spiritwisestudios.gpstracker.domain.model.TourContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContentDeliveryQueueTest {

    private fun content(title: String) = TourContent(
        id = title,
        poiId = "poi-$title",
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
}
