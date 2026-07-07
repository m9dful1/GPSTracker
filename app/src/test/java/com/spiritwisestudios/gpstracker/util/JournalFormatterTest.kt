package com.spiritwisestudios.gpstracker.util

import com.google.android.gms.maps.model.LatLng
import com.spiritwisestudios.gpstracker.domain.model.PointOfInterest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalFormatterTest {

    private fun poi(name: String, category: String = "HISTORICAL", visitedDate: Long? = 1000L) =
        PointOfInterest(
            id = name,
            name = name,
            latLng = LatLng(0.0, 0.0),
            address = "",
            category = category,
            isVisited = true,
            visitedDate = visitedDate
        )

    private val stamp: (Long) -> String = { millis -> "T$millis" }

    @Test
    fun `empty journal has a friendly message`() {
        val text = JournalFormatter.shareText(emptyList(), stamp)
        assertTrue(text.contains("empty"))
    }

    @Test
    fun `header counts places with singular and plural`() {
        assertTrue(JournalFormatter.shareText(listOf(poi("A")), stamp).contains("1 place discovered"))
        assertTrue(
            JournalFormatter.shareText(listOf(poi("A"), poi("B")), stamp)
                .contains("2 places discovered")
        )
    }

    @Test
    fun `entries keep their order and show name, category and date`() {
        val text = JournalFormatter.shareText(
            listOf(poi("Fort Point", "HISTORICAL", 1000L), poi("Ocean Park", "NATURAL", 2000L)),
            stamp
        )
        val fortIndex = text.indexOf("Fort Point")
        val parkIndex = text.indexOf("Ocean Park")
        assertTrue(fortIndex in 0 until parkIndex)
        assertTrue(text.contains("• Fort Point (Historical) — T1000"))
        assertTrue(text.contains("• Ocean Park (Natural) — T2000"))
    }

    @Test
    fun `missing visit date just omits the date part`() {
        val text = JournalFormatter.shareText(listOf(poi("Fort Point", visitedDate = null)), stamp)
        val entryLine = text.lines().first { it.startsWith("•") }
        assertEquals("• Fort Point (Historical)", entryLine)
        assertFalse(entryLine.contains("—"))
    }

    @Test
    fun `date formatting is delegated to the caller`() {
        var called = 0L
        JournalFormatter.shareText(listOf(poi("A", visitedDate = 42L))) { millis ->
            called = millis
            "whenever"
        }
        assertEquals(42L, called)
    }
}
