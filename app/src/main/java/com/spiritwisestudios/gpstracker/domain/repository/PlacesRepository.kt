package com.spiritwisestudios.gpstracker.domain.repository

import com.google.android.gms.maps.model.LatLng
import com.spiritwisestudios.gpstracker.domain.model.PointOfInterest
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for accessing points of interest data.
 */
interface PlacesRepository {
    
    /**
     * Get points of interest around a location.
     *
     * @param center Location to search around
     * @param radius Search radius in meters
     * @return Flow of points of interest
     */
    fun getNearbyPlaces(center: LatLng, radius: Int = 500): Flow<List<PointOfInterest>>

    /**
     * Get points of interest along a route corridor, in route order.
     * Searches around sampled points along the polyline and de-duplicates.
     *
     * @param route The route polyline
     * @param searchRadius Radius in meters around each sampled route point
     * @return Points of interest along the route, ordered by route progress
     */
    suspend fun getPlacesAlongRoute(route: List<LatLng>, searchRadius: Int = 500): List<PointOfInterest>

    /**
     * Get detailed information about a specific place
     * 
     * @param placeId Place identifier
     * @return Result containing the point of interest or an error
     */
    suspend fun getPlaceDetails(placeId: String): Result<PointOfInterest>
    
    /**
     * Save a visited point of interest to the database
     * 
     * @param pointOfInterest The point of interest to save
     * @return Result indicating success or failure
     */
    suspend fun saveVisitedPlace(pointOfInterest: PointOfInterest): Result<Unit>
    
    /**
     * Get all visited places from database
     * 
     * @return Flow of points of interest that have been visited
     */
    fun getVisitedPlaces(): Flow<List<PointOfInterest>>
} 