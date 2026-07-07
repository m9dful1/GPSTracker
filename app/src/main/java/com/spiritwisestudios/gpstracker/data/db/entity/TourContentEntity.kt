package com.spiritwisestudios.gpstracker.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.spiritwisestudios.gpstracker.domain.model.TourContent
import java.util.Date

/**
 * Cached narration content for a POI. Stores the full, untrimmed text; the
 * content service trims it to the user's detail level when serving.
 */
@Entity(tableName = "tour_content")
data class TourContentEntity(
    @PrimaryKey
    @ColumnInfo(name = "poi_id")
    val poiId: String,

    @ColumnInfo(name = "content_id")
    val contentId: String,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "content")
    val content: String,

    @ColumnInfo(name = "summary")
    val summary: String,

    @ColumnInfo(name = "source")
    val source: String,

    @ColumnInfo(name = "source_url")
    val sourceUrl: String?,

    @ColumnInfo(name = "language")
    val language: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long
) {
    fun toDomainModel(): TourContent {
        return TourContent(
            id = contentId,
            poiId = poiId,
            title = title,
            content = content,
            summary = summary,
            createdAt = Date(createdAt),
            updatedAt = Date(createdAt),
            source = try {
                TourContent.ContentSource.valueOf(source)
            } catch (e: IllegalArgumentException) {
                TourContent.ContentSource.THIRD_PARTY
            },
            metadata = sourceUrl?.let { mapOf("sourceUrl" to it) } ?: emptyMap(),
            language = language
        )
    }

    companion object {
        fun fromDomainModel(content: TourContent): TourContentEntity {
            return TourContentEntity(
                poiId = content.poiId,
                contentId = content.id,
                title = content.title,
                content = content.content,
                summary = content.summary,
                source = content.source.name,
                sourceUrl = content.metadata["sourceUrl"],
                language = content.language,
                createdAt = content.createdAt.time
            )
        }
    }
}
