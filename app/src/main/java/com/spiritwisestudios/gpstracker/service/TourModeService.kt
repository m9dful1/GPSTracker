package com.spiritwisestudios.gpstracker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.android.gms.maps.model.LatLng
import com.spiritwisestudios.gpstracker.MainActivity
import com.spiritwisestudios.gpstracker.R
import com.spiritwisestudios.gpstracker.domain.model.PointOfInterest
import com.spiritwisestudios.gpstracker.domain.model.UserPreferences
import com.spiritwisestudios.gpstracker.domain.repository.PlacesRepository
import com.spiritwisestudios.gpstracker.domain.service.AudioService
import com.spiritwisestudios.gpstracker.domain.service.ContentService
import com.spiritwisestudios.gpstracker.domain.service.LocationAwarenessService
import com.spiritwisestudios.gpstracker.util.AppConstants
import com.spiritwisestudios.gpstracker.util.GeoUtils
import com.spiritwisestudios.gpstracker.util.TourLogic
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Foreground service that manages the automatic tour guide functionality.
 *
 * Discovery is continuous: POIs are re-fetched as the user moves, and when a
 * navigation route is active the whole route corridor is registered ahead of
 * the user so narration follows the drive.
 */
@AndroidEntryPoint
class TourModeService : Service() {

    @Inject
    lateinit var locationAwarenessService: LocationAwarenessService

    @Inject
    lateinit var placesRepository: PlacesRepository

    @Inject
    lateinit var contentService: ContentService

    @Inject
    lateinit var audioService: AudioService

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val binder = TourModeServiceBinder()

    // Current service state
    private val _serviceState = MutableStateFlow<TourModeState>(TourModeState.Inactive)
    val serviceState: StateFlow<TourModeState> = _serviceState

    // Current user preferences
    private var userPreferences: UserPreferences = UserPreferences()

    // Current POI being described
    private var currentPoi: PointOfInterest? = null

    // Rolling discovery state
    private var refreshJob: Job? = null
    private var lastFetchCenter: LatLng? = null
    private var routeCorridorActive = false

    companion object {
        // Search this far around the user (or route samples) for POIs
        private const val DISCOVERY_RADIUS_METERS = 1500

        // Re-discover when the user has moved this far from the last fetch
        private const val REFRESH_DISTANCE_METERS = 750f
        private const val REFRESH_CHECK_INTERVAL_MS = 30_000L
    }

    // Notification constants
    private val NOTIFICATION_CHANNEL_SERVICE = "tour_mode_service_channel"
    private val NOTIFICATION_CHANNEL_POI_APPROACHING = "tour_mode_poi_approaching_channel"
    private val NOTIFICATION_CHANNEL_POI_ARRIVED = "tour_mode_poi_arrived_channel"
    private val NOTIFICATION_CHANNEL_PLAYBACK = "tour_mode_playback_channel"
    private val NOTIFICATION_ID = 1001

    override fun onCreate() {
        super.onCreate()
        Timber.d("TourModeService created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d("TourModeService started with intent: $intent")

        // Enter the foreground immediately: the geofence receiver starts this
        // service with startForegroundService(), which requires a prompt
        // startForeground() call. Redundant calls just update the notification.
        startForeground(
            NOTIFICATION_ID,
            createNotification("Tour Mode Active", "Discovering interesting places nearby...", NOTIFICATION_CHANNEL_SERVICE)
        )

        when (intent?.action) {
            AppConstants.ACTION_START_TOUR_MODE -> {
                startTourMode()
            }
            AppConstants.ACTION_STOP_TOUR_MODE -> {
                stopTourMode()
            }
            AppConstants.ACTION_PROCESS_GEOFENCE -> {
                val action = intent.getStringExtra("action") ?: return START_STICKY
                val geofenceIds = intent.getStringArrayListExtra("geofence_ids") ?: return START_STICKY

                // If we were revived by a geofence event, make sure tour mode is running
                if (_serviceState.value !is TourModeState.Active) {
                    startTourMode(UserPreferences())
                }

                // Process the geofence event
                handleGeofenceEvent(action, geofenceIds)
            }
            AppConstants.ACTION_PLAY_PAUSE -> {
                handlePlayPauseAction()
            }
            AppConstants.ACTION_NEXT_POI -> {
                handleNextPoiAction()
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        stopTourMode()
        serviceScope.cancel()
        super.onDestroy()
        Timber.d("TourModeService destroyed")
    }

    /**
     * Start the tour mode.
     */
    fun startTourMode(preferences: UserPreferences = UserPreferences()) {
        if (_serviceState.value is TourModeState.Active) {
            Timber.d("Tour mode already active")
            return
        }

        userPreferences = preferences

        // Start monitoring for POIs
        serviceScope.launch {
            try {
                // Initialize audio service
                audioService.initialize(userPreferences)

                // Fetch nearby places first
                val currentLocation = locationAwarenessService.getCurrentLocation()
                if (currentLocation != null) {
                    fetchAndRegisterNearbyPlaces(currentLocation)
                }

                // Update service state
                if (_serviceState.value !is TourModeState.Active) {
                    _serviceState.value = TourModeState.Active(emptyList())
                }

                // Keep discovering places as the user moves
                startRollingRefresh()

                // Start monitoring for proximity alerts
                locationAwarenessService.startProximityMonitoring(userPreferences.notifyDistance)
                    .catch { e ->
                        Timber.e(e, "Error in proximity monitoring")
                        _serviceState.value = TourModeState.Error("Error monitoring locations: ${e.message}")
                    }
                    .collectLatest { proximityAlert ->
                        handleProximityAlert(proximityAlert)
                    }
            } catch (e: Exception) {
                Timber.e(e, "Error starting tour mode")
                _serviceState.value = TourModeState.Error("Failed to start tour mode: ${e.message}")
                stopSelf()
            }
        }
    }

    /**
     * Stop the tour mode.
     */
    fun stopTourMode() {
        if (_serviceState.value !is TourModeState.Active) {
            return
        }

        // Stop discovery and proximity monitoring
        refreshJob?.cancel()
        refreshJob = null
        routeCorridorActive = false
        lastFetchCenter = null
        locationAwarenessService.stopProximityMonitoring()

        // Remove registered geofences
        serviceScope.launch {
            try {
                locationAwarenessService.unregisterAllPointsOfInterest()
            } catch (e: Exception) {
                Timber.e(e, "Error unregistering points of interest")
            }
        }

        // Stop audio playback
        audioService.stop()

        // Clear content queue
        contentService.clearContentQueue()

        // Update service state
        _serviceState.value = TourModeState.Inactive

        // Stop the foreground service
        stopForeground(true)
        stopSelf()
    }

    /**
     * Register the POIs along a navigation route so narration follows the
     * drive. Called by MainActivity when navigation starts or re-routes.
     */
    fun updateRouteCorridor(route: List<LatLng>) {
        if (route.isEmpty()) return

        serviceScope.launch {
            try {
                routeCorridorActive = true
                val places = placesRepository.getPlacesAlongRoute(route, DISCOVERY_RADIUS_METERS / 3)
                Timber.d("Route corridor: found ${places.size} places along route")

                // The corridor replaces whatever was registered before
                locationAwarenessService.unregisterAllPointsOfInterest()
                registerPlaces(places)

                updateNotification(
                    "Tour Mode Active",
                    "Watching ${places.size} interesting places along your route",
                    NOTIFICATION_CHANNEL_SERVICE
                )
            } catch (e: Exception) {
                Timber.e(e, "Error updating route corridor")
            }
        }
    }

    /**
     * Return to location-based discovery after navigation ends.
     */
    fun clearRouteCorridor() {
        routeCorridorActive = false
        lastFetchCenter = null // force a refresh around the current position
    }

    /**
     * Periodically re-discover POIs around the user as they move. Route
     * corridors take precedence — the loop idles while one is active.
     */
    private fun startRollingRefresh() {
        refreshJob?.cancel()
        refreshJob = serviceScope.launch {
            while (isActive) {
                delay(REFRESH_CHECK_INTERVAL_MS)
                if (routeCorridorActive) continue

                try {
                    val location = locationAwarenessService.getCurrentLocation() ?: continue
                    val last = lastFetchCenter
                    if (last == null || GeoUtils.distanceMeters(last, location) > REFRESH_DISTANCE_METERS) {
                        Timber.d("Moved ${last?.let { GeoUtils.distanceMeters(it, location).toInt() } ?: "∞"}m — refreshing nearby places")
                        fetchAndRegisterNearbyPlaces(location)
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error in rolling POI refresh")
                }
            }
        }
    }

    /**
     * Fetch POIs around a location and register them for monitoring.
     */
    private suspend fun fetchAndRegisterNearbyPlaces(location: LatLng) {
        try {
            lastFetchCenter = location
            val places = placesRepository.getNearbyPlaces(location, DISCOVERY_RADIUS_METERS).first()
            Timber.d("Found ${places.size} nearby places")

            // Replace the previous registration set so geofences don't accumulate
            // past the platform limit of 100
            locationAwarenessService.unregisterAllPointsOfInterest()
            registerPlaces(places)
        } catch (e: Exception) {
            Timber.e(e, "Error fetching nearby places")
        }
    }

    /**
     * Register places for proximity monitoring and prefetch their narration.
     */
    private suspend fun registerPlaces(places: List<PointOfInterest>) {
        if (places.isEmpty()) return

        // Adjust geofence radius based on current speed
        val currentSpeed = locationAwarenessService.getCurrentSpeed() ?: 0f
        val adjustedRadius = TourLogic.geofenceRadiusFor(currentSpeed, userPreferences.notifyDistance)
        Timber.d("Geofence radius: $adjustedRadius m (speed: $currentSpeed m/s)")

        val registeredCount = locationAwarenessService.registerPointsOfInterest(places, adjustedRadius)
        Timber.d("Registered $registeredCount places for proximity monitoring")

        // If preferences indicate, prefetch content for these places
        if (userPreferences.prefetchContent) {
            contentService.prefetchContent(places, userPreferences)
        }

        // Update service state with nearby places
        if (_serviceState.value is TourModeState.Active) {
            _serviceState.value = TourModeState.Active(places)
        }
    }

    /**
     * Handle geofence events from the GeofenceBroadcastReceiver.
     */
    private fun handleGeofenceEvent(action: String, geofenceIds: List<String>) {
        serviceScope.launch {
            try {
                when (action) {
                    "enter", "dwell" -> {
                        // For each geofence ID (POI ID), fetch the POI and generate/play content
                        for (poiId in geofenceIds) {
                            val result = placesRepository.getPlaceDetails(poiId)
                            result.onSuccess { poi ->
                                // If we're already describing this POI, don't interrupt
                                if (currentPoi?.id == poi.id) {
                                    return@onSuccess
                                }

                                // Generate and queue content for this POI
                                val priority = if (action == "dwell") 1 else 0
                                generateAndQueueContent(poi, priority)

                                // Update notification
                                updateNotification("Tour Mode Active", "Approaching ${poi.name}", NOTIFICATION_CHANNEL_SERVICE)
                            }
                        }
                    }
                    "exit" -> {
                        Timber.d("Exited POI zones: $geofenceIds")
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error handling geofence event: $action for $geofenceIds")
            }
        }
    }

    /**
     * Handle proximity alerts from the LocationAwarenessService.
     */
    private fun handleProximityAlert(proximityAlert: LocationAwarenessService.ProximityAlert) {
        // Log the alert
        Timber.d("Proximity alert: ${proximityAlert.alertType} for ${proximityAlert.pointOfInterest.name} at ${proximityAlert.distance}m")

        // Update the notification based on alert type
        when (proximityAlert.alertType) {
            LocationAwarenessService.AlertType.APPROACHING -> {
                val estimatedTime = proximityAlert.estimatedTimeToReach?.let {
                    val seconds = it / 1000
                    if (seconds < 60) "$seconds seconds" else "${seconds / 60} minutes"
                } ?: ""

                val content = if (estimatedTime.isNotEmpty()) {
                    "Approaching ${proximityAlert.pointOfInterest.name} (${proximityAlert.distance.toInt()}m) - ETA: $estimatedTime"
                } else {
                    "Approaching ${proximityAlert.pointOfInterest.name} (${proximityAlert.distance.toInt()}m)"
                }

                updateNotification(
                    "Approaching Point of Interest",
                    content,
                    NOTIFICATION_CHANNEL_POI_APPROACHING
                )
            }
            LocationAwarenessService.AlertType.ARRIVED -> {
                updateNotification(
                    "Arrived at Point of Interest",
                    "You have arrived at ${proximityAlert.pointOfInterest.name}",
                    NOTIFICATION_CHANNEL_POI_ARRIVED
                )
            }
            else -> {
                // No notification update needed for other alert types
            }
        }

        // Handle the alert based on type
        serviceScope.launch {
            when (proximityAlert.alertType) {
                LocationAwarenessService.AlertType.APPROACHING,
                LocationAwarenessService.AlertType.ARRIVED -> {
                    // Determine initial priority based on alert type
                    val basePriority = when (proximityAlert.alertType) {
                        LocationAwarenessService.AlertType.ARRIVED -> 5 // Higher priority when arrived
                        LocationAwarenessService.AlertType.APPROACHING -> 3 // Medium priority when approaching
                        else -> 0 // Should not reach here
                    }

                    // If auto-play is enabled and we're not already describing this POI,
                    // generate and queue content
                    if (userPreferences.autoPlayContent && currentPoi?.id != proximityAlert.pointOfInterest.id) {
                        generateAndQueueContent(proximityAlert.pointOfInterest, basePriority)
                    }
                }
                else -> {
                    // No action needed for other alert types
                }
            }
        }
    }

    /**
     * Generate and queue content for a point of interest.
     */
    private suspend fun generateAndQueueContent(poi: PointOfInterest, priority: Int = 0) {
        try {
            // Set as current POI
            currentPoi = poi

            // Get or generate content
            val content = contentService.getContentForPlace(poi, userPreferences)

            // Calculate content priority (user prefs, rating, alert proximity)
            val calculatedPriority = TourLogic.contentPriorityFor(poi, userPreferences, priority)

            // Queue for delivery
            contentService.queueContentForDelivery(content, calculatedPriority)

            // If nothing is currently being spoken, start speaking
            if (!audioService.isSpeaking()) {
                deliverNextContent()
            }
        } catch (e: Exception) {
            Timber.e(e, "Error generating content for ${poi.name}")
        }
    }

    /**
     * Deliver the next piece of content in the queue.
     */
    private suspend fun deliverNextContent() {
        try {
            val content = contentService.getNextContent() ?: return

            // Set as current POI
            val poi = placesRepository.getPlaceDetails(content.poiId).getOrNull()
            currentPoi = poi

            // Speak the content
            audioService.speak(content)
                .collectLatest { status ->
                    when (status) {
                        AudioService.SpeakingStatus.COMPLETED -> {
                            // When complete, deliver the next content if available
                            deliverNextContent()
                        }
                        AudioService.SpeakingStatus.ERROR -> {
                            Timber.e("Error speaking content for ${content.title}")
                            // Try next content
                            deliverNextContent()
                        }
                        else -> {
                            // No action needed for other statuses
                        }
                    }
                }
        } catch (e: Exception) {
            Timber.e(e, "Error delivering content")
        }
    }

    /**
     * Create the notification channels for the foreground service.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Service channel (for ongoing service status)
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_SERVICE,
                "Tour Mode Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows the status of the tour guide service"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }

            // Approaching POI channel (for when approaching a POI)
            val approachingChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_POI_APPROACHING,
                "Approaching Points of Interest",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifies you when approaching interesting places"
                setShowBadge(true)
                enableLights(true)
                lightColor = android.graphics.Color.BLUE
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 100, 50, 100)
            }

            // Arrived at POI channel (for when arrived at a POI)
            val arrivedChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_POI_ARRIVED,
                "Arrived at Points of Interest",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifies you when you've arrived at interesting places"
                setShowBadge(true)
                enableLights(true)
                lightColor = android.graphics.Color.GREEN
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 200, 100, 200)
            }

            // Playback control channel (for audio playback controls)
            val playbackChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_PLAYBACK,
                "Audio Playback Controls",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Controls for audio narration playback"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }

            // Create all channels
            notificationManager.createNotificationChannels(
                listOf(serviceChannel, approachingChannel, arrivedChannel, playbackChannel)
            )
        }
    }

    /**
     * Create a notification for the foreground service.
     */
    private fun createNotification(title: String, content: String, channelId: String = NOTIFICATION_CHANNEL_SERVICE): Notification {
        // Create intents for notification actions
        val mainActivityIntent = Intent(this, MainActivity::class.java)
        val mainPendingIntent = PendingIntent.getActivity(
            this,
            0,
            mainActivityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Create action intents
        val stopIntent = Intent(this, TourModeService::class.java).apply {
            action = AppConstants.ACTION_STOP_TOUR_MODE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIntent = Intent(this, TourModeService::class.java).apply {
            action = AppConstants.ACTION_PLAY_PAUSE
        }
        val playPausePendingIntent = PendingIntent.getService(
            this,
            2,
            playPauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nextIntent = Intent(this, TourModeService::class.java).apply {
            action = AppConstants.ACTION_NEXT_POI
        }
        val nextPendingIntent = PendingIntent.getService(
            this,
            3,
            nextIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Create the base notification builder
        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(mainPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)

        // Add actions based on the channel type
        when (channelId) {
            NOTIFICATION_CHANNEL_SERVICE -> {
                // Basic service notification, just show stop action
                builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Tour", stopPendingIntent)
            }
            NOTIFICATION_CHANNEL_POI_APPROACHING, NOTIFICATION_CHANNEL_POI_ARRIVED -> {
                // POI notifications, show all controls
                builder.addAction(android.R.drawable.ic_media_pause, "Play/Pause", playPausePendingIntent)
                      .addAction(android.R.drawable.ic_media_next, "Next", nextPendingIntent)
                      .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Tour", stopPendingIntent)
                      .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            }
            NOTIFICATION_CHANNEL_PLAYBACK -> {
                // Playback notifications, focus on playback controls
                builder.addAction(
                    if (audioService.isSpeaking()) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    if (audioService.isSpeaking()) "Pause" else "Play",
                    playPausePendingIntent
                )
                      .addAction(android.R.drawable.ic_media_next, "Next", nextPendingIntent)
                      .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            }
        }

        return builder.build()
    }

    /**
     * Update the notification with new information.
     */
    private fun updateNotification(title: String, content: String, channelId: String = NOTIFICATION_CHANNEL_SERVICE) {
        val notification = createNotification(title, content, channelId)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Handle play/pause action from notification.
     */
    private fun handlePlayPauseAction() {
        serviceScope.launch {
            if (audioService.isSpeaking()) {
                audioService.pause()
                updateNotification(
                    "Audio Paused",
                    "Paused narration for ${currentPoi?.name ?: "Unknown location"}",
                    NOTIFICATION_CHANNEL_PLAYBACK
                )
            } else {
                audioService.resume()
                val poiName = currentPoi?.name ?: "Unknown location"
                updateNotification(
                    "Playing Audio",
                    "Playing narration for $poiName",
                    NOTIFICATION_CHANNEL_PLAYBACK
                )
            }
        }
    }

    /**
     * Handle next POI action from notification.
     */
    private fun handleNextPoiAction() {
        serviceScope.launch {
            // Stop current audio
            audioService.stop()

            // Get next content if available
            deliverNextContent()
        }
    }

    /**
     * Binder class for clients to access the service.
     */
    inner class TourModeServiceBinder : Binder() {
        fun getService(): TourModeService = this@TourModeService
    }

    /**
     * States for the tour mode service.
     */
    sealed class TourModeState {
        data object Inactive : TourModeState()
        data class Active(val nearbyPlaces: List<PointOfInterest>) : TourModeState()
        data class Error(val message: String) : TourModeState()
    }
}
