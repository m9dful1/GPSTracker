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
import com.spiritwisestudios.gpstracker.util.LocationCadence
import com.spiritwisestudios.gpstracker.util.ProximityAlertGate
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
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

    // One alert per place per change: every fix re-measures every place, and
    // an alert costs the tour service a content lookup
    private val alertGate = ProximityAlertGate()

    // Store previous location to calculate direction and speed
    private var previousLocation: Location? = null
    private var previousLocationTimestamp: Long = 0
    private var currentSpeed = 0f // meters per second
    private var currentBearing = 0f // degrees, direction of travel

    // Inputs to the update-cadence policy; re-registering the listener is
    // not free (the provider restarts its delivery schedule), so track what
    // is currently requested and only re-register on an actual change
    private var batteryPercent = 100
    private var requestedIntervalMs = 0L

    companion object {
        // Linger this long inside a radius before the "dwell" notification
        private const val DWELL_DELAY_MS = 30_000L
    }

    // Battery monitoring
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level == -1 || scale == -1) return
            batteryPercent = (level * 100 / scale.toFloat()).toInt()

            // Update location cadence if monitoring is active
            if (isMonitoring) {
                applyDesiredInterval()
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
        batteryPercent = if (level != -1 && scale != -1) (level * 100 / scale.toFloat()).toInt() else 100

        // Location listener drives proximity alerts and manual geofencing
        locationListener = LocationListenerCompat { location ->
            // Speed and direction of travel, computed once per fix
            updateMotion(location)

            // Process the new location (alerts + geofence transitions). Each
            // alert is sent as it is produced: collecting them in one shared
            // field meant two places in range left only the last one, and a
            // fix that produced nothing re-sent whatever was there before.
            processNewLocation(location, detectionRadius) { alert ->
                trySend(alert)
            }

            // Speed may have moved the desired cadence to another band
            applyDesiredInterval()
        }

        try {
            // Start location updates at a speed/battery-appropriate cadence
            applyDesiredInterval()
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

    /**
     * (Re-)register the active listener when speed or battery moves the
     * desired cadence to a different band. A no-op while the requested
     * interval already matches, so steady driving doesn't reset the
     * provider's delivery schedule on every fix.
     */
    private fun applyDesiredInterval() {
        val desired = LocationCadence.intervalMs(currentSpeed, batteryPercent)
        if (desired == requestedIntervalMs) return

        locationListener?.let {
            locationClient.requestUpdates(desired, 0f, it)
            requestedIntervalMs = desired
            Timber.d("Location updates requested with interval $desired ms")
        }
    }

    /**
     * Update speed and direction of travel from a new fix. The fix's own
     * Doppler speed and course are preferred — they are instantaneous and
     * far less noisy than deltas between consecutive positions, which only
     * serve as the fallback.
     */
    private fun updateMotion(location: Location) {
        val currentTime = System.currentTimeMillis()
        val prev = previousLocation

        currentSpeed = when {
            location.hasSpeed() -> location.speed
            prev != null -> {
                val meters = calculateDistance(
                    prev.latitude, prev.longitude,
                    location.latitude, location.longitude
                )
                val seconds = (currentTime - previousLocationTimestamp) / 1000f
                if (seconds > 0) meters / seconds else currentSpeed
            }
            else -> 0f
        }

        currentBearing = when {
            // The course is only trustworthy in motion
            location.hasBearing() && currentSpeed >= LocationCadence.SPEED_STATIONARY_MPS ->
                location.bearing
            prev != null -> calculateBearing(
                prev.latitude, prev.longitude,
                location.latitude, location.longitude
            )
            else -> currentBearing
        }

        previousLocation = location
        previousLocationTimestamp = currentTime
    }

    /**
     * Process a new location update: generate proximity alerts and derive
     * geofence enter/dwell/exit transitions for the monitored POIs.
     *
     * [onAlert] receives every alert this fix produced, in the order they
     * were found — one place in range must not hide another.
     */
    private fun processNewLocation(
        location: Location,
        defaultRadius: Int,
        onAlert: (LocationAwarenessService.ProximityAlert) -> Unit
    ) {
        val currentTime = System.currentTimeMillis()

        // Motion computed once per fix by updateMotion()
        val speed = currentSpeed
        val bearing = currentBearing

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

            // Alert only on something new for this place — a changed state,
            // or the same one held long enough to bear repeating. The gate is
            // consulted even with no alert, so leaving the radius clears the
            // place and a return alerts straight away.
            val worthAlerting = alertGate.shouldAlert(poiId, alertType, currentTime)
            if (alertType == null || !worthAlerting) {
                continue
            }

            // Calculate estimated time to reach if approaching
            val estimatedTimeToReach =
                if (alertType == LocationAwarenessService.AlertType.APPROACHING && speed > 0) {
                    (distance / speed * 1000).toLong() // milliseconds
                } else {
                    null
                }

            onAlert(
                LocationAwarenessService.ProximityAlert(
                    pointOfInterest = poi,
                    distance = distance,
                    estimatedTimeToReach = estimatedTimeToReach,
                    alertType = alertType
                )
            )
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
        currentBearing = 0f
        requestedIntervalMs = 0L
        geofenceEntryTimes.clear()
        dwellNotified.clear()
        alertGate.reset()
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
        alertGate.forget(pointOfInterestId)
        return true
    }

    /**
     * Unregister all points of interest from proximity monitoring.
     */
    override fun monitoredPointOfInterest(pointOfInterestId: String): PointOfInterest? {
        return monitoredPointsOfInterest[pointOfInterestId]
    }

    override suspend fun unregisterAllPointsOfInterest(): Boolean {
        monitoredPointsOfInterest.clear()
        customRadii.clear()
        geofenceEntryTimes.clear()
        dwellNotified.clear()
        alertGate.reset()
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
