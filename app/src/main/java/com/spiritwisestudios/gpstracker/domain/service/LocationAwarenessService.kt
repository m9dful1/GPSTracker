package com.spiritwisestudios.gpstracker.domain.service

import com.google.android.gms.maps.model.LatLng
import com.spiritwisestudios.gpstracker.domain.model.PointOfInterest
import kotlinx.coroutines.flow.Flow

/**
 * Service interface for location awareness and proximity detection.
 */
interface LocationAwarenessService {
    
    /**
     * Start monitoring for points of interest near the current location.
     * 
     * @param detectionRadius Radius in meters to detect points of interest
     * @return Flow of nearby points of interest with proximity data
     */
    fun startProximityMonitoring(detectionRadius: Int = 100): Flow<ProximityAlert>
    
    /**
     * Stop monitoring for nearby points of interest.
     */
    fun stopProximityMonitoring()
    
    /**
     * Check if the monitoring service is currently active.
     * 
     * @return True if monitoring is active
     */
    fun isMonitoringActive(): Boolean
    
    /**
     * Register a point of interest for proximity monitoring.
     * 
     * @param pointOfInterest The point of interest to monitor
     * @param customRadius Optional custom detection radius for this POI
     * @return True if registration was successful
     */
    suspend fun registerPointOfInterest(
        pointOfInterest: PointOfInterest, 
        customRadius: Int? = null
    ): Boolean
    
    /**
     * Register multiple points of interest for proximity monitoring.
     * 
     * @param pointsOfInterest The points of interest to monitor
     * @param customRadius Optional custom detection radius for these POIs
     * @return Number of successfully registered points
     */
    suspend fun registerPointsOfInterest(
        pointsOfInterest: List<PointOfInterest>,
        customRadius: Int? = null
    ): Int
    
    /**
     * Unregister a point of interest from proximity monitoring.
     * 
     * @param pointOfInterestId The ID of the point of interest to unregister
     * @return True if unregistration was successful
     */
    suspend fun unregisterPointOfInterest(pointOfInterestId: String): Boolean
    
    /**
     * Unregister all points of interest from proximity monitoring.
     * 
     * @return True if unregistration was successful
     */
    suspend fun unregisterAllPointsOfInterest(): Boolean
    
    /**
     * Get the current location of the device.
     * 
     * @return The current location as LatLng or null if unavailable
     */
    suspend fun getCurrentLocation(): LatLng?
    
    /**
     * Get the current movement speed of the device.
     * 
     * @return The current speed in meters per second, or null if unavailable
     */
    fun getCurrentSpeed(): Float?

    /**
     * Get the device's current direction of travel.
     *
     * @return Bearing in degrees [0, 360) or null when unknown (e.g. stationary
     * or no GPS fix with a bearing yet)
     */
    fun getCurrentHeading(): Float?

    /**
     * Calculate the distance to a point of interest from the current location.
     * 
     * @param pointOfInterest The point of interest
     * @return Distance in meters or null if current location is unavailable
     */
    suspend fun getDistanceToPointOfInterest(pointOfInterest: PointOfInterest): Float?
    
    /**
     * Class representing a proximity alert for a point of interest.
     */
    data class ProximityAlert(
        val pointOfInterest: PointOfInterest,
        val distance: Float,
        val estimatedTimeToReach: Long? = null,
        val alertType: AlertType
    )
    
    /**
     * Enum representing the type of proximity alert.
     */
    enum class AlertType {
        APPROACHING, // User is moving toward the POI
        NEARBY,      // User is within the detection radius but not moving toward POI
        ARRIVED,     // User has arrived at the POI
        DEPARTING    // User is leaving the POI area
    }
} 