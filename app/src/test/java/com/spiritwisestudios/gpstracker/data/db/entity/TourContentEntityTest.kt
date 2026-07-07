package com.spiritwisestudios.gpstracker.data.db.entity

import com.spiritwisestudios.gpstracker.domain.model.TourContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TourContentEntityTest {

    private fun content(metadata: Map<String, String>) = TourContent(
        id = "c1",
        poiId = "p1",
        title = "About Fort Point",
        content = "Fort Point is a fort.",
        summary = "Fort Point is a fort.",
        source = TourContent.ContentSource.THIRD_PARTY,
        metadata = metadata
    )

    @Test
    fun `wikipedia source url survives the cache round-trip`() {
        // The fact card's "read the full article" link depends on this
        val url = "https://en.wikipedia.org/wiki/Fort_Point"
        val entity = TourContentEntity.fromDomainModel(content(mapOf("sourceUrl" to url)))
        assertEquals(url, entity.toDomainModel().metadata["sourceUrl"])
    }

    @Test
    fun `content without a source carries no url out of the cache`() {
        val entity = TourContentEntity.fromDomainModel(content(emptyMap()))
        assertNull(entity.toDomainModel().metadata["sourceUrl"])
    }
}
