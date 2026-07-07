package com.spiritwisestudios.gpstracker.util

import com.spiritwisestudios.gpstracker.domain.model.PointOfInterest

/**
 * Formats the tour journal as shareable plain text. Date rendering is
 * injected so the formatter stays pure and locale decisions live with
 * the caller.
 */
object JournalFormatter {

    fun shareText(places: List<PointOfInterest>, formatDate: (Long) -> String): String {
        if (places.isEmpty()) {
            return "My tour journal is empty so far — no places discovered yet."
        }

        val header = when (places.size) {
            1 -> "My tour journal — 1 place discovered:"
            else -> "My tour journal — ${places.size} places discovered:"
        }

        val lines = places.map { poi ->
            val category = poi.category.lowercase().replaceFirstChar { it.uppercase() }
            val date = poi.visitedDate?.let { " — ${formatDate(it)}" } ?: ""
            "• ${poi.name} ($category)$date"
        }

        return buildString {
            appendLine(header)
            appendLine()
            lines.forEach { appendLine(it) }
            appendLine()
            append("Discovered with GPSTracker, my personal tour guide.")
        }
    }
}
