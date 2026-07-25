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
import com.spiritwisestudios.gpstracker.domain.model.LatLng
import com.spiritwisestudios.gpstracker.MainActivity
import com.spiritwisestudios.gpstracker.R
import com.spiritwisestudios.gpstracker.data.api.NearbyCityApiService
import com.spiritwisestudios.gpstracker.data.repository.UserPreferencesRepository
import com.spiritwisestudios.gpstracker.domain.model.PointOfInterest
import com.spiritwisestudios.gpstracker.domain.model.TourContent
import com.spiritwisestudios.gpstracker.domain.model.UserPreferences
import com.spiritwisestudios.gpstracker.domain.repository.PlacesRepository
import com.spiritwisestudios.gpstracker.domain.service.AudioService
import com.spiritwisestudios.gpstracker.domain.service.ContentService
import com.spiritwisestudios.gpstracker.domain.service.LocationAwarenessService
import com.spiritwisestudios.gpstracker.util.AppConstants
import com.spiritwisestudios.gpstracker.util.GeoUtils
import com.spiritwisestudios.gpstracker.util.TourCommand
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
import kotlinx.coroutines.flow.drop
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

    @Inject
    lateinit var userPreferencesRepository: UserPreferencesRepository

    @Inject
    lateinit var nearbyCityApiService: NearbyCityApiService

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val binder = TourModeServiceBinder()

    // Current service state
    private val _serviceState = MutableStateFlow<TourModeState>(TourModeState.Inactive)
    val serviceState: StateFlow<TourModeState> = _serviceState

    // Narration currently being spoken, so the UI can show a fact card
    private val _currentNarration = MutableStateFlow<Narration?>(null)
    val currentNarration: StateFlow<Narration?> = _currentNarration

    // Whether the current narration is audibly playing (false = paused).
    // Lets the in-app fact card mirror the notification's play/pause state.
    private val _isNarrationPlaying = MutableStateFlow(true)
    val isNarrationPlaying: StateFlow<Boolean> = _isNarrationPlaying

    // Current user preferences
    private var userPreferences: UserPreferences = UserPreferences()

    // Current POI being described
    private var currentPoi: PointOfInterest? = null

    // The tour's own coroutine: settings, TTS, discovery and proximity
    // monitoring all live in it, so cancelling it ends the tour
    private var tourJob: Job? = null

    // Rolling discovery state
    private var refreshJob: Job? = null
    private var preferencesJob: Job? = null
    private var lastFetchCenter: LatLng? = null
    private var routeCorridorActive = false

    // Places narrated since the current route corridor began, for the
    // arrival summary ("you heard about 7 places along the way")
    private var tripNarrationCount = 0

    // The drive's most memorable narrated place, called back in the
    // arrival summary the way a guide closes on the highlight
    private var tripHighlightName: String? = null
    private var tripHighlightScore = -1

    // True from the moment a narration is pulled off the queue until the
    // queue drains, including the scheduled pause between narrations —
    // isSpeaking() alone would let a geofence event double-deliver during
    // that pause
    @Volatile
    private var isDelivering = false

    // Way-of-life filler state: when the guide last said anything, when the
    // last filler played, which regions were already covered this session,
    // and the watcher job itself
    private var wayOfLifeJob: Job? = null
    @Volatile
    private var lastSpokenAt = 0L
    private var lastWayOfLifeAt: Long? = null
    private val narratedRegions = mutableSetOf<String>()

    // When each automatic narration was queued, for the per-hour cap.
    // Entries older than the window are pruned as new ones arrive.
    private val narrationTimes = mutableListOf<Long>()

    companion object {
        // Search this far around the user (or route samples) for POIs
        private const val DISCOVERY_RADIUS_METERS = 1500

        // Re-discover when the user has moved this far from the last fetch
        private const val REFRESH_DISTANCE_METERS = 750f
        private const val REFRESH_CHECK_INTERVAL_MS = 30_000L

        // How often the quiet-stretch watcher checks, and how far around
        // the listener to look for the region a filler segment describes
        private const val WAY_OF_LIFE_CHECK_INTERVAL_MS = 30_000L
        private const val WAY_OF_LIFE_CITY_RADIUS_METERS = 30_000

        /**
         * Whether a service instance exists right now.
         *
         * A bound client hears everything, but an unbound one hears nothing:
         * a tour ended from the notification while the UI was in the
         * background has no way to report itself. Screens coming back check
         * this before trusting the last state they saw.
         */
        @Volatile
        var isAlive = false
            private set
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
        isAlive = true
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val command = TourCommand.forAction(intent?.action, _serviceState.value.isRunning)
        Timber.d("TourModeService start command: $command (intent=$intent)")

        if (command == TourCommand.NONE) {
            // A control for a tour that has already ended — the fact card's
            // playback buttons can send one after the service is gone, and
            // the intent itself creates this instance. Entering the
            // foreground here is what left a "Tour Mode Active" notification
            // with no tour behind it. Nothing arrives this way via
            // startForegroundService(), so skipping startForeground() is safe.
            Timber.d("Nothing to control; stopping instead of going foreground")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // Enter the foreground immediately: the geofence receiver starts this
        // service with startForegroundService(), which requires a prompt
        // startForeground() call. Redundant calls just update the notification.
        startForeground(
            NOTIFICATION_ID,
            createNotification("Tour Mode Active", "Discovering interesting places nearby...", NOTIFICATION_CHANNEL_SERVICE)
        )

        when (command) {
            // The system killed us mid-tour and handed the service back with
            // no intent. The guide's settings are persisted, so pick the tour
            // up again — the alternative is a foreground notification with
            // nothing monitoring behind it, which is what this used to do.
            TourCommand.RESUME -> {
                Timber.i("Revived without an intent; resuming the tour")
                startTourMode()
            }
            TourCommand.START -> {
                startTourMode()
            }
            TourCommand.STOP -> {
                stopTourMode()
            }
            TourCommand.GEOFENCE -> {
                val geofenceAction = intent?.getStringExtra("action")
                val geofenceIds = intent?.getStringArrayListExtra("geofence_ids")
                if (geofenceAction == null || geofenceIds == null) {
                    // Malformed. Don't hold the foreground over it when there
                    // is no tour behind it — and don't end a running tour
                    // over one bad intent either.
                    Timber.w("Geofence command without an action or ids")
                    if (!_serviceState.value.isRunning) {
                        stopSelf(startId)
                        return START_NOT_STICKY
                    }
                    return START_STICKY
                }

                // If we were revived by a geofence event, make sure tour mode
                // is running (it loads the saved settings itself)
                if (!_serviceState.value.isRunning) {
                    startTourMode()
                }

                // Process the geofence event
                handleGeofenceEvent(geofenceAction, geofenceIds)
            }
            TourCommand.PLAY_PAUSE -> {
                handlePlayPauseAction()
            }
            TourCommand.NEXT -> {
                handleNextPoiAction()
            }
            TourCommand.NONE -> {
                // Handled above
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        isAlive = false
        stopTourMode()
        serviceScope.cancel()
        super.onDestroy()
        Timber.d("TourModeService destroyed")
    }

    /**
     * Start the tour mode.
     */
    fun startTourMode() {
        if (_serviceState.value.isRunning) {
            Timber.d("Tour mode already running")
            return
        }

        // Announce the tour before any of the slow setup: clients bind to
        // this state, and leaving it Inactive while the guide gets ready is
        // what made a starting tour look like no tour at all
        _serviceState.value = TourModeState.Starting

        // Start monitoring for POIs
        tourJob = serviceScope.launch {
            try {
                // The tour just started; quiet stretches are measured from
                // here so filler never fires right out of the gate
                lastSpokenAt = System.currentTimeMillis()

                // The guide works from the user's saved settings, not
                // factory defaults — and keeps them fresh so edits in the
                // settings sheet apply mid-tour without a restart
                userPreferences = userPreferencesRepository.userPreferencesFlow.first()
                preferencesJob?.cancel()
                preferencesJob = launch {
                    // drop(1) skips the initial replay: startup is handled
                    // below, and reacting to it here would race the TTS init
                    userPreferencesRepository.userPreferencesFlow.drop(1).collectLatest { prefs ->
                        userPreferences = prefs
                        // Idempotent once TTS is up: just updates rate/pitch
                        audioService.initialize(prefs)
                    }
                }

                // Initialize audio service
                audioService.initialize(userPreferences)

                // Fetch nearby places first
                val currentLocation = locationAwarenessService.getCurrentLocation()
                val initialPlaces = if (currentLocation != null) {
                    fetchAndRegisterNearbyPlaces(currentLocation)
                } else {
                    emptyList()
                }

                // Say hello: confirms audio works and sets expectations
                // instead of silence until the first geofence fires
                if (userPreferences.audioEnabled) {
                    launch {
                        audioService.speak(TourLogic.tourStartAnnouncement(initialPlaces.size))
                            .collectLatest {}
                        lastSpokenAt = System.currentTimeMillis()
                    }
                }

                // The guide is ready. Only promote from Starting: a stop
                // request that landed during setup must not be undone here.
                if (_serviceState.value is TourModeState.Starting) {
                    _serviceState.value = TourModeState.Active(emptyList())
                }

                // Keep discovering places as the user moves
                startRollingRefresh()

                // Fill long quiet stretches with regional color
                startWayOfLifeWatcher()

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
        if (!_serviceState.value.isRunning) {
            return
        }

        // Stop the tour's own coroutine too — stopping during setup used to
        // leave it running, so a cancelled tour still registered geofences
        tourJob?.cancel()
        tourJob = null

        // Stop discovery, settings tracking, and proximity monitoring
        refreshJob?.cancel()
        refreshJob = null
        preferencesJob?.cancel()
        preferencesJob = null
        wayOfLifeJob?.cancel()
        wayOfLifeJob = null
        lastWayOfLifeAt = null
        narratedRegions.clear()
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
        _currentNarration.value = null

        // Clear content queue
        contentService.clearContentQueue()
        isDelivering = false

        // Update service state
        _serviceState.value = TourModeState.Inactive

        // Stop the foreground service
        stopForeground(true)
        stopSelf()
    }

    /**
     * Register the POIs along a navigation route so narration follows the
     * drive. Called by MainActivity when navigation starts or re-routes.
     * [tourName] is set when the drive is a planned Take a Tour loop, so
     * the guide can open with a proper welcome.
     */
    fun updateRouteCorridor(route: List<LatLng>, tourName: String? = null) {
        if (route.isEmpty()) return

        serviceScope.launch {
            try {
                // A fresh corridor starts a new trip; reroutes of the same
                // drive keep the running count
                val freshCorridor = !routeCorridorActive
                if (freshCorridor) {
                    tripNarrationCount = 0
                    tripHighlightName = null
                    tripHighlightScore = -1
                }
                routeCorridorActive = true
                val places = placesRepository.getPlacesAlongRoute(route, DISCOVERY_RADIUS_METERS / 3)
                Timber.d("Route corridor: found ${places.size} places along route")

                // The corridor replaces whatever was registered before
                locationAwarenessService.unregisterAllPointsOfInterest()
                registerPlaces(places)

                // Preview the tour so the driver knows narration is coming —
                // but only once per drive: re-announcing on every reroute is
                // the kind of chatter that gets a guide tuned out
                if (userPreferences.audioEnabled && freshCorridor) {
                    val announcement = if (tourName != null) {
                        TourLogic.tourWelcomeAnnouncement(tourName, places.size)
                    } else {
                        TourLogic.corridorAnnouncement(places.size)
                    }
                    announcement?.let {
                        launch {
                            audioService.speak(it).collectLatest {}
                            lastSpokenAt = System.currentTimeMillis()
                        }
                    }
                }

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
     * Return (and clear) the closing tour-summary line for this drive, or
     * null when nothing was narrated. MainActivity appends it to the spoken
     * arrival announcement so it rides the same utterance.
     */
    fun consumeTripSummaryPhrase(): String? {
        val phrase = TourLogic.tripSummaryPhrase(tripNarrationCount, tripHighlightName)
        tripNarrationCount = 0
        tripHighlightName = null
        tripHighlightScore = -1
        return phrase
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
     * Returns the places found (empty on failure).
     */
    private suspend fun fetchAndRegisterNearbyPlaces(location: LatLng): List<PointOfInterest> {
        return try {
            lastFetchCenter = location
            val places = placesRepository.getNearbyPlaces(location, DISCOVERY_RADIUS_METERS).first()
            Timber.d("Found ${places.size} nearby places")

            // Replace the previous registration set so geofences don't accumulate
            // past the platform limit of 100
            locationAwarenessService.unregisterAllPointsOfInterest()
            registerPlaces(places)
            places
        } catch (e: Exception) {
            Timber.e(e, "Error fetching nearby places")
            emptyList()
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
     * Handle geofence transitions forwarded by the location awareness service.
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
        // A tour guide shouldn't repeat itself day to day, but going silent
        // forever would mute a daily commute after the first week — visited
        // places become eligible again once the revisit cooldown passes.
        // On-demand playback from the place details sheet is unaffected.
        if (TourLogic.shouldSkipNarration(poi.isVisited, poi.visitedDate, System.currentTimeMillis())) {
            Timber.d("Skipping ${poi.name}: narrated recently")
            return
        }

        // Honor the "max notifications per hour" setting: a tour guide who
        // won't stop talking gets tuned out. Skipped places stay unvisited,
        // so they're eligible again once the hour rolls over.
        val now = System.currentTimeMillis()
        while (narrationTimes.isNotEmpty() && now - narrationTimes.first() > TourLogic.NARRATION_WINDOW_MS) {
            narrationTimes.removeAt(0)
        }
        if (!TourLogic.narrationAllowed(narrationTimes, now, userPreferences.maxNotificationsPerHour)) {
            Timber.d("Skipping ${poi.name}: cap of ${userPreferences.maxNotificationsPerHour} narrations/hour reached")
            return
        }

        try {
            // Set as current POI
            currentPoi = poi

            // Shorten facts at speed: driving past leaves less time per place
            val speed = locationAwarenessService.getCurrentSpeed() ?: 0f
            val effectivePreferences = userPreferences.copy(
                contentDetailLevel = TourLogic.detailLevelFor(speed, userPreferences.contentDetailLevel)
            )

            // Get or generate content
            val content = contentService.getContentForPlace(poi, effectivePreferences)

            // Calculate content priority (category interest, user prefs,
            // rating, alert proximity, and whether there are real facts:
            // places with a story outrank bare map pins in the queue)
            val hasRichContent = content.source == TourContent.ContentSource.AI_GENERATED ||
                content.source == TourContent.ContentSource.THIRD_PARTY
            val calculatedPriority =
                TourLogic.contentPriorityFor(poi, userPreferences, priority, hasRichContent)

            // Queue for delivery; only accepted narrations count toward the cap
            if (contentService.queueContentForDelivery(content, calculatedPriority)) {
                narrationTimes.add(now)
            }

            // If nothing is being spoken or scheduled, start speaking
            if (!audioService.isSpeaking() && !isDelivering) {
                deliverNextContent()
            } else {
                // Mid-narration: surface the newly queued place as "up next"
                _currentNarration.value = _currentNarration.value?.copy(upNextTitle = upNextTitle())
            }
        } catch (e: Exception) {
            Timber.e(e, "Error generating content for ${poi.name}")
        }
    }

    /**
     * Deliver the next piece of content in the queue. [consecutiveErrors]
     * counts speech failures in the current chain: a broken or muted engine
     * fails instantly, and an unbounded retry would empty the queue in one
     * pass, so the chain gives up and leaves the rest for the next trigger.
     */
    private suspend fun deliverNextContent(consecutiveErrors: Int = 0) {
        try {
            isDelivering = true
            val content = contentService.getNextContent()
            if (content == null) {
                _currentNarration.value = null
                isDelivering = false
                return
            }

            val poi = placesRepository.getPlaceDetails(content.poiId).getOrNull()

            // Queued narrations can be overtaken by the drive. A guide
            // previews what's coming; a place that's already well behind
            // the listener is a story whose moment has passed — drop it and
            // move on (it stays unvisited, so a future pass can tell it).
            val (direction, distanceMeters) = poiGeometry(poi)
            if (TourLogic.narrationIsStale(direction, distanceMeters)) {
                Timber.d("Skipping ${content.title}: already ${distanceMeters?.toInt()}m behind")
                // A skip is not a failure; the error run carries over unchanged
                deliverNextContent(consecutiveErrors)
                return
            }

            // Set as current POI
            currentPoi = poi

            _currentNarration.value = Narration(
                poiId = poi?.placeId ?: content.poiId,
                poiName = poi?.name ?: content.title,
                category = poi?.category,
                factText = content.content,
                upNextTitle = upNextTitle(),
                sourceUrl = content.metadata["sourceUrl"]
            )
            _isNarrationPlaying.value = true

            // Speak the content, introduced like a live tour guide
            // ("On your left: Fort Point. ..."). The chain continues after
            // the flow finishes, so an interrupted utterance (stopped, or
            // flushed by a newer one) ends the chain cleanly instead of
            // leaving the delivery flag stuck.
            var outcome: AudioService.SpeakingStatus? = null
            audioService.speak(spokenNarrationFor(poi, content, direction, distanceMeters))
                .collectLatest { status ->
                    when (status) {
                        AudioService.SpeakingStatus.COMPLETED -> {
                            // Told, so nothing queues this place again for the
                            // rest of the tour however many times it re-alerts
                            contentService.markContentDelivered(content.poiId)

                            // Remember this place was narrated, with a fresh
                            // timestamp so the revisit cooldown restarts (a
                            // re-narrated place shouldn't repeat every pass)
                            poi?.let {
                                placesRepository.saveVisitedPlace(
                                    it.copy(isVisited = true, visitedDate = System.currentTimeMillis())
                                )
                                updateTripHighlight(it)
                            }
                            tripNarrationCount++
                            lastSpokenAt = System.currentTimeMillis()
                            outcome = status
                        }
                        AudioService.SpeakingStatus.ERROR -> {
                            Timber.e("Error speaking content for ${content.title}")
                            outcome = status
                        }
                        else -> {
                            // No action needed for other statuses
                        }
                    }
                }

            when (outcome) {
                AudioService.SpeakingStatus.COMPLETED -> {
                    // Breathing room before the next story: guides leave
                    // listeners time to look and talk instead of lecturing
                    // wall to wall
                    if (contentService.peekNextContent() != null) {
                        delay(TourLogic.INTER_NARRATION_PAUSE_MS)
                    }
                    // A success ends the error run
                    deliverNextContent(consecutiveErrors = 0)
                }
                AudioService.SpeakingStatus.ERROR -> {
                    val errorRun = consecutiveErrors + 1
                    if (TourLogic.shouldKeepDeliveringAfterError(errorRun)) {
                        deliverNextContent(errorRun)
                    } else {
                        // The engine isn't speaking at all. Leave the rest of
                        // the queue for the next trigger rather than burning
                        // it, and drop the card — nothing is being said.
                        Timber.w("Giving up delivery after $errorRun speech failures in a row")
                        _currentNarration.value = null
                        isDelivering = false
                    }
                }
                // Interrupted mid-utterance: whatever interrupted owns the
                // audio now; the queue waits for the next trigger
                else -> isDelivering = false
            }
        } catch (e: Exception) {
            Timber.e(e, "Error delivering content")
            isDelivering = false
        }
    }

    /**
     * Track the most memorable narrated place of the drive for the closing
     * summary's callback.
     */
    private fun updateTripHighlight(poi: PointOfInterest) {
        val score = TourLogic.highlightWorthiness(poi.category, poi.rating)
        if (score > tripHighlightScore) {
            tripHighlightScore = score
            tripHighlightName = poi.name
        }
    }

    /**
     * Watch for long quiet stretches and fill them with regional color —
     * the coach guide's "way of life" commentary about how people live in
     * the area, told between sights when there is no sight to tell.
     */
    private fun startWayOfLifeWatcher() {
        wayOfLifeJob?.cancel()
        wayOfLifeJob = serviceScope.launch {
            while (isActive) {
                delay(WAY_OF_LIFE_CHECK_INTERVAL_MS)
                try {
                    maybePlayWayOfLife()
                } catch (e: Exception) {
                    Timber.e(e, "Error in way-of-life filler")
                }
            }
        }
    }

    /**
     * Play one way-of-life segment if the stretch is quiet enough to earn
     * it. Sights always outrank filler: anything speaking, delivering, or
     * queued postpones this, and each region is told at most once per tour
     * session.
     */
    private suspend fun maybePlayWayOfLife() {
        if (!userPreferences.audioEnabled || !userPreferences.autoPlayContent) return

        val narrationBusy = audioService.isSpeaking() || isDelivering ||
            contentService.peekNextContent() != null
        val speed = locationAwarenessService.getCurrentSpeed() ?: 0f
        val now = System.currentTimeMillis()
        if (!TourLogic.shouldPlayWayOfLife(now, lastSpokenAt, lastWayOfLifeAt, speed, narrationBusy)) {
            return
        }

        // Filler is still automatic narration: it counts against the hourly
        // cap, and a cap of zero mutes it like everything else automatic
        if (!TourLogic.narrationAllowed(narrationTimes, now, userPreferences.maxNotificationsPerHour)) {
            return
        }

        val location = locationAwarenessService.getCurrentLocation() ?: return
        val region = nearbyCityApiService
            .nearbyCities(location, WAY_OF_LIFE_CITY_RADIUS_METERS)
            .firstOrNull() ?: return
        if (!narratedRegions.add(region.name)) {
            // Nearest region already covered this session; wait out a full
            // cooldown instead of re-running the lookup every pass
            lastWayOfLifeAt = now
            return
        }

        // Speed-capped detail, like place narration
        val effectivePreferences = userPreferences.copy(
            contentDetailLevel = TourLogic.detailLevelFor(speed, userPreferences.contentDetailLevel)
        )
        val content = contentService.getWayOfLifeContent(
            region.name, region.latLng, effectivePreferences
        )
        if (content == null) {
            // Undocumented region: narratedRegions remembers it, so it isn't
            // retried until the next tour session
            lastWayOfLifeAt = now
            return
        }

        // A sight may have arrived while we were fetching; it wins
        if (audioService.isSpeaking() || isDelivering ||
            contentService.peekNextContent() != null
        ) {
            return
        }

        narrationTimes.add(now)
        lastWayOfLifeAt = now
        _currentNarration.value = Narration(
            poiId = null, // regional color, not a place with details to open
            poiName = region.name,
            category = null,
            factText = content.content,
            upNextTitle = null,
            sourceUrl = content.metadata["sourceUrl"]
        )
        _isNarrationPlaying.value = true

        audioService.speak("${TourLogic.wayOfLifeIntro(region.name)} ${content.content}")
            .collectLatest { status ->
                when (status) {
                    AudioService.SpeakingStatus.COMPLETED,
                    AudioService.SpeakingStatus.ERROR -> {
                        lastSpokenAt = System.currentTimeMillis()
                    }
                    else -> {
                        // No action needed for other statuses
                    }
                }
            }

        // Hand the floor back: clear the fact card unless a real narration
        // already took it over, and if a sight queued up while the filler
        // spoke, deliver it after the usual breather
        if (_currentNarration.value?.poiId == null) {
            _currentNarration.value = null
        }
        if (!isDelivering && contentService.peekNextContent() != null) {
            delay(TourLogic.INTER_NARRATION_PAUSE_MS)
            if (!isDelivering) {
                deliverNextContent()
            }
        }
    }

    /**
     * Display name of the next queued narration, if any. Content titles are
     * stored as "About <place>"; the prefix reads poorly after "Up next:".
     */
    private fun upNextTitle(): String? {
        return contentService.peekNextContent()?.title?.removePrefix("About ")
    }

    /**
     * Where a POI currently sits relative to the listener: the quadrant
     * (only while moving — a GPS heading means nothing when parked) and the
     * straight-line distance. Used both to introduce the narration and to
     * drop narrations whose place is already behind the car.
     */
    private suspend fun poiGeometry(
        poi: PointOfInterest?
    ): Pair<TourLogic.RelativeDirection?, Float?> {
        if (poi == null) return null to null
        val location = locationAwarenessService.getCurrentLocation() ?: return null to null

        val heading = locationAwarenessService.getCurrentHeading()
        val speed = locationAwarenessService.getCurrentSpeed() ?: 0f
        val direction = if (heading != null && speed >= TourLogic.MIN_HEADING_SPEED_MPS) {
            TourLogic.relativeDirection(heading, GeoUtils.bearingDegrees(location, poi.latLng))
        } else {
            null
        }
        return direction to GeoUtils.distanceMeters(location, poi.latLng)
    }

    /**
     * Prefix the narration with where the place sits relative to the current
     * direction of travel, and roughly how far away it is — the listener
     * needs to know where to look before hearing why. A stationary user gets
     * a neutral introduction but still hears the distance, which is exactly
     * what a parked scout wants to know. When another place is already
     * queued, a short "Up next" bridge rides the same utterance so the
     * silence that follows never reads as the app breaking.
     */
    private fun spokenNarrationFor(
        poi: PointOfInterest?,
        content: TourContent,
        direction: TourLogic.RelativeDirection?,
        distanceMeters: Float?
    ): String {
        val body = if (poi == null) {
            // No place details to orient by; the title carries the name
            "${content.title}. ${content.content}"
        } else {
            val distancePhrase = distanceMeters?.let { TourLogic.distancePhrase(it) }
            "${TourLogic.narrationIntroFor(poi.name, direction, distancePhrase)} ${content.content}"
        }
        val upNext = TourLogic.upNextPhrase(upNextTitle())
        return if (upNext != null) "$body $upNext" else body
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
            .setSmallIcon(R.drawable.ic_tour)
            .setContentIntent(mainPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)

        // Add actions based on the channel type
        when (channelId) {
            NOTIFICATION_CHANNEL_SERVICE -> {
                // Basic service notification, just show stop action
                builder.addAction(R.drawable.ic_stop, "Stop Tour", stopPendingIntent)
            }
            NOTIFICATION_CHANNEL_POI_APPROACHING, NOTIFICATION_CHANNEL_POI_ARRIVED -> {
                // POI notifications, show all controls
                builder.addAction(R.drawable.ic_pause, "Play/Pause", playPausePendingIntent)
                      .addAction(R.drawable.ic_skip_next, "Next", nextPendingIntent)
                      .addAction(R.drawable.ic_stop, "Stop Tour", stopPendingIntent)
                      .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            }
            NOTIFICATION_CHANNEL_PLAYBACK -> {
                // Playback notifications, focus on playback controls
                builder.addAction(
                    if (audioService.isSpeaking()) R.drawable.ic_pause else R.drawable.ic_play_arrow,
                    if (audioService.isSpeaking()) "Pause" else "Play",
                    playPausePendingIntent
                )
                      .addAction(R.drawable.ic_skip_next, "Next", nextPendingIntent)
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
                _isNarrationPlaying.value = false
                updateNotification(
                    "Audio Paused",
                    "Paused narration for ${currentPoi?.name ?: "Unknown location"}",
                    NOTIFICATION_CHANNEL_PLAYBACK
                )
            } else {
                audioService.resume()
                _isNarrationPlaying.value = true
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
     * The narration currently being spoken, exposed so the UI can show a
     * fact card alongside the audio.
     */
    data class Narration(
        val poiId: String?,
        val poiName: String,
        val category: String?,
        val factText: String,
        val upNextTitle: String? = null,
        /** Wikipedia article URL when the fact came from there. */
        val sourceUrl: String? = null
    )

    /**
     * States for the tour mode service.
     */
    sealed class TourModeState {
        data object Inactive : TourModeState()

        /**
         * The start command was accepted but the tour isn't narrating yet:
         * settings load, TTS init and the first POI discovery take seconds.
         * A separate state from [Inactive] because the UI has to show a tour
         * in progress through that gap.
         */
        data object Starting : TourModeState()
        data class Active(val nearbyPlaces: List<PointOfInterest>) : TourModeState()
        data class Error(val message: String) : TourModeState()

        /**
         * Whether a tour is under way — starting counts. Anything asking
         * "is there a tour?" (the FAB, the geofence revival path, stop
         * requests) means this, not `is Active`.
         */
        val isRunning: Boolean
            get() = this is Starting || this is Active
    }
}
