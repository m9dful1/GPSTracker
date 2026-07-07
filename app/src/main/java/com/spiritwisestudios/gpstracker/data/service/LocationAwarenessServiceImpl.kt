package com.spiritwisestudios.gpstracker.data.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.os.BatteryManager
import android.os.Looper
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.model.LatLng
import com.spiritwisestudios.gpstracker.domain.model.PointOfInterest
import com.spiritwisestudios.gpstracker.domain.service.LocationAwarenessService
import com.spiritwisestudios.gpstracker.receiver.GeofenceBroadcastReceiver
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Implementation of LocationAwarenessService that uses FusedLocationProvider and Geofencing.
 */
class LocationAwarenessServiceImpl @Inject constructor(
    private val context: Context
) : LocationAwarenessService {
    
    private val fusedLocationClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)
    private val geofencingClient: GeofencingClient = LocationServices.getGeofencingClient(context)
    
    private val monitoredPointsOfInterest = ConcurrentHashMap<String, PointOfInterest>()
    private val customRadii = ConcurrentHashMap<String, Int>()
    
    private var locationCallback: LocationCallback? = null
    private var isMonitoring = false
    
    private val proximityAlerts = MutableStateFlow<LocationAwarenessService.ProximityAlert?>(null)
    
    // Store previous location to calculate direction and speed
    private var previousLocation: Location? = null
    private var previousLocationTimestamp: Long = 0
    private var currentSpeed = 0f // meters per second
    
    // Constants for location update intervals (in milliseconds)
    companion object {
        // Base intervals
        private const val INTERVAL_HIGH_POWER = 5000L // 5 seconds
        private const val INTERVAL_BALANCED = 10000L // 10 seconds
        private const val INTERVAL_LOW_POWER = 30000L // 30 seconds
        
        // Battery thresholds
        private const val BATTERY_LOW_THRESHOLD = 15
        private const val BATTERY_MEDIUM_THRESHOLD = 50
        
        // Speed thresholds (in meters per second)
        private const val SPEED_STATIONARY = 0.5f // < 1.8 km/h
        private const val SPEED_WALKING = 2.0f // ~ 7.2 km/h
        private const val SPEED_DRIVING_SLOW = 8.0f // ~ 29 km/h
        private const val SPEED_DRIVING_FAST = 20.0f // ~ 72 km/h
    }
    
    // Battery monitoring
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val batteryPct = level * 100 / scale.toFloat()
            
            // Update location intervals if monitoring is active
            if (isMonitoring) {
                updateLocationRequestSettings(batteryPct.toInt())
            }
        }
    }
    
    /**
     * Start monitoring for points of interest near the current location.
     */
    @SuppressLint("MissingPermission")
    override fun startProximityMonitoring(detectionRadius: Int): Flow<LocationAwarenessService.ProximityAlert> = callbackFlow {
        if (isMonitoring) {
            stopProximityMonitoring()
        }
        
        // Register battery receiver
        context.registerReceiver(
            batteryReceiver, 
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        
        // Get current battery level
        val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level != -1 && scale != -1) level * 100 / scale.toFloat() else 100f
        
        // Create initial location request based on battery level
        val locationRequest = createLocationRequest(batteryPct.toInt())
        
        // Create location callback
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    // Calculate speed
                    updateSpeed(location)
                    
                    // Process the new location
                    processNewLocation(location, detectionRadius)
                    
                    // Check if we need to update location request based on speed
                    updateLocationRequestBasedOnSpeed()
                    
                    // Check if new alert is available
                    proximityAlerts.value?.let { alert ->
                        trySend(alert)
                    }
                }
            }
        }
        
        try {
            // Start location updates
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            )
            
            isMonitoring = true
            
            // Set up geofences for all registered POIs
            setupGeofences(monitoredPointsOfInterest.values.toList(), detectionRadius)
            
        } catch (e: SecurityException) {
            Timber.e(e, "Missing location permission")
            close(e)
        } catch (e: Exception) {
            Timber.e(e, "Error starting proximity monitoring")
            close(e)
        }
        
        awaitClose {
            stopProximityMonitoring()
        }
    }
    
    /**
     * Create a location request based on current battery level.
     */
    private fun createLocationRequest(batteryLevel: Int): LocationRequest {
        val interval = when {
            batteryLevel <= BATTERY_LOW_THRESHOLD -> INTERVAL_LOW_POWER
            batteryLevel <= BATTERY_MEDIUM_THRESHOLD -> INTERVAL_BALANCED
            else -> INTERVAL_HIGH_POWER
        }
        
        Timber.d("Creating location request with interval $interval ms (battery level: $batteryLevel%)")
        
        return LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, interval)
            .setWaitForAccurateLocation(false)
            .setMinUpdateIntervalMillis(interval / 2)
            .setMaxUpdateDelayMillis(interval * 2)
            .build()
    }
    
    /**
     * Update speed calculation based on new location.
     */
    private fun updateSpeed(location: Location) {
        val currentTime = System.currentTimeMillis()
        
        previousLocation?.let { prev ->
            val distanceInMeters = calculateDistance(
                prev.latitude, prev.longitude,
                location.latitude, location.longitude
            )
            val timeInSeconds = (currentTime - previousLocationTimestamp) / 1000f
            if (timeInSeconds > 0) {
                currentSpeed = distanceInMeters / timeInSeconds // meters per second
                Timber.d("Current speed: $currentSpeed m/s (${currentSpeed * 3.6} km/h)")
            }
        }
        
        // Store current location for next calculation
        previousLocation = location
        previousLocationTimestamp = currentTime
    }
    
    /**
     * Update location request settings based on current battery level.
     */
    @SuppressLint("MissingPermission")
    private fun updateLocationRequestSettings(batteryLevel: Int) {
        locationCallback?.let {
            val locationRequest = createLocationRequest(batteryLevel)
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                it,
                Looper.getMainLooper()
            )
            Timber.d("Updated location request based on battery level: $batteryLevel%")
        }
    }
    
    /**
     * Update location request based on current speed.
     */
    @SuppressLint("MissingPermission")
    private fun updateLocationRequestBasedOnSpeed() {
        locationCallback?.let {
            // Determine appropriate interval based on speed
            val interval = when {
                currentSpeed < SPEED_STATIONARY -> INTERVAL_LOW_POWER
                currentSpeed < SPEED_WALKING -> INTERVAL_BALANCED
                currentSpeed < SPEED_DRIVING_SLOW -> INTERVAL_HIGH_POWER
                else -> INTERVAL_HIGH_POWER / 2 // Even faster updates for high speed
            }
            
            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, interval)
                .setWaitForAccurateLocation(false)
                .setMinUpdateIntervalMillis(interval / 2)
                .setMaxUpdateDelayMillis(interval * 2)
                .build()
                
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                it,
                Looper.getMainLooper()
            )
            
            Timber.d("Updated location request based on speed: $currentSpeed m/s, interval: $interval ms")
        }
    }
    
    /**
     * Process a new location update and generate proximity alerts if needed.
     */
    private fun processNewLocation(location: Location, defaultRadius: Int) {
        val currentLatLng = LatLng(location.latitude, location.longitude)
        val currentTime = System.currentTimeMillis()
        
        // Calculate speed and direction if we have a previous location
        val speed = previousLocation?.let { prev ->
            val distanceInMeters = calculateDistance(
                prev.latitude, prev.longitude,
                location.latitude, location.longitude
            )
            val timeInSeconds = (currentTime - previousLocationTimestamp) / 1000f
            distanceInMeters / timeInSeconds // meters per second
        } ?: 0f
        
        val bearing = previousLocation?.let { prev ->
            calculateBearing(
                prev.latitude, prev.longitude,
                location.latitude, location.longitude
            )
        } ?: 0f
        
        // Store current location for next calculation
        previousLocation = location
        previousLocationTimestamp = currentTime
        
        // Check all monitored POIs
        for ((poiId, poi) in monitoredPointsOfInterest) {
            val radius = customRadii[poiId] ?: defaultRadius
            
            // Calculate distance to POI
            val distance = calculateDistance(
                location.latitude, location.longitude,
                poi.latLng.latitude, poi.latLng.longitude
            )
            
            // Calculate bearing to POI
            val bearingToPoi = calculateBearing(
                location.latitude, location.longitude,
                poi.latLng.latitude, poi.latLng.longitude
            )
            
            // Determine if user is moving toward POI (within 45 degrees of direct path)
            val isMovingToward = angleDifference(bearing, bearingToPoi) <= 45
            
            // Generate alert based on distance and movement
            val alertType = when {
                distance <= 20 -> LocationAwarenessService.AlertType.ARRIVED
                distance <= radius && isMovingToward -> LocationAwarenessService.AlertType.APPROACHING
                distance <= radius -> LocationAwarenessService.AlertType.NEARBY
                distance <= radius * 1.2 && !isMovingToward -> LocationAwarenessService.AlertType.DEPARTING
                else -> null // No alert needed
            }
            
            // If an alert is needed, create and emit it
            alertType?.let {
                // Calculate estimated time to reach if approaching
                val estimatedTimeToReach = if (it == LocationAwarenessService.AlertType.APPROACHING && speed > 0) {
                    (distance / speed * 1000).toLong() // milliseconds
                } else {
                    null
                }
                
                val alert = LocationAwarenessService.ProximityAlert(
                    pointOfInterest = poi,
                    distance = distance,
                    estimatedTimeToReach = estimatedTimeToReach,
                    alertType = it
                )
                
                proximityAlerts.value = alert
            }
        }
    }
    
    /**
     * Stop monitoring for nearby points of interest.
     */
    override fun stopProximityMonitoring() {
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
            locationCallback = null
        }
        
        try {
            context.unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {
            Timber.e(e, "Error unregistering battery receiver")
        }
        
        isMonitoring = false
        previousLocation = null
        previousLocationTimestamp = 0
        currentSpeed = 0f
    }
    
    /**
     * Check if the monitoring service is currently active.
     */
    override fun isMonitoringActive(): Boolean {
        return isMonitoring
    }
    
    /**
     * Register a point of interest for proximity monitoring.
     */
    override suspend fun registerPointOfInterest(
        pointOfInterest: PointOfInterest,
        customRadius: Int?
    ): Boolean {
        monitoredPointsOfInterest[pointOfInterest.id] = pointOfInterest
        
        if (customRadius != null) {
            customRadii[pointOfInterest.id] = customRadius
        }
        
        // If monitoring is active, update geofences
        if (isMonitoring) {
            return try {
                addGeofence(pointOfInterest, customRadius ?: 100)
                true
            } catch (e: Exception) {
                Timber.e(e, "Failed to register geofence for POI: ${pointOfInterest.id}")
                false
            }
        }
        
        return true
    }
    
    /**
     * Register multiple points of interest for proximity monitoring.
     */
    override suspend fun registerPointsOfInterest(
        pointsOfInterest: List<PointOfInterest>,
        customRadius: Int?
    ): Int {
        var successCount = 0
        
        for (poi in pointsOfInterest) {
            if (registerPointOfInterest(poi, customRadius)) {
                successCount++
            }
        }
        
        return successCount
    }
    
    /**
     * Unregister a point of interest from proximity monitoring.
     */
    override suspend fun unregisterPointOfInterest(pointOfInterestId: String): Boolean {
        monitoredPointsOfInterest.remove(pointOfInterestId)
        customRadii.remove(pointOfInterestId)
        
        // If monitoring is active, remove geofence
        if (isMonitoring) {
            return try {
                removeGeofence(pointOfInterestId)
                true
            } catch (e: Exception) {
                Timber.e(e, "Failed to unregister geofence for POI: $pointOfInterestId")
                false
            }
        }
        
        return true
    }
    
    /**
     * Unregister all points of interest from proximity monitoring.
     */
    override suspend fun unregisterAllPointsOfInterest(): Boolean {
        monitoredPointsOfInterest.clear()
        customRadii.clear()
        
        // If monitoring is active, remove all geofences
        if (isMonitoring) {
            return try {
                removeAllGeofences()
                true
            } catch (e: Exception) {
                Timber.e(e, "Failed to unregister all geofences")
                false
            }
        }
        
        return true
    }
    
    /**
     * Get the current location of the device.
     */
    @SuppressLint("MissingPermission")
    override suspend fun getCurrentLocation(): LatLng? = suspendCancellableCoroutine { continuation ->
        try {
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location ->
                    if (location != null) {
                        continuation.resume(LatLng(location.latitude, location.longitude))
                    } else {
                        continuation.resume(null)
                    }
                }
                .addOnFailureListener { e ->
                    Timber.e(e, "Error getting current location")
                    continuation.resume(null)
                }
        } catch (e: SecurityException) {
            Timber.e(e, "Missing location permission")
            continuation.resumeWithException(e)
        }
    }
    
    /**
     * Calculate the distance to a point of interest from the current location.
     */
    override suspend fun getDistanceToPointOfInterest(pointOfInterest: PointOfInterest): Float? {
        val currentLocation = getCurrentLocation() ?: return null
        
        return calculateDistance(
            currentLocation.latitude, currentLocation.longitude,
            pointOfInterest.latLng.latitude, pointOfInterest.latLng.longitude
        )
    }
    
    /**
     * Set up geofences for a list of points of interest.
     */
    @SuppressLint("MissingPermission")
    private suspend fun setupGeofences(pointsOfInterest: List<PointOfInterest>, defaultRadius: Int): Boolean {
        // Remove existing geofences first
        removeAllGeofences()
        
        val geofenceList = pointsOfInterest.map { poi ->
            Geofence.Builder()
                .setRequestId(poi.id)
                .setCircularRegion(
                    poi.latLng.latitude,
                    poi.latLng.longitude,
                    (customRadii[poi.id] ?: defaultRadius).toFloat()
                )
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(
                    Geofence.GEOFENCE_TRANSITION_ENTER or
                    Geofence.GEOFENCE_TRANSITION_EXIT or
                    Geofence.GEOFENCE_TRANSITION_DWELL
                )
                .setLoiteringDelay(30000) // 30 seconds for DWELL transition
                .build()
        }
        
        if (geofenceList.isEmpty()) {
            return true
        }
        
        val geofencingRequest = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofences(geofenceList)
            .build()
        
        val geofencePendingIntent = getGeofencePendingIntent()
        
        return suspendCancellableCoroutine { continuation ->
            try {
                geofencingClient.addGeofences(geofencingRequest, geofencePendingIntent)
                    .addOnSuccessListener {
                        Timber.d("Geofences added successfully")
                        continuation.resume(true)
                    }
                    .addOnFailureListener { e ->
                        Timber.e(e, "Failed to add geofences")
                        continuation.resume(false)
                    }
            } catch (e: SecurityException) {
                Timber.e(e, "Missing location permission")
                continuation.resume(false)
            }
        }
    }
    
    /**
     * Add a single geofence for a point of interest.
     */
    @SuppressLint("MissingPermission")
    private suspend fun addGeofence(pointOfInterest: PointOfInterest, radius: Int): Boolean {
        val geofence = Geofence.Builder()
            .setRequestId(pointOfInterest.id)
            .setCircularRegion(
                pointOfInterest.latLng.latitude,
                pointOfInterest.latLng.longitude,
                radius.toFloat()
            )
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(
                Geofence.GEOFENCE_TRANSITION_ENTER or
                Geofence.GEOFENCE_TRANSITION_EXIT or
                Geofence.GEOFENCE_TRANSITION_DWELL
            )
            .setLoiteringDelay(30000) // 30 seconds for DWELL transition
            .build()
        
        val geofencingRequest = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofence(geofence)
            .build()
        
        val geofencePendingIntent = getGeofencePendingIntent()
        
        return suspendCancellableCoroutine { continuation ->
            try {
                geofencingClient.addGeofences(geofencingRequest, geofencePendingIntent)
                    .addOnSuccessListener {
                        Timber.d("Geofence added successfully")
                        continuation.resume(true)
                    }
                    .addOnFailureListener { e ->
                        Timber.e(e, "Failed to add geofence")
                        continuation.resume(false)
                    }
            } catch (e: SecurityException) {
                Timber.e(e, "Missing location permission")
                continuation.resume(false)
            }
        }
    }
    
    /**
     * Remove a specific geofence by ID.
     */
    private suspend fun removeGeofence(geofenceId: String): Boolean {
        return suspendCancellableCoroutine { continuation ->
            geofencingClient.removeGeofences(listOf(geofenceId))
                .addOnSuccessListener {
                    Timber.d("Geofence removed successfully")
                    continuation.resume(true)
                }
                .addOnFailureListener { e ->
                    Timber.e(e, "Failed to remove geofence")
                    continuation.resume(false)
                }
        }
    }
    
    /**
     * Remove all geofences.
     */
    private suspend fun removeAllGeofences(): Boolean {
        return suspendCancellableCoroutine { continuation ->
            geofencingClient.removeGeofences(getGeofencePendingIntent())
                .addOnSuccessListener {
                    Timber.d("All geofences removed successfully")
                    continuation.resume(true)
                }
                .addOnFailureListener { e ->
                    Timber.e(e, "Failed to remove all geofences")
                    continuation.resume(false)
                }
        }
    }
    
    /**
     * Get a PendingIntent to use with geofencing.
     */
    private fun getGeofencePendingIntent(): PendingIntent {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }
    
    /**
     * Calculate distance between two points using the Haversine formula.
     */
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val earthRadius = 6371000.0 // meters
        
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        
        return (earthRadius * c).toFloat()
    }
    
    /**
     * Calculate bearing between two points.
     */
    private fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val dLon = Math.toRadians(lon2 - lon1)
        
        val y = sin(dLon) * cos(Math.toRadians(lat2))
        val x = cos(Math.toRadians(lat1)) * sin(Math.toRadians(lat2)) -
                sin(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * cos(dLon)
        
        val bearing = Math.toDegrees(atan2(y, x))
        
        return ((bearing + 360) % 360).toFloat()
    }
    
    /**
     * Calculate the absolute angle difference between two bearings.
     */
    private fun angleDifference(angle1: Float, angle2: Float): Float {
        var diff = (angle2 - angle1 + 360) % 360
        if (diff > 180) diff = 360 - diff
        return diff
    }
    
    /**
     * Get the current movement speed of the device.
     */
    override fun getCurrentSpeed(): Float? {
        // If we haven't calculated a speed yet or we're not monitoring, return null
        if (currentSpeed == 0f && previousLocation == null) {
            return null
        }
        return currentSpeed
    }

    /**
     * Get the device's current direction of travel from the latest GPS fix.
     * The fix only carries a bearing while actually moving.
     */
    override fun getCurrentHeading(): Float? {
        return previousLocation?.takeIf { it.hasBearing() }?.bearing
    }
}