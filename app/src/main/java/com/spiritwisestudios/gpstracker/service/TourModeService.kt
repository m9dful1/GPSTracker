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
import com.spiritwisestudios.gpstracker.domain.model.TourContent
import com.spiritwisestudios.gpstracker.domain.model.UserPreferences
import com.spiritwisestudios.gpstracker.domain.repository.PlacesRepository
import com.spiritwisestudios.gpstracker.domain.service.AudioService
import com.spiritwisestudios.gpstracker.domain.service.ContentService
import com.spiritwisestudios.gpstracker.domain.service.LocationAwarenessService
import com.spiritwisestudios.gpstracker.util.AppConstants
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Foreground service that manages the automatic tour guide functionality.
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
        
        // For any intent, ensure service is in foreground state immediately if needed
        val wasStartedWithForeground = intent?.getBooleanExtra("start_as_foreground", false) ?: false
        if (wasStartedWithForeground) {
            startForeground(NOTIFICATION_ID, createNotification("Tour Mode Active", "Discovering interesting places nearby...", NOTIFICATION_CHANNEL_SERVICE))
        }
        
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
                
                // Ensure we're in foreground for geofence processing
                if (_serviceState.value !is TourModeState.Active) {
                    // If not already active, start foreground immediately (required for Android 8+)
                    startForeground(NOTIFICATION_ID, createNotification(
                        "Processing Geofence",
                        "Analyzing location data...",
                        NOTIFICATION_CHANNEL_SERVICE
                    ))
                    
                    // Since we're starting from a geofence event, also initiate tour mode 
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
        
        // Start the service as a foreground service
        startForeground(NOTIFICATION_ID, createNotification("Tour Mode Active", "Discovering interesting places nearby...", NOTIFICATION_CHANNEL_SERVICE))
        
        // Start monitoring for POIs
        serviceScope.launch {
            try {
                // Initialize audio service
                audioService.initialize(userPreferences)
                
                // Fetch nearby places first
                val currentLocation = locationAwarenessService.getCurrentLocation()
                if (currentLocation != null) {
                    fetchAndRegisterNearbyPlaces(currentLocation, userPreferences.notifyDistance)
                }
                
                // Update service state
                _serviceState.value = TourModeState.Active(emptyList())
                
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
        
        // Stop proximity monitoring
        locationAwarenessService.stopProximityMonitoring()
        
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
     * Fetch and register nearby places for monitoring.
     */
    private suspend fun fetchAndRegisterNearbyPlaces(location: LatLng, radius: Int) {
        serviceScope.launch {
            try {
                // Use the places repository to fetch nearby places
                placesRepository.getNearbyPlaces(radius).collectLatest { places ->
                    Timber.d("Found ${places.size} nearby places")
                    
                    // Get current speed from the location awareness service if available
                    val currentSpeed = locationAwarenessService.getCurrentSpeed() ?: 0f
                    
                    // Adjust geofence radius based on speed
                    val adjustedRadius = calculateGeofenceRadius(currentSpeed, radius)
                    Timber.d("Adjusted geofence radius: $adjustedRadius meters (speed: $currentSpeed m/s)")
                    
                    // Register these places for proximity monitoring
                    val registeredCount = locationAwarenessService.registerPointsOfInterest(places, adjustedRadius)
                    Timber.d("Registered $registeredCount places for proximity monitoring")
                    
                    // If preferences indicate, prefetch content for these places
                    if (userPreferences.prefetchContent) {
                        contentService.prefetchContent(places, userPreferences)
                        Timber.d("Prefetched content for ${places.size} places")
                    }
                    
                    // Update service state with nearby places
                    if (_serviceState.value is TourModeState.Active) {
                        _serviceState.value = TourModeState.Active(places)
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error fetching nearby places")
            }
        }
    }
    
    /**
     * Calculate an appropriate geofence radius based on movement speed.
     * Faster speeds require larger geofences to provide timely notifications.
     */
    private fun calculateGeofenceRadius(speedMetersPerSecond: Float, baseRadius: Int): Int {
        // Convert to km/h for more intuitive thresholds
        val speedKmh = speedMetersPerSecond * 3.6f
        
        return when {
            speedKmh < 2.0f -> baseRadius // Walking slowly or stationary
            speedKmh < 7.0f -> (baseRadius * 1.5).toInt() // Walking
            speedKmh < 15.0f -> (baseRadius * 2.0).toInt() // Jogging or cycling
            speedKmh < 40.0f -> (baseRadius * 3.0).toInt() // Driving in city
            speedKmh < 80.0f -> (baseRadius * 5.0).toInt() // Driving on highway
            else -> (baseRadius * 8.0).toInt() // Very fast movement
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
                        // If we're exiting a POI zone, we might want to stop or fade out audio
                        // for now, just log it
                        Timber.d("Exited POI zones: $geofenceIds")
                        
                        // If we're currently describing one of these POIs, pause or stop
                        val exitedCurrentPoi = currentPoi?.let { geofenceIds.contains(it.id) } ?: false
                        if (exitedCurrentPoi && audioService.isSpeaking()) {
                            // Optionally fade out audio or just stop
                            // audioService.stop()
                            // currentPoi = null
                        }
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
                    val currentPoiId = currentPoi?.id
                    val newPoiId = proximityAlert.pointOfInterest.id
                    
                    if (userPreferences.autoPlayContent) {
                        if (currentPoiId != newPoiId) {
                            // New POI - queue it according to priority
                            generateAndQueueContent(proximityAlert.pointOfInterest, basePriority)
                        } else if (audioService.isSpeaking() && proximityAlert.alertType == LocationAwarenessService.AlertType.ARRIVED) {
                            // Same POI but we've arrived and are still speaking about approaching
                            // Could update content or delivery here
                        }
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
            
            // Calculate content priority based on multiple factors
            val calculatedPriority = calculateContentPriority(poi, priority)
            
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
     * Calculate content priority based on multiple factors.
     * Higher number means higher priority.
     */
    private fun calculateContentPriority(poi: PointOfInterest, basePriority: Int): Int {
        var priority = basePriority
        
        // Factor 1: POI rating (0-5 scale, add 0-3 priority points)
        poi.rating?.let { rating ->
            priority += when {
                rating >= 4.5 -> 3 // Exceptional rating
                rating >= 4.0 -> 2 // Very good rating
                rating >= 3.5 -> 1 // Good rating
                else -> 0          // Average or below rating
            }
        }
        
        // Factor 2: User preference categories (add 2 priority points if in preferred categories)
        val poiCategory = try {
            PointOfInterest.Category.valueOf(poi.category.uppercase())
        } catch (e: IllegalArgumentException) {
            null
        }
        
        if (poiCategory != null && userPreferences.preferredCategories.contains(poiCategory)) {
            priority += 2
        }
        
        // Factor 3: Previously visited (subtract 3 priority points if already visited)
        if (poi.isVisited) {
            priority -= 3
        }
        
        // Factor 4: Distance to POI - handled by location awareness service
        // (already affects when notifications are triggered)
        
        // Factor 5: Time of day - certain POIs might be more interesting at specific times
        // This could be extended in the future
        
        // Ensure priority doesn't go below 0
        return priority.coerceAtLeast(0)
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