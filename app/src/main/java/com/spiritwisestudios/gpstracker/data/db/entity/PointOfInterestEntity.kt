package com.spiritwisestudios.gpstracker.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.android.gms.maps.model.LatLng
import com.spiritwisestudios.gpstracker.domain.model.PointOfInterest

@Entity(tableName = "points_of_interest")
data class PointOfInterestEntity(
    @PrimaryKey
    val id: String,
    
    @ColumnInfo(name = "name")
    val name: String,
    
    @ColumnInfo(name = "lat_lng")
    val latLng: LatLng,
    
    @ColumnInfo(name = "address")
    val address: String,
    
    @ColumnInfo(name = "category")
    val category: String,
    
    @ColumnInfo(name = "rating")
    val rating: Double?,
    
    @ColumnInfo(name = "description")
    val description: String?,
    
    @ColumnInfo(name = "photo_url")
    val photoUrl: String?,
    
    @ColumnInfo(name = "place_id")
    val placeId: String?,
    
    @ColumnInfo(name = "is_visited")
    val isVisited: Boolean,
    
    @ColumnInfo(name = "user_notes")
    val userNotes: String?,
    
    @ColumnInfo(name = "visited_date")
    val visitedDate: Long? = null
) {
    /**
     * Convert entity to domain model
     */
    fun toDomainModel(): PointOfInterest {
        return PointOfInterest(
            id = id,
            name = name,
            latLng = latLng,
            address = address,
            category = category,
            rating = rating,
            description = description,
            photoUrl = photoUrl,
            placeId = placeId,
            isVisited = isVisited,
            userNotes = userNotes
        )
    }
    
    companion object {
        /**
         * Convert domain model to entity
         */
        fun fromDomainModel(domainModel: PointOfInterest, visitedDate: Long? = null): PointOfInterestEntity {
            return PointOfInterestEntity(
                id = domainModel.id,
                name = domainModel.name,
                latLng = domainModel.latLng,
                address = domainModel.address,
                category = domainModel.category,
                rating = domainModel.rating,
                description = domainModel.description,
                photoUrl = domainModel.photoUrl,
                placeId = domainModel.placeId,
                isVisited = domainModel.isVisited,
                userNotes = domainModel.userNotes,
                visitedDate = visitedDate ?: if (domainModel.isVisited) System.currentTimeMillis() else null
            )
        }
    }
} 