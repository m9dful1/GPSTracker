package com.spiritwisestudios.gpstracker.domain.service

import com.google.android.gms.maps.model.LatLng
import com.spiritwisestudios.gpstracker.domain.model.PointOfInterest
import com.spiritwisestudios.gpstracker.domain.model.UserPreferences
import kotlinx.coroutines.flow.Flow

/**
 * Service interface for discovering and managing places functionality.
 */
interface PlacesService {
    
    /**
     * Start monitoring for interesting places near the user's location.
     * 
     * @param userPreferences User preferences to filter places
     * @return Flow of detected points of interest
     */
    fun startPlaceMonitoring(userPreferences: UserPreferences): Flow<List<PointOfInterest>>
    
    /**
     * Stop monitoring for places.
     */
    fun stopPlaceMonitoring()
    
    /**
     * Search for places near a specific location.
     * 
     * @param location The location to search around
     * @param radius Radius in meters to search
     * @param categories Optional set of categories to filter by
     * @return List of points of interest
     */
    suspend fun searchNearbyPlaces(
        location: LatLng,
        radius: Int,
        categories: Set<PointOfInterest.Category>? = null
    ): List<PointOfInterest>
    
    /**
     * Set up geofences for relevant points of interest.
     * 
     * @param pointsOfInterest List of points to create geofences for
     * @param radius Radius in meters for the geofence
     * @return True if geofences were set up successfully
     */
    suspend fun setupGeofences(
        pointsOfInterest: List<PointOfInterest>,
        radius: Int = 100
    ): Boolean
    
    /**
     * Remove all active geofences.
     */
    fun removeAllGeofences()
    
    /**
     * Get details for a specific place.
     * 
     * @param placeId The ID of the place
     * @return Detailed point of interest or null if not found
     */
    suspend fun getPlaceDetails(placeId: String): PointOfInterest?
    
    /**
     * Plan a route with interesting places.
     * 
     * @param start Starting location
     * @param destination Destination location
     * @param preferences User preferences for place selection
     * @return List of waypoints including interesting places
     */
    suspend fun planRouteWithInterestingPlaces(
        start: LatLng,
        destination: LatLng,
        preferences: UserPreferences
    ): List<PointOfInterest>
} 