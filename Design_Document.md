# GPS Tracker App Design Document

## Overview
The GPS Tracker is an Android application that tracks and displays the user's real-time location on a Google Map. It has been expanded to function as a city tour guide, providing information about nearby points of interest, and now includes turn-by-turn navigation. The app utilizes the device's GPS, the Google Places API, and the Google Directions API to obtain location data, nearby places, and routing information, then displays them on the map with interactive elements and voice guidance.

API keys live in `local.properties` (gitignored) as `MAPS_API_KEY`. Gradle injects the value into the manifest meta-data entry the Maps SDK reads, and into `BuildConfig.MAPS_API_KEY` for the Places SDK and web-service calls. See `app/docs/api_key_setup.md`.

Narration content comes from Wikipedia: the app geosearches for the article matching each point of interest and narrates its intro extract, caching results in Room. A template built from place details is the fallback when no article exists.

## Project Structure
- `MainActivity.kt`: Main activity class that handles the UI, location tracking, map interactions, Places Autocomplete, and navigation UI.
- `activity_main.xml`: Layout file for the main activity, including containers for map, status cards, and navigation instructions.
- `bottom_sheet_place_details.xml`: Layout for the place details bottom sheet.
- `PlaceDetailsBottomSheet.kt`: Fragment for displaying place details in a bottom sheet with TTS controls.
- `layout_turn_instruction.xml`: Layout for the turn-by-turn instruction card.
- `TurnInstructionFragment.kt`: Fragment for displaying turn-by-turn navigation instructions.
- `service/TourModeService.kt`: Foreground service for continuous location monitoring and content playback.
- `receiver/GeofenceBroadcastReceiver.kt`: Forwards geofence transitions to `TourModeService`.
- `data/service/LocationAwarenessServiceImpl.kt`: Proximity monitoring, geofencing, and adaptive intervals.
- `data/api/WikipediaApiService.kt`: Wikipedia geosearch + extracts for real POI facts.
- `util/Polyline.kt`, `util/GeoUtils.kt`, `util/RouteSampler.kt`, `util/TourLogic.kt`: Pure, unit-tested geo/tour logic.
- `di/AppModule.kt`, `di/AudioModule.kt`: Hilt modules wiring services/repositories/clients.
- `build.gradle.kts`: Project-level build configuration.
- `app/build.gradle.kts`: App-level build configuration with dependencies.

## Components

### Domain Layer

#### Models
- `PointOfInterest.kt`: Domain model representing a point of interest in the city
  ```kotlin
  data class PointOfInterest(
      val id: String = UUID.randomUUID().toString(),
      val name: String,
      val latLng: LatLng,
      val address: String,
      val category: String,
      val rating: Double? = null,
      val description: String? = null,
      val photoUrl: String? = null,
      val placeId: String? = null,
      val isVisited: Boolean = false,
      val userNotes: String? = null
  )
  ```

- `UserPreferences.kt`: Domain model representing user preferences for the tour guide
  ```kotlin
  data class UserPreferences(
      val id: String = "default",
      val audioEnabled: Boolean = true,
      val voiceSpeed: Float = 1.0f,
      val voicePitch: Float = 1.0f,
      val voiceLanguage: String = "en-US",
      val autoPlayContent: Boolean = true,
      val preferredCategories: Set<PointOfInterest.Category> = setOf(...),
      val contentDetailLevel: DetailLevel = DetailLevel.MEDIUM,
      val notifyDistance: Int = 200,
      val maxNotificationsPerHour: Int = 10,
      val prefetchContent: Boolean = true,
      val useMobileData: Boolean = false,
      val darkModeEnabled: Boolean = false
  )
  ```

- `TourContent.kt`: Domain model representing content for a point of interest
  ```kotlin
  data class TourContent(
      val id: String,
      val poiId: String,
      val title: String,
      val content: String,
      val summary: String,
      val createdAt: Date = Date(),
      val updatedAt: Date = Date(),
      val source: ContentSource = ContentSource.AI_GENERATED,
      val metadata: Map<String, String> = emptyMap(),
      val audioDuration: Int? = null,
      val language: String = "en"
  )
  ```

#### Service Interfaces
- `AudioService.kt`: Interface for text-to-speech services
  ```kotlin
  interface AudioService {
      suspend fun initialize(userPreferences: UserPreferences): Boolean
      fun speak(content: TourContent): Flow<SpeakingStatus>
      fun speak(text: String): Flow<SpeakingStatus>
      fun pause(): Boolean
      fun resume(): Boolean
      fun stop()
      fun isSpeaking(): Boolean
      fun updateVoiceSettings(preferences: UserPreferences)
      fun shutdown()
      
      enum class SpeakingStatus {
          STARTED, IN_PROGRESS, PAUSED, COMPLETED, ERROR
      }
  }
  ```

- `ContentService.kt`: Interface for AI content generation
  ```kotlin
  interface ContentService {
      fun generateContent(pointOfInterest: PointOfInterest, userPreferences: UserPreferences): Flow<ContentGenerationResult>
      suspend fun getContentForPlace(pointOfInterest: PointOfInterest, userPreferences: UserPreferences): TourContent
      suspend fun prefetchContent(pointsOfInterest: List<PointOfInterest>, userPreferences: UserPreferences)
      fun queueContentForDelivery(content: TourContent, priority: Int): Boolean
      suspend fun getNextContent(): TourContent?
      fun clearContentQueue()
      
      sealed class ContentGenerationResult {
          data class Success(val content: TourContent) : ContentGenerationResult()
          data class InProgress(val progress: Float) : ContentGenerationResult()
          data class Error(val message: String) : ContentGenerationResult()
          data object Queued : ContentGenerationResult()
      }
  }
  ```

- `LocationAwarenessService.kt`: Interface for location awareness and proximity monitoring (implemented by `LocationAwarenessServiceImpl`)
  ```kotlin
  interface LocationAwarenessService {
      fun startProximityMonitoring(detectionRadius: Int = 100): Flow<ProximityAlert>
      fun stopProximityMonitoring()
      fun isMonitoringActive(): Boolean
      suspend fun registerPointOfInterest(pointOfInterest: PointOfInterest, customRadius: Int? = null): Boolean
      suspend fun registerPointsOfInterest(pointsOfInterest: List<PointOfInterest>, customRadius: Int? = null): Int
      suspend fun unregisterPointOfInterest(pointOfInterestId: String): Boolean
      suspend fun unregisterAllPointsOfInterest(): Boolean
      suspend fun getCurrentLocation(): LatLng?
      fun getCurrentSpeed(): Float?
      suspend fun getDistanceToPointOfInterest(pointOfInterest: PointOfInterest): Float?

      data class ProximityAlert(
          val pointOfInterest: PointOfInterest,
          val distance: Float,
          val estimatedTimeToReach: Long? = null,
          val alertType: AlertType
      )

      enum class AlertType { APPROACHING, NEARBY, ARRIVED, DEPARTING }
  }
  ```

- `PlacesService.kt`: Present for future expansion of place discovery; not currently used by `TourModeService`.

- `NavigationService.kt`: Interface for navigation functionality
  ```kotlin
  interface NavigationService {
      fun startNavigation(destination: LatLng, waypoints: List<LatLng> = emptyList()): Flow<NavigationStatus>
      suspend fun getCurrentRoute(): List<LatLng>
      suspend fun getEstimatedTimeOfArrival(): Long
      fun getRemainingDistance(): Float
      suspend fun getNextInstruction(): NavigationInstruction?
      fun getManeuverDetails(instruction: NavigationInstruction): ManeuverDetails
      fun determineAnnouncementTiming(distanceToManeuver: Float): AnnouncementTiming
      fun stopNavigation()
      fun isNavigating(): Boolean
      suspend fun geocodeAddress(address: String): LatLng?
      
      data class NavigationStatus(
          val isActive: Boolean,
          val currentLocation: LatLng,
          val distanceRemaining: Float,
          val timeRemaining: Long,
          val nextInstruction: NavigationInstruction?,
          val announcementTiming: AnnouncementTiming = AnnouncementTiming.NONE
      )
      
      data class NavigationInstruction(
          val type: InstructionType,
          val distance: Float,
          val description: String,
          val maneuverPoint: LatLng
      )
      
      data class ManeuverDetails(
          val visualIcon: String,
          val visualColor: Int,
          val soundCue: String,
          val primaryInstruction: String,
          val secondaryInstruction: String
      )
      
      enum class AnnouncementTiming {
          NONE, ADVANCE, APPROACHING, IMMEDIATE, PASSED
      }
      
      enum class InstructionType { 
          STRAIGHT, TURN_LEFT, TURN_RIGHT, TURN_SLIGHT_LEFT, TURN_SLIGHT_RIGHT,
          TURN_SHARP_LEFT, TURN_SHARP_RIGHT, ROUNDABOUT, MERGE, HIGHWAY_EXIT,
          ARRIVE, DEPART, OTHER
      }
  }
  ```

#### Repositories (Interfaces)
- `PlacesRepository.kt`: Interface defining operations for accessing place data
  ```kotlin
  interface PlacesRepository {
      fun getNearbyPlaces(center: LatLng, radius: Int = 500): Flow<List<PointOfInterest>>
      suspend fun getPlacesAlongRoute(route: List<LatLng>, searchRadius: Int = 500): List<PointOfInterest>
      suspend fun getPlaceDetails(placeId: String): Result<PointOfInterest>
      suspend fun saveVisitedPlace(pointOfInterest: PointOfInterest): Result<Unit>
      fun getVisitedPlaces(): Flow<List<PointOfInterest>>
  }
  ```
  `getPlacesAlongRoute` samples points along the route polyline (`RouteSampler`), runs a nearby search around each sample, and de-duplicates by place ID — POIs come back in route order.

### Data Layer

#### API Services
- `PlacesApiService.kt`: Service that interacts with Google Places API
  ```kotlin
  class PlacesApiService(placesClient: PlacesClient, httpClient: OkHttpClient, apiKey: String) {
      suspend fun getNearbyPlaces(center: LatLng, radius: Int): List<PointOfInterest>  // Nearby Search web service
      suspend fun getPlaceDetails(placeId: String): PointOfInterest                    // Places SDK fetchPlace
  }
  ```
  Nearby discovery uses the Places Nearby Search web service so any point can be searched (sampled route points, not just the device position). Results are filtered to tour-worthy categories, and photo references become fetchable photo URLs.
- `WikipediaApiService.kt`: Real facts for narration
  ```kotlin
  class WikipediaApiService(httpClient: OkHttpClient) {
      suspend fun findArticleFor(name: String, location: LatLng, radiusMeters: Int, allowNearestFallback: Boolean): WikiArticle?
      suspend fun geoSearch(location: LatLng, radiusMeters: Int, limit: Int): List<GeoSearchResult>
      suspend fun fetchExtract(pageId: Long): String?
  }
  ```
  Articles are matched to POIs by word overlap between POI name and article title; landmark-like categories may fall back to the nearest article within 100m.

#### Service Implementations
- `AudioServiceImpl.kt`: Implementation of AudioService using Android's TextToSpeech
  ```kotlin
  class AudioServiceImpl @Inject constructor(
      private val context: Context
  ) : AudioService {
      private var textToSpeech: TextToSpeech? = null
      private var isInitialized = false
      private var currentUtteranceId: String? = null
      
      override suspend fun initialize(userPreferences: UserPreferences): Boolean { ... }
      override fun speak(content: TourContent): Flow<AudioService.SpeakingStatus> { ... }
      override fun speak(text: String): Flow<AudioService.SpeakingStatus> = callbackFlow { ... }
      override fun pause(): Boolean { ... }
      override fun resume(): Boolean { ... }
      override fun stop() { ... }
      override fun isSpeaking(): Boolean { ... }
      override fun updateVoiceSettings(preferences: UserPreferences) { ... }
      override fun shutdown() { ... }
  }
  ```

- `ContentServiceImpl.kt`: Implementation of ContentService backed by Wikipedia
  ```kotlin
  class ContentServiceImpl(
      private val wikipediaApiService: WikipediaApiService,
      private val tourContentDao: TourContentDao
  ) : ContentService {
      private val deliveryQueue = ContentDeliveryQueue() // thread-safe priority queue
      // getContentForPlace: Room cache → Wikipedia article → template fallback
      // Full text is cached untrimmed; served trimmed to the user's DetailLevel
  }
  ```
  `TourContentRepository` is now a thin facade over `ContentService` for the UI layer (the old duplicate mock generator was removed).

- `NavigationServiceImpl.kt`: Implementation of NavigationService using Google Directions API and location updates
  ```kotlin
  class NavigationServiceImpl @Inject constructor(private val context: Context) : NavigationService {
      override fun startNavigation(destination: LatLng, waypoints: List<LatLng>): Flow<NavigationService.NavigationStatus> { /*...*/ }
      override suspend fun getCurrentRoute(): List<LatLng> { /*...*/ }
      override suspend fun getEstimatedTimeOfArrival(): Long { /*...*/ }
      override fun getRemainingDistance(): Float { /*...*/ }
      override suspend fun getNextInstruction(): NavigationService.NavigationInstruction? { /*...*/ }
      override fun getManeuverDetails(instruction: NavigationService.NavigationInstruction): NavigationService.ManeuverDetails { /*...*/ }
      override fun determineAnnouncementTiming(distanceToManeuver: Float): NavigationService.AnnouncementTiming { /*...*/ }
      override fun stopNavigation() { /*...*/ }
      override fun isNavigating(): Boolean { /*...*/ }
      override suspend fun geocodeAddress(address: String): LatLng? { /*...*/ }
      
      private suspend fun getRouteFromDirectionsApi(origin: LatLng, destination: LatLng, waypoints: List<LatLng>): RouteResult? { /*...*/ }
      private fun decodePolyline(encoded: String): List<LatLng> { /*...*/ }
      private fun setupLocationUpdates(emitStatus: (NavigationService.NavigationStatus) -> Unit) { /*...*/ }
      private fun stopLocationUpdates() { /*...*/ }
      private fun updateNavigation(newLocation: LatLng, emitStatus: (NavigationService.NavigationStatus) -> Unit) { /*...*/ }
      private fun findClosestPointOnRoute(location: LatLng, route: List<LatLng>): Int { /*...*/ }
      private fun updateNextInstructionBasedOnRoute(/* ... */): NavigationService.NavigationInstruction? { /*...*/ }
      private suspend fun getCurrentLocation(): Location? { /*...*/ }
      private fun isNetworkAvailable(): Boolean { /*...*/ }
      private fun calculateDistance(start: LatLng, end: LatLng): Float { /*...*/ }
      private fun isValidApiKey(apiKey: String): Boolean { /*...*/ }
  }
  ```

#### Database
- `AppDatabase.kt`: Room database configuration
  ```kotlin
  @Database(
      entities = [PointOfInterestEntity::class],
      version = 1,
      exportSchema = false
  )
  @TypeConverters(LatLngConverter::class)
  abstract class AppDatabase : RoomDatabase() {
      abstract fun pointOfInterestDao(): PointOfInterestDao
      
      companion object {
          @Volatile
          private var INSTANCE: AppDatabase? = null
          
          fun getDatabase(context: Context): AppDatabase { ... }
      }
  }
  ```

- `LatLngConverter.kt`: Type converter for storing LatLng objects in Room
  ```kotlin
  class LatLngConverter {
      @TypeConverter
      fun fromLatLng(latLng: LatLng?): String? { ... }

      @TypeConverter
      fun toLatLng(latLngString: String?): LatLng? { ... }
  }
  ```

### Presentation Layer

#### ViewModels
- `PlacesViewModel.kt`: ViewModel for managing places and points of interest data
  ```kotlin
  @HiltViewModel
  class PlacesViewModel @Inject constructor(
      private val placesRepository: PlacesRepository,
      private val userPreferencesRepository: UserPreferencesRepository,
      private val tourContentRepository: TourContentRepository,
      private val audioService: AudioService
  ) : ViewModel() {
      // LiveData properties
      val nearbyPlaces: LiveData<List<PointOfInterest>>
      val selectedPlace: LiveData<PointOfInterest?>
      val visitedPlaces = placesRepository.getVisitedPlaces().asLiveData()
      val userPreferences = userPreferencesRepository.userPreferencesFlow.asLiveData()
      val selectedPlaceContent: LiveData<TourContent?>
      val speakingStatus: LiveData<AudioService.SpeakingStatus?>
      val contentGenerationStatus: LiveData<TourContentRepository.ContentGenerationResult?>
      val isLoading: LiveData<Boolean>
      val error: LiveData<String?>
      
      // Functions
      fun fetchNearbyPlaces(radius: Int = 500) { ... }
      fun selectPlace(placeId: String) { ... }
      fun markPlaceAsVisited(pointOfInterest: PointOfInterest) { ... }
      fun addUserNotes(pointOfInterest: PointOfInterest, notes: String) { ... }
      private suspend fun loadContentForSelectedPlace() { ... }
      fun generateContentForSelectedPlace() { ... }
      fun speakSelectedPlaceContent() { ... }
      fun speakText(text: String) { ... }
      fun pauseSpeaking() { ... }
      fun resumeSpeaking() { ... }
      fun stopSpeaking() { ... }
      fun updateUserPreferences(userPreferences: UserPreferences) { ... }
      fun updateAudioSettings(...) { ... }
      fun clearError() { ... }
      override fun onCleared() { ... }
  }
  ```

#### Application Class
- `GPSTrackerApplication.kt`: Main application class that handles app-level initialization
  ```kotlin
  @HiltAndroidApp
  class GPSTrackerApplication : Application(), OnMapsSdkInitializedCallback {
      override fun onCreate() {
          super.onCreate()
          if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
          val apiKey = ApiKeyManager.getInstance(this).getGoogleMapsApiKey()
          MapsInitializer.setApiKey(apiKey)
          MapsInitializer.initialize(applicationContext, Renderer.LATEST, this)
      }
      override fun onMapsSdkInitialized(renderer: Renderer) {
          when (renderer) {
              Renderer.LATEST -> Timber.d("Using the latest Maps renderer")
              Renderer.LEGACY -> Timber.d("Using the legacy Maps renderer")
          }
      }
  }
  ```

#### Services
- `TourModeService.kt`: Foreground service for continuous location monitoring and content playback
  ```kotlin
  @AndroidEntryPoint
  class TourModeService : Service() {
      @Inject lateinit var locationAwarenessService: LocationAwarenessService
      @Inject lateinit var placesRepository: PlacesRepository
      @Inject lateinit var contentService: ContentService
      @Inject lateinit var audioService: AudioService
      private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
      private val _serviceState = MutableStateFlow<TourModeState>(TourModeState.Inactive)
      val serviceState: StateFlow<TourModeState> = _serviceState
      fun startTourMode(preferences: UserPreferences = UserPreferences()) { /* implemented */ }
      fun stopTourMode() { /* implemented */ }
      override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int { /* implemented */ }
      private fun createNotificationChannel() { /* implemented */ }
      sealed class TourModeState {
          data object Inactive : TourModeState()
          data class Active(val nearbyPlaces: List<PointOfInterest>) : TourModeState()
          data class Error(val message: String) : TourModeState()
      }
  }
  ```

#### Activities and Fragments
- `MainActivity.kt`: Main activity that handles the map, location tracking, and tour mode
  - Key Properties:
    - `mMap`: Google Map instance
    - `fusedLocationClient`: For location services
    - `locationCallback`: For handling location updates
    - `placesClient`: For accessing Places API
    - `placesViewModel`: ViewModel for places data
    - `tourModeService`: Reference to the bound TourModeService
    - `isTourModeActive`: Flag indicating if tour mode is active

  - Key Functions:
    - `onCreate()`: Sets up the UI, initializes services, and sets up observers
    - `onMapReady()`: Called when the map is ready, sets up the map and registers a callback for when the map is fully loaded to ensure proper rendering
    - `enableMyLocation()`: Handles location permissions
    - `checkBackgroundLocationPermission()`: Checks if background location permission is granted
    - `requestBackgroundLocationPermission()`: Requests background location permission
    - `startLocationUpdates()`: Requests location updates
    - `updateLocationOnMap()`: Updates the map with new location and uses callbacks to ensure map tiles render properly
    - `fetchNearbyPlaces()`: Triggers fetching of nearby places
    - `displayPointsOfInterest()`: Displays POIs on the map
    - `onMarkerClick()`: Handles marker clicks to show place details
    - `showPlaceDetailsBottomSheet()`: Shows the place details in a bottom sheet
    - `startTourMode()`: Starts the tour mode service
    - `stopTourMode()`: Stops the tour mode service
    - `updateTourModeUI()`: Updates the UI based on tour mode state
    - `observeTourModeServiceState()`: Observes state changes from the service

### Tour Mode Implementation
The app now includes a tour mode feature that continues to monitor location and provide audio narration even when the app is in the background:

1. **TourModeService**: A foreground service that continues location tracking when the app is in the background
   - Uses multiple notification channels (service status, approaching, arrived, playback controls)
   - Maintains a StateFlow to communicate its state back to the UI
   - Uses `LocationAwarenessService` to monitor proximity and geofences; dynamically adjusts geofence radius based on speed
   - Generates/prefetches content and manages audio playback for POIs

2. **Service Binding**: MainActivity binds to the TourModeService to control and monitor its state
   - Implemented through a ServiceConnection and Binder pattern
   - Allows two-way communication between Activity and Service

3. **Tour Mode UI**: Added UI elements to control the tour mode
   - FloatingActionButton to start/stop tour mode
   - Status card to show current tour mode state
   - Stop button to exit tour mode
   - Settings button for future tour mode customization

4. **Permission Handling**: Enhanced permission handling to include background location (Android 10+) and notifications (Android 13+)
   - `ACCESS_BACKGROUND_LOCATION` permission requested for tour mode
   - User-friendly explanations for why permissions are needed
   - Permission request flows that align with Android guidelines

### Flow of Execution

#### Location Tracking and Map Display
1. `MainActivity.onCreate()` initializes components including the map and location services
2. `onMapReady()` is triggered when the map is initialized
3. `enableMyLocation()` checks and requests location permissions
4. `startLocationUpdates()` begins tracking the user's location
5. `locationCallback` receives location updates and calls `updateLocationOnMap()`
6. `updateLocationOnMap()` updates the map with the user's current location
7. On first location update, `fetchNearbyPlaces()` is called

#### Places Discovery and Display
1. `fetchNearbyPlaces()` in MainActivity calls `placesViewModel.fetchNearbyPlaces()`
2. PlacesViewModel delegates to PlacesRepository via `getNearbyPlaces()`
3. PlacesRepositoryImpl calls `getNearbyPlacesFromApi()` which uses the Places API
4. PlacesViewModel updates the `nearbyPlaces` LiveData
5. MainActivity observes `nearbyPlaces` and calls `displayPointsOfInterest()`
6. `displayPointsOfInterest()` adds markers for each point of interest on the map

#### Place Detail Interaction
1. User taps a marker, triggering `onMarkerClick()`
2. `onMarkerClick()` calls `placesViewModel.selectPlace()` with the place ID
3. PlacesViewModel calls `placesRepository.getPlaceDetails()`
4. PlacesRepositoryImpl first checks the local database via `pointOfInterestDao.getPointOfInterestById()`
5. If not found locally, calls `getPlaceDetailsFromApi()`
6. PlacesViewModel updates the `selectedPlace` LiveData
7. `onMarkerClick()` also calls `showPlaceDetailsBottomSheet()`
8. PlaceDetailsBottomSheet observes `selectedPlace` and calls `updateUI()`
9. `updateUI()` displays the place details in the bottom sheet
10. User can mark a place as visited or add notes, which update the database

#### Tour Mode Activation
1. User taps the Tour Mode FAB, triggering `startTourMode()`
2. `startTourMode()` checks for background location permission using `checkBackgroundLocationPermission()`
3. If permission is not granted, calls `requestBackgroundLocationPermission()`
4. After permissions are granted, creates and starts the TourModeService
5. Service creates a notification channel and starts a foreground service
6. Service begins location tracking in the background using `startLocationUpdates()`
7. MainActivity binds to the service and observes its state with `observeTourModeServiceState()`
8. Service updates its state to Active, which MainActivity observes and updates UI accordingly

#### Tour Mode Operation
1. TourModeService continuously monitors location in the background
2. When a point of interest is detected nearby, content is generated or retrieved from cache
3. Audio narration begins via AudioService
4. User can control audio playback via the notification or UI
5. Tour continues until the user stops it by tapping Stop or the Tour Mode FAB

#### **Navigation Flow (New/Updated)**
1.  User taps Navigation FAB or selects a place from the map/details.
2.  User can search destinations via Places Autocomplete ActivityResult launcher, or `startNavigationToPlace()` if a place is already selected.
3.  User selects a destination from Autocomplete, or enters manually.
4.  `MainActivity.startNavigation()` or `startNavigationToPlace()` is called.
5.  `NavigationService.geocodeAddress()` may be called if input is an address string.
6.  `MainActivity.startActiveNavigation()` is called with destination `LatLng`.
    -   Sets `isNavigating` flag to true.
    -   Hides destination input, shows navigation status card.
    -   Sets up the "Start/End Navigation" button.
    -   Launches a coroutine to collect from `navigationService.startNavigation()`.
7.  `NavigationServiceImpl.startNavigation()`:
    -   Gets current location.
    -   Calls `getRouteFromDirectionsApi()` to fetch route and instructions.
    -   Updates internal state (`navigationState`).
    -   Emits initial `NavigationStatus`.
    -   Calls `setupLocationUpdates()` to start receiving location updates.
8.  `MainActivity` receives the `NavigationStatus` flow:
    -   Calls `updateNavigationStatus()` to update the bottom card (ETA, distance).
    -   Calls `drawRouteFromNavigationService()` to draw the polyline on the map.
    -   Calls `updateCameraForNavigation()` to adjust map view (bearing, tilt).
    -   Calls `showNextInstruction()` if an instruction is available.
9.  `MainActivity.showNextInstruction()`:
    -   Calls `navigationService.getManeuverDetails()` to get display info.
    -   Calls `showTurnInstructionFragment()` to display/update the top instruction card via `TurnInstructionFragment.updateInstruction()`.
    -   If announcement timing is `IMMEDIATE` or `APPROACHING`, calls `formatInstructionForVoice()` and then `placesViewModel.speakText()` to trigger voice prompt via `AudioService`.
10. `NavigationServiceImpl.locationCallback` receives location updates:
    -   Calls `updateNavigation()` which recalculates distance, ETA, finds the next instruction (`updateNextInstructionBasedOnRoute`), determines announcement timing, and emits a new `NavigationStatus`.
11. Steps 8-10 repeat as the user progresses along the route.
12. User taps "End Navigation" button -> `MainActivity.stopNavigation()` is called.
    -   Clears UI elements (route, markers, instruction card).
    -   Calls `navigationService.stopNavigation()`.
    -   Clears location history.
13. `NavigationServiceImpl.stopNavigation()`:
    -   Calls `stopLocationUpdates()`.
    -   Resets internal state.

## Current Implementation Status

### Completed Features
1. **Core GPS Tracking**
   - Real-time location tracking
   - Map display with current location marker
   - Permissions handling
   - ✓ Map renderer optimization for improved tile rendering quality

2. **Places API Integration (Partial)**
   - Discovering nearby points of interest
   - Displaying POIs on map with custom markers
   - Basic place information retrieval
   - ✓ Places API initialization with API key

3. **Interactive UI Components**
   - Bottom sheet for displaying place details
   - Mark places as visited functionality
   - Add notes to places functionality

4. **Local Storage**
   - Room database for storing visited places
   - Database entity and DAO implementation
   - Type converters for complex objects

5. **Dependency Injection**
   - ✓ Dagger/Hilt integration implemented
   - ✓ Module setup for providing dependencies

6. **TourModeService Framework**
   - ✓ Foreground service implementation
   - ✓ Service binding with MainActivity
   - ✓ ServiceState flow for state monitoring
   - ✓ Tour mode UI controls in MainActivity
   - ✓ Android 14 (API 34) permission compatibility

7. **Maps SDK Integration**
   - ✓ Using latest Google Maps renderer for improved performance and visuals
   - ✓ Proper map loading callbacks to ensure tiles render at full quality
   - ✓ CameraUpdate callbacks to refresh tiles after camera movements
   - ✓ Compatible with Google's automatic renderer updates for Maps SDK

8. **Text-to-Speech Implementation**
   - ✓ Basic AudioService implementation for TTS
   - ✓ Voice setting controls in UI
   - ✓ Play/pause/stop functionality in bottom sheet
   - ✓ Automatic content narration during driving
   - ✓ Audio interruption handling

9. **Content Generation**
   - ✓ Basic mock content generation in ContentServiceImpl
   - ✓ Content display in place details
   - ✓ Integration with real AI services
   - ✓ Automatic content triggering for nearby places 
   - ✓ Content prefetching

10. **Turn-by-Turn Navigation (Core)**
    -   Route fetching and polyline drawing.
    -   Step-by-step instruction parsing.
    -   Visual turn instruction card (`TurnInstructionFragment`).
    -   Basic voice guidance integration via `AudioService`.
    -   Camera following with bearing/tilt during navigation.
    -   ETA and distance remaining display.

### Partially Implemented Features
1. **Places API Integration**
   -   ✓ Basic nearby places discovery
   -   ✓ Improved place detail retrieval with buildDescription method
   -   ✓ Better error handling with API-specific error messages
   -   ❌ Advanced filtering/ranking based on user prefs not fully implemented.
2. **Tour Mode Functionality**
   -   ✓ Basic tour mode service structure
   -   ✓ UI for tour mode activation/deactivation
   -   ✓ POI detection via Geofencing/Proximity Alerts
   -   ✓ Basic content queuing and playback triggering
   -   ❌ Sophisticated interruption handling (e.g., during navigation) not implemented.
   -   ✓ Dynamic geofence radius based on speed implemented and applied when registering POIs.
3. **Navigation Enhancements**
   -   ✓ Core turn-by-turn implemented.
   -   ❌ Route recalculation on deviation not implemented.
   -   ❌ Advanced route visualization (traffic, arrows) not implemented.
   -   ❌ Lane guidance, junction views not implemented.

### Pending Implementation
1. **Full Automatic Tour Guide Functionality Refinement**
   -   Refine POI detection logic in TourModeService.
   -   Improve interruption handling between tour audio and navigation prompts.
2. **Navigation Enhancement**
   -   Implement route recalculation when off-route.
   -   Add advanced visualization (traffic, maneuver arrows).
   -   Consider lane guidance and junction views.
   -   Route planning with interesting waypoints (`planRouteWithInterestingPlaces`).
3. **Real AI Content Integration**
   -   Connect to real AI services.
   -   Implement robust content caching.
   -   Personalize content further.
4. **User Experience Refinement**
   -   Design history view.
   -   Complete settings UI.
   -   Add feedback mechanisms.
   -   Implement tour history tracking.
5. **Performance and Battery Optimization**
   -   Optimize location polling.
   -   Refine content prefetching strategy.
   -   Optimize background processing.

## Dependencies
- Google Play Services Maps: For displaying the map
- Google Play Services Location: For accessing location services
- Google Places API: For points of interest data
- AndroidX Core KTX: Kotlin extensions for Android core libraries
- AndroidX AppCompat: Backward compatibility for newer Android features
- Material Design: UI components following Material Design guidelines
- Room: Local database storage
- Kotlin Coroutines: Asynchronous programming
- StateFlow/SharedFlow: For reactive streams
- Gson: JSON parsing
- Dagger Hilt: Dependency injection
- Timber: Improved logging

## Permission Requirements
- `ACCESS_FINE_LOCATION`: For precise GPS location
- `ACCESS_COARSE_LOCATION`: For approximate network-based location
- `ACCESS_BACKGROUND_LOCATION`: For location tracking in background
- `FOREGROUND_SERVICE`: For running services in the foreground
- `FOREGROUND_SERVICE_LOCATION`: For foreground services that access location (Android 14+)
- `POST_NOTIFICATIONS`: For displaying notifications (Android 13+)

## Implementation Plan (Updated)

### Phase 1: Foundation and Architecture Setup (✓ Completed)
- Refactored code to implement clean architecture pattern
- Created domain layer with models and interfaces
- Established data layer with repository implementations
- Set up presentation layer with ViewModels
- Implemented Dagger Hilt for dependency injection
- ✓ Added Google Maps SDK latest renderer integration

### Phase 2: Location and Places Integration (✓ Completed)
- ✓ Implemented Google Places API integration
- ✓ Created database for local storage
- ✓ Developed interactive UI components
- ✓ Enhanced map rendering quality through proper loading callbacks
- ✓ Implemented place details viewing and interaction
- ✓ Completed the getPlaceDetails API implementation
- ✓ Improved error handling in Places API interactions

### Phase 3: Audio and Content Implementation (✓ Completed)
- ✓ AudioService for TTS implemented
- ✓ UI controls for audio playback added
- ✓ Mock content generation implemented
- ✓ Manual content triggering implemented

### Phase 4: Automatic Tour Guide & Navigation Functionality (✓ Largely Completed)
-   ✓ Created TourModeService, binding, state management.
-   ✓ Added tour mode UI controls, permissions.
-   ✓ Fixed foreground service start issue.
-   ✓ Implemented core turn-by-turn navigation (visual & basic voice).
-   ✓ Implemented camera following with bearing/tilt.
-   ❌ Automatic POI detection refinement needed.
-   ❌ Intelligent interruption handling between tour/nav audio needed.
-   ❌ Route recalculation not implemented.

### Phase 5: Content Enhancement (⏳ Pending)
- Implement AI service integration
- Create content management system
- Develop content personalization based on preferences
- Add content prefetching for smooth experience

### Phase 6: Advanced Navigation & Final Features (⏳ Pending)
- Implement turn-by-turn navigation
- Create history and statistics views
- Add final UI polish and optimizations

## Key Interactions Between Components

### MainActivity and TourModeService
- Service is started with `startService(intent)` and bound with `bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)`
- MainActivity observes service state changes via `tourModeService?.serviceState?.collectLatest`
- Service communicates state changes through StateFlow
- MainActivity updates UI based on service state via `updateTourModeUI()`

### AudioService and TextToSpeech
- AudioService wraps Android's TextToSpeech engine
- Initialization occurs in `initialize()` with user preferences
- Speaking status is communicated through Flow via `speak()` methods
- UtteranceProgressListener reports TTS progress through Flow emissions

### PlacesViewModel and Repositories
- ViewModel acts as a mediator between UI and data sources
- Coordinates interactions between PlacesRepository, TourContentRepository, and UserPreferencesRepository
- Maintains LiveData for UI observations
- Handles background coroutines for data operations

### ContentService and TourModeService
- TourModeService will trigger content generation for nearby POIs
- ContentService manages content queue and generation
- AudioService plays the content when triggered

### PlacesApiService and Error Handling
- Implements proper error handling for API calls using suspendCancellableCoroutine
- Maps API-specific error codes to user-friendly messages
- Provides context-specific error handling for network issues

### MainActivity and NavigationService
- `MainActivity` injects `NavigationService`.
- `MainActivity` calls `navigationService.startNavigation()` to begin and collects the resulting `Flow<NavigationStatus>`.
- `MainActivity` calls `navigationService.getCurrentRoute()` to get points for drawing the polyline.
- `MainActivity` calls `navigationService.getManeuverDetails()` to populate the `TurnInstructionFragment`.
- `MainActivity` calls `navigationService.stopNavigation()` to end guidance.
- `MainActivity` calls `navigationService.geocodeAddress()` to convert addresses.

### MainActivity and TurnInstructionFragment
- `MainActivity` creates and displays `TurnInstructionFragment` in the `turn_instruction_container` FrameLayout.
- `MainActivity` calls `turnInstructionFragment.updateInstruction()` to update the displayed maneuver.
- `MainActivity` implements `TurnInstructionFragment.NavigationDetailsProvider` to provide maneuver details (by calling `navigationService.getManeuverDetails()`).
- `MainActivity` implements `TurnInstructionFragment.NavigationInstructionController` to handle the fragment's close request (`hideTurnInstructions()`).
- `TurnInstructionFragment` calls `activity.hideTurnInstructions()` when its close button is clicked.

### PlacesViewModel and AudioService
- `PlacesViewModel` provides the `speakText()` function used by `MainActivity` to request voice prompts.
- `AudioService` handles TTS synthesis and audio focus management.

### NavigationServiceImpl and Location Updates
- `NavigationServiceImpl` uses `FusedLocationProviderClient` to receive location updates via `locationCallback`.
- `locationCallback` triggers `updateNavigation()` which recalculates state and emits new `NavigationStatus`.

### NavigationServiceImpl and Directions API
- `NavigationServiceImpl.getRouteFromDirectionsApi()` uses `OkHttpClient` to call the Google Directions API.
- Parses JSON response to extract route polyline and step-by-step instructions (`NavigationInstruction`).

### GeofenceBroadcastReceiver and TourModeService
- `GeofenceBroadcastReceiver` receives geofence events.
- Calls `context.startService()` with `ACTION_PROCESS_GEOFENCE` intent for `TourModeService`. (Changed from `startForegroundService`)
- `TourModeService.onStartCommand()` handles this action, ensuring it calls `startForeground()` if necessary before processing the event via `handleGeofenceEvent()`. This prevents the `ForegroundServiceDidNotStartInTimeException`.

### AudioService and AudioManager
- `AudioServiceImpl` uses `AudioManager` to request and manage audio focus (`requestAudioFocus`, `releaseAudioFocus`, `OnAudioFocusChangeListener`).
- Pauses/stops TTS on focus loss, resumes on focus gain.

## Future Implementation Notes
1. POI detection in TourModeService should use geofencing for efficiency
2. Audio playback should consider device state (e.g., calls, other media)
3. Content generation should have a caching strategy to minimize API costs
4. Tour history should be persisted for review and statistics
5. Battery optimization is critical for background operation 

## Navigation Implementation Analysis (Revised)

### Current Navigation Architecture
The application now implements a more complete turn-by-turn navigation system:

1.  **NavigationService Interface & Implementation (`NavigationServiceImpl`)**
    -   Fetches routes and instructions using Google Directions API.
    -   Manages navigation state (`NavigationState` internal class).
    -   Provides detailed maneuver information (`ManeuverDetails`) and announcement timing (`AnnouncementTiming`).
    -   Uses `FusedLocationProviderClient` for location updates.
    -   Decodes polylines and calculates distances/ETAs.
    -   Handles API key validation and basic error handling.
    -   Includes geocoding via Android `Geocoder`.

2.  **MainActivity Navigation Integration**
    -   Initiates navigation via Places Autocomplete or manual input.
    -   Collects `NavigationStatus` updates from `NavigationService`.
    -   Draws the route polyline (`drawRouteFromNavigationService`).
    -   Updates camera position, bearing, and tilt (`updateCameraForNavigation`, `getUserBearing`).
    -   Manages the `TurnInstructionFragment` (`showTurnInstructionFragment`, `hideTurnInstructions`).
    -   Triggers voice prompts via `PlacesViewModel` -> `AudioService`.
    -   Displays ETA and distance remaining in a status card (`updateNavigationStatus`).
    -   Handles start/stop navigation actions.

3.  **TurnInstructionFragment & Layout**
    -   Dedicated UI component (`layout_turn_instruction.xml`) displaying maneuver icon, primary/secondary instructions, distance, street name (if available), and progress bar.
    -   `TurnInstructionFragment.kt` receives data from `MainActivity` and updates the UI.
    -   Includes a close button handled via `NavigationInstructionController` interface implemented by `MainActivity`.

4.  **PlacesViewModel & AudioService**
    -   `PlacesViewModel` provides the `speakText()` function used by `MainActivity` to request voice prompts.
    -   `AudioService` handles TTS synthesis and audio focus management.

### Identified Issues and Recommended Fixes (Updated based on Implementation)

1.  **Google Directions API Key Handling**: (✓ Improved)
    -   API key is set programmatically via `ApiKeyManager` using an encrypted resource; `NavigationServiceImpl` validates basic format with `isValidApiKey`.
    -   *Recommendation*: Monitor API usage and ensure key restrictions are properly set in Google Cloud Console.

2.  **Geocoding Implementation**: (✓ Improved)
    -   `geocodeAddress` is now correctly part of the `NavigationService` interface, removing the need for casting in `MainActivity`.

3.  **Route Drawing Logic**: (✓ Improved)
    -   Route is drawn using the polyline points decoded from the Directions API response (`drawRouteFromNavigationService`).
    -   Fallback to straight line if API fails is still present in `startNavigation`'s error handling within `NavigationServiceImpl` but primary flow uses API route.
    -   *Recommendation*: Further improve error handling in `getRouteFromDirectionsApi` to provide more specific feedback if route fetching fails (e.g., "Route not found", "Network error").

4.  **Inconsistent Navigation Start Behavior**: (✓ Improved)
    -   `startActiveNavigation` provides a common entry point.
    -   The flow now involves showing the status card, setting up the button, and *then* starting the flow collection when the user explicitly taps "Start Navigation". This seems intentional for user control.

5.  **Route Recalculation**: (❌ Missing)
    -   The system currently does not detect when the user deviates from the planned route or recalculate.
    -   *Recommendation*: Implement off-route detection (e.g., checking distance from the nearest route segment) and trigger a call back to `getRouteFromDirectionsApi` with the current location as the new origin. Update the polyline and instructions accordingly.

6.  **Voice Prompt Sophistication**: (⚠️ Partially Implemented)
    -   Basic voice prompts for immediate/approaching maneuvers are implemented.
    -   *Recommendation*: Enhance voice prompts for more complex scenarios (e.g., roundabout exits, multiple upcoming maneuvers). Improve timing logic based on speed. Add configuration options for voice prompt frequency/detail.

7.  **Error Handling & Logging**: (✓ Improved)
    -   `NavigationServiceImpl` includes more specific error handling for network/API issues.
    -   Timber logging is used throughout.
    -   *Recommendation*: Continue refining error messages shown to the user to be more actionable.

8.  **Connectivity Checks**: (✓ Implemented)
    -   `isNetworkAvailable()` check added before Directions API calls.

9.  **Test Coverage**: (❓ Unknown)
    -   *Recommendation*: Add unit tests for `NavigationServiceImpl` (especially route parsing, instruction logic, bearing calculation) and UI tests for the navigation flow in `MainActivity`.

By implementing these fixes, the application should have more reliable navigation functionality with better error handling and a more consistent user experience. 

## Resolved Issues (July 2026 rework)

- **API key management rebuilt**: The AES/ECB `ApiKeyManager` scheme could not work — the Maps SDK has no programmatic key API, and the device-derived encryption key made committed ciphertext undecryptable on other devices. Keys now live in `local.properties` (`MAPS_API_KEY`), injected into the manifest and `BuildConfig` at build time. All API key logging removed. Note: an old plaintext key exists in git history — restrict/rotate it in Cloud Console.
- **Real POI discovery**: `findCurrentPlace` (which ignored the radius and could only report the venue the device was at) was replaced with the Places Nearby Search web service. Discovery now works around any point, refreshes as the user moves (`TourModeService` rolling refresh), and covers whole navigation routes (`getPlacesAlongRoute` + `updateRouteCorridor`).
- **Real facts instead of mock content**: `ContentServiceImpl` narrates Wikipedia article intros (geosearch + title matching), cached untrimmed in Room (`tour_content` table, DB v2) and trimmed to the user's detail level at serve time. Template fallback when no article exists. The duplicate mock generator in `TourContentRepository` was removed; it now delegates to `ContentService`.
- **Navigation prompts interrupt and resume narration**: `AudioService.speakPriority()` pauses tour narration, plays the prompt, and resumes from the interrupted sentence. `AudioServiceImpl` now uses one persistent `UtteranceProgressListener` (per-call listeners used to clobber each other).
- **Double voice prompts fixed**: MainActivity spoke each instruction twice per status update (two `showNextInstruction` call sites) and re-announced on every location tick; now a single call path with per-maneuver dedup.
- **Geofence background start fixed**: `GeofenceBroadcastReceiver` uses `startForegroundService()`, and `TourModeService.onStartCommand` calls `startForeground()` immediately (plain `startService()` from the background throws on Android 8+).
- **Camera follows like Google Maps**: following disengages on user pan/zoom and re-engages via the recenter FAB; the duplicate red "You are here" marker was removed in favor of the built-in my-location dot.
- **Photo URLs fixed**: `photoUrl` was being set to photo *attributions* text; nearby search results now build real photo URLs from `photo_reference`.
- **Compile errors fixed**: nonexistent `MapsInitializer.setApiKey`, undeclared `progressEta`, invalid `.kotlinx.coroutines.flow.first()` chains, and a suspend call outside a coroutine.
- **Collector leak fixed**: `PlacesViewModel.updateAudioSettings` no longer starts an endless preferences collector per call.
- **Thread-safe content queue**: delivery queue extracted into `ContentDeliveryQueue` with synchronized access.
- **Unit tests added** (50 tests): polyline decoding, geo math, route sampling, geofence radius, content priority, delivery queue ordering, Places/Wikipedia response parsing, detail-level trimming, TTS resume-position logic.
- **Directional narration**: each narration opens with where the place sits relative to the direction of travel ("On your left: Fort Point."), like a live tour guide. `TourLogic.relativeDirection` classifies the POI bearing against the GPS heading (`LocationAwarenessService.getCurrentHeading`, trusted only above 1 m/s); a neutral "Coming up:" intro is used when stationary.
- **Fact card during narration**: `TourModeService` exposes the narration in flight as `currentNarration: StateFlow<Narration?>`; MainActivity slides up a card with the place name, category, and the full fact text (scrollable) while audio plays, and hides it when the delivery queue drains. Stacks above the navigation status card so both fit during navigation.
- **Speed-adaptive narration length**: `TourLogic.detailLevelFor` caps the content detail level by travel speed — full detail on foot (<15 km/h), medium while driving, brief at highway speed (≥80 km/h) — never exceeding the user's preferred level. Applied per delivery in `TourModeService`; safe because Room caches the untrimmed text and trimming happens at serve time.
- **The tour guide remembers**: narrated places are saved as visited (`saveVisitedPlace` on narration completion), discovery overlays stored visited state onto fresh API results (`PlacesRepositoryImpl.mergeVisitedState` — API results always arrive with `isVisited = false`), and `generateAndQueueContent` skips visited places so the guide never repeats itself across passes or sessions. Manual replay is still available from the place details sheet, and the pre-existing visited priority penalty now actually fires.
- **Category-styled markers**: `MarkerStyling` (pure, unit-tested) maps each category to a distinct marker hue and fades already-narrated places to 45% alpha.
- **Corridor and polyline follow reroutes**: `NavigationStatus.routeVersion` increments on every route (re)calculation; MainActivity re-sends the tour corridor and redraws the route polyline whenever it changes (previously both were done once per session, so off-route recalculations left a stale polyline and a stale corridor).
- **Tappable fact card**: `Narration` carries the placeId; tapping the card opens the place details sheet for the spot being narrated.
- **Tour Journal**: a journal FAB opens `TourJournalBottomSheet`, listing every narrated place newest-first (name, category, visit time) with tap-through to the details sheet. Backed by the existing visited-places store; `PointOfInterest` now carries `visitedDate`, and re-saves preserve the original visit timestamp.
- **Live narration progress**: `AudioService.speechProgress: StateFlow<Float>` reports how far the TTS engine is through the current narration (fed by `onRangeStart` character offsets, API 26+; prompts don't move it, errors reset it). The fact card shows it as a thin progress bar via `PlacesViewModel.narrationProgress`.
- **Spoken welcome and route preview**: tour mode start is confirmed out loud ("Tour mode is on. I found 12 interesting places nearby...") instead of staying silent until the first geofence fires — doubling as a TTS health check — and registering a route corridor announces "Your route passes N interesting places" (quiet when the route has none). Both respect `audioEnabled`.
- **Trip summary on arrival**: the guide closes the drive with "That concludes today's tour: you heard about N places along the way." `TourModeService` counts completed narrations per corridor (reroutes keep the count, a fresh corridor resets it) and `consumeTripSummaryPhrase()` hands the line to MainActivity, which appends it to the spoken arrival announcement so the two can't race in the TTS layer. Silent when nothing was narrated.

## Remaining TODOs

- User-facing error messages: standardize and surface actionable messages for Directions/Places failures in UI.
- UI/UX polish: accurate audio progress, cancel during route calculation, surface monitoring status (battery/speed).
- Dependency updates: Places SDK 3.3.0 → 4.x (Autocomplete `TypeFilter` and `Place.Field.NAME` are deprecated), `LocationRequest.create()` → `LocationRequest.Builder` in MainActivity.
- Production key hygiene: split SDK vs web-service API keys; proxy Directions/Places web calls through a backend.
- Instrumented tests for MainActivity flows and TourModeService.