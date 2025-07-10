package com.spiritwisestudios.gpstracker.domain.model

import com.google.android.gms.maps.model.LatLng
import java.util.UUID

/**
 * Model class representing a Point of Interest in the city tour guide
 */
data class PointOfInterest(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val latLng: LatLng,
    val address: String,
    val category: String,
    val rating: Double? = null,
    val description: String? = null,
    val photoUrl: String? = null,
    val placeId: String? = null,
    val isVisited: Boolean = false,
    val userNotes: String? = null
) {
    /**
     * Categories for points of interest
     */
    enum class Category {
        HISTORICAL,
        CULTURAL,
        NATURAL,
        ARCHITECTURAL,
        ENTERTAINMENT,
        DINING,
        SHOPPING,
        OTHER
    }
} 