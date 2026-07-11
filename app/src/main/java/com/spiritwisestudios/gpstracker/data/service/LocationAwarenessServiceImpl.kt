package com.spiritwisestudios.gpstracker.data.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import com.spiritwisestudios.gpstracker.domain.model.LatLng
import com.spiritwisestudios.gpstracker.domain.model.PointOfInterest
import com.spiritwisestudios.gpstracker.domain.service.LocationAwarenessService
import com.spiritwisestudios.gpstracker.service.TourModeService
import com.spiritwisestudios.gpstracker.util.AppConstants
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Implementation of LocationAwarenessService on the framework
 * [android.location.LocationManager] — no Play Services. Geofence
 * transitions (enter/dwell/exit) are derived from the same per-fix distance
 * checks that drive proximity alerts, and forwarded to [TourModeService]
 * exactly like the old GeofencingClient broadcast path did.
 */
class LocationAwarenessServiceImpl @Inject constructor(
    private val context: Context
) : LocationAwarenessService {

    private val locationClient = FrameworkLocationClient(context)

    private val monitoredPointsOfInterest = ConcurrentHashMap<String, PointOfInterest>()
    private val customRadii = ConcurrentHashMap<String, Int>()

    // Manual geofencing state: when each POI's radius was entered, and
    // which POIs already got their one dwell notification
    private val geofenceEntryTimes = ConcurrentHashMap<String, Long>()
    private val dwellNotified = ConcurrentHashMap.newKeySet<String>()

    private var locationListener: LocationListenerCompat? = null
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

        // Linger this long inside a radius before the "dwell" notification
        private const val DWELL_DELAY_MS = 30_000L
    }

    // Battery monitoring
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val batteryPct = level * 100 / scale.toFloat()

            // Update location intervals if monitoring is active
            if (isMonitoring) {
                requestUpdatesWithInterval(intervalForBattery(batteryPct.toInt()))
            }
        }
    }

    /**
     * Start monitoring for points of interest near the current location.
     */
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

        // Location listener drives proximity alerts and manual geofencing
        locationListener = LocationListenerCompat { location ->
            // Calculate speed
            updateSpeed(location)

            // Process the new location (alerts + geofence transitions)
            processNewLocation(location, detectionRadius)

            // Check if we need to update location request based on speed
            updateLocationRequestBasedOnSpeed()

            // Check if new alert is available
            proximityAlerts.value?.let { alert ->
                trySend(alert)
            }
        }

        try {
            // Start location updates at a battery-appropriate interval
            requestUpdatesWithInterval(intervalForBattery(batteryPct.toInt()))
            isMonitoring = true
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

    /** Update interval appropriate for the battery level. */
    private fun intervalForBattery(batteryLevel: Int): Long {
        return when {
            batteryLevel <= BATTERY_LOW_THRESHOLD -> INTERVAL_LOW_POWER
            batteryLevel <= BATTERY_MEDIUM_THRESHOLD -> INTERVAL_BALANCED
            else -> INTERVAL_HIGH_POWER
        }
    }

    /** (Re-)register the active listener at a new update interval. */
    private fun requestUpdatesWithInterval(intervalMs: Long) {
        locationListener?.let {
            locationClient.requestUpdates(intervalMs, 0f, it)
            Timber.d("Location updates requested with interval $intervalMs ms")
        }
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
     * Update location request based on current speed.
     */
    private fun updateLocationRequestBasedOnSpeed() {
        // Determine appropriate interval based on speed
        val interval = when {
            currentSpeed < SPEED_STATIONARY -> INTERVAL_LOW_POWER
            currentSpeed < SPEED_WALKING -> INTERVAL_BALANCED
            currentSpeed < SPEED_DRIVING_SLOW -> INTERVAL_HIGH_POWER
            else -> INTERVAL_HIGH_POWER / 2 // Even faster updates for high speed
        }
        requestUpdatesWithInterval(interval)
    }

    /**
     * Process a new location update: generate proximity alerts and derive
     * geofence enter/dwell/exit transitions for the monitored POIs.
     */
    private fun processNewLocation(location: Location, defaultRadius: Int) {
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

            // Manual geofencing: enter/dwell/exit from the same distance check
            updateGeofenceState(poiId, distance <= radius, currentTime)

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
     * Track one POI's in/out state and notify the tour service on
     * transitions, mirroring the ENTER / DWELL / EXIT events the Play
     * Services GeofencingClient used to deliver.
     */
    private fun updateGeofenceState(poiId: String, isInside: Boolean, now: Long) {
        val enteredAt = geofenceEntryTimes[poiId]

        when {
            isInside && enteredAt == null -> {
                geofenceEntryTimes[poiId] = now
                notifyGeofenceTransition("enter", poiId)
            }
            isInside && enteredAt != null &&
                now - enteredAt >= DWELL_DELAY_MS && dwellNotified.add(poiId) -> {
                notifyGeofenceTransition("dwell", poiId)
            }
            !isInside && enteredAt != null -> {
                geofenceEntryTimes.remove(poiId)
                dwellNotified.remove(poiId)
                notifyGeofenceTransition("exit", poiId)
            }
        }
    }

    /**
     * Forward a transition to the tour service — the same intent the old
     * GeofenceBroadcastReceiver sent, so the service's handling is unchanged.
     */
    private fun notifyGeofenceTransition(action: String, poiId: String) {
        Timber.d("Geofence transition: $action for $poiId")

        val serviceIntent = Intent(context, TourModeService::class.java).apply {
            this.action = AppConstants.ACTION_PROCESS_GEOFENCE
            putExtra("action", action)
            putStringArrayListExtra("geofence_ids", arrayListOf(poiId))
        }

        // TourModeService calls startForeground() immediately in
        // onStartCommand(), and monitoring only runs while it is foreground
        ContextCompat.startForegroundService(context, serviceIntent)
    }

    /**
     * Stop monitoring for nearby points of interest.
     */
    override fun stopProximityMonitoring() {
        locationListener?.let {
            locationClient.removeUpdates(it)
            locationListener = null
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
        geofenceEntryTimes.clear()
        dwellNotified.clear()
    }

    /**
     * Check if the monitoring service is currently active.
     */
    override fun isMonitoringActive(): Boolean {
        return isMonitoring
    }

    /**
     * Register a point of interest for proximity monitoring. Transitions
     * are derived from location updates, so registration is just bookkeeping.
     */
    override suspend fun registerPointOfInterest(
        pointOfInterest: PointOfInterest,
        customRadius: Int?
    ): Boolean {
        monitoredPointsOfInterest[pointOfInterest.id] = pointOfInterest

        if (customRadius != null) {
            customRadii[pointOfInterest.id] = customRadius
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
        geofenceEntryTimes.remove(pointOfInterestId)
        dwellNotified.remove(pointOfInterestId)
        return true
    }

    /**
     * Unregister all points of interest from proximity monitoring.
     */
    override suspend fun unregisterAllPointsOfInterest(): Boolean {
        monitoredPointsOfInterest.clear()
        customRadii.clear()
        geofenceEntryTimes.clear()
        dwellNotified.clear()
        return true
    }

    /**
     * Get the current location of the device.
     */
    override suspend fun getCurrentLocation(): LatLng? {
        return try {
            locationClient.lastKnownLocation()?.let { LatLng(it.latitude, it.longitude) }
        } catch (e: SecurityException) {
            Timber.e(e, "Missing location permission")
            throw e
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
