package com.spiritwisestudios.gpstracker.domain.service

import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.flow.Flow

/**
 * Service interface for navigation functionality.
 */
interface NavigationService {
    
    /**
     * Start navigation to a destination.
     * 
     * @param destination Destination coordinates
     * @param waypoints Optional waypoints to include in the route
     * @return Flow emitting navigation status updates
     */
    fun startNavigation(
        destination: LatLng,
        waypoints: List<LatLng> = emptyList()
    ): Flow<NavigationStatus>
    
    /**
     * Get the current route.
     * 
     * @return List of coordinates representing the current route
     */
    suspend fun getCurrentRoute(): List<LatLng>
    
    /**
     * Calculate estimated time of arrival.
     * 
     * @return Estimated time of arrival in milliseconds since epoch
     */
    suspend fun getEstimatedTimeOfArrival(): Long
    
    /**
     * Get remaining distance to destination.
     * 
     * @return Distance in meters
     */
    fun getRemainingDistance(): Float
    
    /**
     * Get next navigation instruction.
     * 
     * @return Next navigation instruction or null if none available
     */
    suspend fun getNextInstruction(): NavigationInstruction?
    
    /**
     * Get detailed information for a maneuver.
     * 
     * @param instruction The instruction to get details for
     * @return ManeuverDetails containing visual and audio presentation info
     */
    fun getManeuverDetails(instruction: NavigationInstruction): ManeuverDetails
    
    /**
     * Get the timing category for when to announce an instruction.
     * 
     * @param distanceToManeuver Distance to the maneuver point in meters
     * @return AnnouncementTiming indicating when the instruction should be announced
     */
    fun determineAnnouncementTiming(distanceToManeuver: Float): AnnouncementTiming
    
    /**
     * Stop the active navigation.
     */
    fun stopNavigation()
    
    /**
     * Check if navigation is currently active.
     * 
     * @return True if navigation is active
     */
    fun isNavigating(): Boolean
    
    /**
     * Geocode an address to coordinates.
     *
     * @param address The address string to geocode
     * @return The coordinates or null if geocoding failed
     */
    suspend fun geocodeAddress(address: String): LatLng?
    
    /**
     * Navigation status data class.
     */
    data class NavigationStatus(
        val isActive: Boolean,
        val currentLocation: LatLng,
        val distanceRemaining: Float,
        val timeRemaining: Long,
        val nextInstruction: NavigationInstruction?,
        val announcementTiming: AnnouncementTiming = AnnouncementTiming.NONE,
        /**
         * Increments every time a route is (re)calculated, so consumers can
         * react to reroutes: redraw the polyline, re-register the tour
         * corridor, etc.
         */
        val routeVersion: Int = 0
    )
    
    /**
     * Navigation instruction data class.
     */
    data class NavigationInstruction(
        val type: InstructionType,
        val distance: Float,
        val description: String,
        val maneuverPoint: LatLng
    )
    
    /**
     * Detailed information for presenting a maneuver.
     */
    data class ManeuverDetails(
        val visualIcon: String,
        val visualColor: Int,
        val soundCue: String,
        val primaryInstruction: String,
        val secondaryInstruction: String
    )
    
    /**
     * Timing categories for instruction announcements.
     */
    enum class AnnouncementTiming {
        NONE,        // Don't announce yet
        ADVANCE,     // Early announcement (e.g., "In 500 meters...")
        APPROACHING, // Getting close (e.g., "In 200 meters...")
        IMMEDIATE,   // Immediate action (e.g., "Turn right now")
        PASSED       // Just passed the maneuver point
    }
    
    /**
     * Navigation instruction types.
     */
    enum class InstructionType {
        STRAIGHT,
        TURN_LEFT,
        TURN_RIGHT,
        TURN_SLIGHT_LEFT,
        TURN_SLIGHT_RIGHT,
        TURN_SHARP_LEFT,
        TURN_SHARP_RIGHT,
        ROUNDABOUT,
        MERGE,
        HIGHWAY_EXIT,
        ARRIVE,
        DEPART,
        OTHER
    }
} 