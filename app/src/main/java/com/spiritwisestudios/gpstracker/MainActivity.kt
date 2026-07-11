package com.spiritwisestudios.gpstracker

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.net.Uri
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.IBinder
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.core.location.LocationListenerCompat
import com.spiritwisestudios.gpstracker.data.service.FrameworkLocationClient
import com.spiritwisestudios.gpstracker.domain.model.LatLng
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.Polygon
import org.maplibre.android.annotations.PolygonOptions
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng as MapLatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.OnMapReadyCallback
import org.maplibre.android.maps.Style
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.spiritwisestudios.gpstracker.data.repository.UserPreferencesRepository
import com.spiritwisestudios.gpstracker.domain.model.PointOfInterest
import com.spiritwisestudios.gpstracker.domain.model.UserPreferences
import com.spiritwisestudios.gpstracker.domain.service.NavigationService
import com.spiritwisestudios.gpstracker.service.TourModeService
import com.spiritwisestudios.gpstracker.ui.fragment.DestinationSearchBottomSheet
import com.spiritwisestudios.gpstracker.ui.fragment.MapLayersBottomSheet
import com.spiritwisestudios.gpstracker.ui.fragment.PlaceDetailsBottomSheet
import com.spiritwisestudios.gpstracker.ui.fragment.TourJournalBottomSheet
import com.spiritwisestudios.gpstracker.ui.fragment.TourSettingsFragment
import com.spiritwisestudios.gpstracker.ui.fragment.TurnInstructionFragment
import com.spiritwisestudios.gpstracker.ui.viewmodel.PlacesViewModel
import com.spiritwisestudios.gpstracker.util.AppConstants
import com.spiritwisestudios.gpstracker.util.CameraLogic
import com.spiritwisestudios.gpstracker.util.DistanceFormatter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import android.os.Looper
import com.spiritwisestudios.gpstracker.util.GeoUtils
import com.spiritwisestudios.gpstracker.util.MapStyles
import com.spiritwisestudios.gpstracker.util.MarkerIcons
import com.spiritwisestudios.gpstracker.util.MarkerStyling
import com.spiritwisestudios.gpstracker.databinding.ActivityMainBinding
import java.util.ArrayDeque

// Removed debug-time API key logger to avoid accidental key exposure

@AndroidEntryPoint
class MainActivity : AppCompatActivity(), OnMapReadyCallback, MapLibreMap.OnMarkerClickListener,
    TurnInstructionFragment.NavigationDetailsProvider, TurnInstructionFragment.NavigationInstructionController,
    MapLayersBottomSheet.MapLayersHost, DestinationSearchBottomSheet.DestinationSearchHost {

    private lateinit var mapView: MapView
    private lateinit var mMap: MapLibreMap
    private lateinit var locationClient: FrameworkLocationClient
    private lateinit var locationListener: LocationListenerCompat

    @Inject
    lateinit var userPreferencesRepository: UserPreferencesRepository

    // Use the viewModels() delegate to get the ViewModel from Hilt
    private val placesViewModel: PlacesViewModel by viewModels()

    private val poiMarkers = mutableMapOf<String, Marker>()

    // MapLibre markers have no tag slot; keyed by Marker.id instead
    private val markerPlaceIds = mutableMapOf<Long, String>()
    private val LOCATION_PERMISSION_REQUEST = 1
    private val BACKGROUND_LOCATION_PERMISSION_REQUEST = 2

    // The layers-sheet style currently applied to the map
    private var currentMapStyle = MapStyles.DEFAULT

    // The in-flight navigation work (geocoding, then status collection),
    // tracked so canceling actually stops it
    private var navigationJob: Job? = null

    // Latest GPS speed, for the speed-adaptive navigation camera
    private var lastKnownSpeedMps = 0f

    // Flag to check if this is the first location update
    private var isFirstUpdate = true

    // Last known device position (the map's built-in blue dot renders it)
    private var lastKnownLatLng: LatLng? = null

    // Camera follows the user until they pan/zoom; recenter FAB re-engages
    private var isFollowingUser = true

    // Where we last searched for POIs; refetch after moving far enough
    private var lastPoiFetchCenter: LatLng? = null

    // Outline of the area scouted via map long-press, if any
    private var scoutCircle: Polygon? = null

    // Dedup key so the same instruction isn't spoken on every location tick
    private var lastAnnouncementKey: String? = null
    
    // Tour mode service connection
    private var tourModeService: TourModeService? = null
    private var isTourModeActive = false
    
    // Tour mode UI elements
    private lateinit var fabTourMode: FloatingActionButton
    private lateinit var tourModeStatusCard: CardView
    private lateinit var tourModeTitle: TextView
    private lateinit var tourModeDescription: TextView
    private lateinit var btnStopTour: Button
    private lateinit var btnTourSettings: Button
    
    // UI elements for navigation
    private lateinit var searchBarCard: CardView
    private lateinit var destinationInputView: View
    private lateinit var etDestination: TextInputEditText
    private lateinit var btnNavigate: Button
    private lateinit var btnCloseDestination: Button
    private lateinit var navigationStatusCard: CardView
    private lateinit var tvNavigationDestination: TextView
    private lateinit var tvNavigationInfo: TextView
    
    // Navigation service
    @Inject
    lateinit var navigationService: NavigationService
    
    // Route polyline
    private var routePolyline: org.maplibre.android.annotations.Polyline? = null
    private var destinationMarker: Marker? = null
    private var isNavigating = false
    
    // Service connection for binding to the TourModeService
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as TourModeService.TourModeServiceBinder
            tourModeService = binder.getService()
            
            // Start observing service state changes
            observeTourModeServiceState()
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            tourModeService = null
            isTourModeActive = false
            updateTourModeUI(false)
        }
    }

    companion object {
        private const val POI_REFETCH_DISTANCE_METERS = 300f
        private const val SCOUT_RADIUS_METERS = 750

        // MapLibre renders 512px tiles, so its zoom levels sit one below
        // Google's for the same view; these replace the old hardcoded 18f
        // and offset CameraLogic's Google-tuned zoom curve.
        private const val FOLLOW_ZOOM = 17.0
        private const val ZOOM_OFFSET = 1f
    }

    /** Domain coordinates → MapLibre coordinates at the map boundary. */
    private fun LatLng.toMap() = MapLatLng(latitude, longitude)

    private lateinit var binding: ActivityMainBinding

    // Turn instruction fragment
    private var turnInstructionFragment: TurnInstructionFragment? = null
    
    // LocationHistory for calculating bearing
    private val locationHistory = ArrayDeque<LatLng>(5)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Timber.d("MainActivity onCreate called")

        // Initialize UI elements
        initializeUIElements()

        // Keep controls clear of the system bars: targetSdk 35 draws
        // edge-to-edge, so the 3-button nav bar otherwise covers the
        // bottom FABs and cards (gesture nav has a much smaller inset)
        applyWindowInsets()

        // Initialize the map view and request the map to be ready. The
        // MapView needs the activity lifecycle forwarded to it (see the
        // lifecycle overrides below).
        mapView = binding.map
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync(this)

        // Setup the framework location client (no Play Services)
        locationClient = FrameworkLocationClient(this)

        // Define the listener to handle location updates
        locationListener = LocationListenerCompat { location ->
            lastKnownSpeedMps = if (location.hasSpeed()) location.speed else 0f
            updateLocationOnMap(location)
            refreshNearbyPlacesIfNeeded()
        }
        
        // Set up observers
        setupObservers()
        
        // Set up click listeners
        setupClickListeners()
    }
    
    private fun initializeUIElements() {
        // Initialize the tour mode UI elements
        fabTourMode = findViewById(R.id.fab_tour_mode)
        tourModeStatusCard = findViewById(R.id.tour_mode_status)
        tourModeTitle = findViewById(R.id.tour_mode_title)
        tourModeDescription = findViewById(R.id.tour_mode_description)
        btnStopTour = findViewById(R.id.btn_stop_tour)
        btnTourSettings = findViewById(R.id.btn_tour_settings)
        
        // Initialize navigation UI elements
        searchBarCard = findViewById(R.id.search_bar_card)
        destinationInputView = findViewById(R.id.destination_input)
        etDestination = destinationInputView.findViewById(R.id.et_destination)
        btnNavigate = destinationInputView.findViewById(R.id.btn_navigate)
        btnCloseDestination = destinationInputView.findViewById(R.id.btn_close_destination)
        navigationStatusCard = findViewById(R.id.navigation_status_card)
        tvNavigationDestination = navigationStatusCard.findViewById(R.id.tv_navigation_destination)
        tvNavigationInfo = navigationStatusCard.findViewById(R.id.tv_navigation_info)
        
        // Initialize the turn instruction container
        findViewById<View>(R.id.turn_instruction_container)
    }
    
    /**
     * Add the system-bar insets to the edge-anchored controls. The map
     * itself stays edge-to-edge; only the controls move. The layout's
     * margins are treated as design margins with the inset added on top,
     * so gesture nav gets a small lift and the 3-button bar a full one.
     */
    private fun applyWindowInsets() {
        val bottomViews = listOf<View>(
            binding.fabTourMode, binding.fabRecenter,
            binding.fabLayers, binding.fabJournal, binding.bottomCards
        )
        // Everything top-anchored lives in one stacked column, so the
        // status-bar inset is applied once to the container
        val topViews = listOf<View>(binding.topCards)
        val baseBottomMargins = bottomViews.associateWith {
            (it.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
        }
        val baseTopMargins = topViews.associateWith {
            (it.layoutParams as ViewGroup.MarginLayoutParams).topMargin
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            bottomViews.forEach { view ->
                view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    bottomMargin = baseBottomMargins.getValue(view) + bars.bottom
                }
            }
            topViews.forEach { view ->
                view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    topMargin = baseTopMargins.getValue(view) + bars.top
                }
            }
            windowInsets
        }
    }

    private fun setupClickListeners() {
        // Set up click listener for the tour mode FAB
        fabTourMode.setOnClickListener {
            if (isTourModeActive) {
                stopTourMode()
            } else {
                startTourMode()
            }
        }
        
        // Set up click listener for the stop tour button
        btnStopTour.setOnClickListener {
            stopTourMode()
        }
        
        // Set up click listener for the tour settings button
        btnTourSettings.setOnClickListener {
            showTourSettings()
        }

        // Fact-card playback controls: same actions the notification uses,
        // but reachable without opening the shade while driving
        binding.btnNarrationPlayPause.setOnClickListener {
            startService(Intent(this, TourModeService::class.java).apply {
                action = AppConstants.ACTION_PLAY_PAUSE
            })
        }
        binding.btnNarrationSkip.setOnClickListener {
            startService(Intent(this, TourModeService::class.java).apply {
                action = AppConstants.ACTION_NEXT_POI
            })
        }

        // The search bar opens Places Autocomplete, like Google Maps
        searchBarCard.setOnClickListener {
            launchDestinationSearch()
        }
        
        // Set up click listener for the close destination button
        btnCloseDestination.setOnClickListener {
            hideDestinationInput()
        }
        
        // Set up click listener for the navigate button
        btnNavigate.setOnClickListener {
            val destination = etDestination.text.toString().trim()
            if (destination.isNotEmpty()) {
                startNavigation(destination)
            } else {
                Toast.makeText(this, "Please enter a destination", Toast.LENGTH_SHORT).show()
            }
        }

        // Recenter FAB re-engages camera following
        findViewById<FloatingActionButton>(R.id.fab_recenter).setOnClickListener {
            isFollowingUser = true
            lastKnownLatLng?.let { pos ->
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(pos.toMap(), FOLLOW_ZOOM))
            }
        }

        // Layers FAB: pick the map type and toggle traffic independently
        findViewById<FloatingActionButton>(R.id.fab_layers).setOnClickListener {
            MapLayersBottomSheet.newInstance()
                .show(supportFragmentManager, MapLayersBottomSheet.TAG)
        }

        // Journal FAB: every place the tour guide has narrated so far
        findViewById<FloatingActionButton>(R.id.fab_journal).setOnClickListener {
            TourJournalBottomSheet.newInstance()
                .show(supportFragmentManager, TourJournalBottomSheet.TAG)
        }

        // Set up editor action listener for the destination EditText
        etDestination.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val destination = etDestination.text.toString().trim()
                if (destination.isNotEmpty()) {
                    startNavigation(destination)
                    return@setOnEditorActionListener true
                }
            }
            return@setOnEditorActionListener false
        }
    }
    
    private fun setupObservers() {
        // Observe nearby places
        placesViewModel.nearbyPlaces.observe(this, Observer { places ->
            displayPointsOfInterest(places)
        })
        
        // Observe errors
        placesViewModel.error.observe(this, Observer { errorMessage ->
            errorMessage?.let {
                Toast.makeText(this, it, Toast.LENGTH_LONG).show()
                placesViewModel.clearError()
            }
        })

        // Fill the fact card's progress bar as the narration is spoken
        placesViewModel.narrationProgress.observe(this, Observer { fraction ->
            binding.progressNarration.progress = (fraction * 100).toInt().coerceIn(0, 100)
        })
    }
    
    private fun observeTourModeServiceState() {
        // Show a fact card while a POI is being narrated
        lifecycleScope.launch {
            tourModeService?.currentNarration?.collectLatest { narration ->
                showNarrationCard(narration)
            }
        }

        // Observe the tour mode service state using lifecycleScope
        lifecycleScope.launch {
            tourModeService?.serviceState?.collectLatest { state ->
                when (state) {
                    is TourModeService.TourModeState.Active -> {
                        isTourModeActive = true
                        updateTourModeUI(true)
                    }
                    is TourModeService.TourModeState.Inactive -> {
                        isTourModeActive = false
                        updateTourModeUI(false)
                    }
                    is TourModeService.TourModeState.Error -> {
                        isTourModeActive = false
                        updateTourModeUI(false)
                        Toast.makeText(
                            this@MainActivity,
                            "Tour mode error: ${state.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }
    
    /**
     * Slide the fact card up while narration plays and slide it away when
     * the narration (and queue) finishes.
     */
    private fun showNarrationCard(narration: TourModeService.Narration?) {
        val card = binding.narrationCard

        if (narration == null) {
            if (card.visibility == View.VISIBLE) {
                card.animate()
                    .translationY(card.height.toFloat())
                    .alpha(0f)
                    .setDuration(200)
                    .withEndAction {
                        card.visibility = View.GONE
                        card.translationY = 0f
                        card.alpha = 1f
                    }
            }
            return
        }

        binding.tvNarrationTitle.text = narration.poiName
        binding.tvNarrationCategory.text = narration.category ?: ""
        binding.tvNarrationCategory.visibility =
            if (narration.category.isNullOrEmpty()) View.GONE else View.VISIBLE
        binding.tvNarrationFact.movementMethod = ScrollingMovementMethod()
        binding.tvNarrationFact.text = narration.factText
        binding.tvNarrationFact.scrollTo(0, 0)

        val upNext = narration.upNextTitle
        binding.tvNarrationUpNext.text = upNext?.let { "Up next: $it" } ?: ""
        binding.tvNarrationUpNext.visibility =
            if (upNext.isNullOrBlank()) View.GONE else View.VISIBLE

        // Attribution + "tell me more" path for Wikipedia-sourced facts
        val sourceUrl = narration.sourceUrl
        binding.tvNarrationSource.visibility =
            if (sourceUrl.isNullOrBlank()) View.GONE else View.VISIBLE
        binding.tvNarrationSource.setOnClickListener {
            sourceUrl?.let { url ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(this, "No browser available to open the article", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Tapping the card opens the full details sheet for the narrated place
        binding.tvNarrationHint.visibility =
            if (narration.poiId == null) View.GONE else View.VISIBLE
        card.setOnClickListener {
            narration.poiId?.let { poiId ->
                placesViewModel.selectPlace(poiId)
                showPlaceDetailsBottomSheet()
            }
        }

        if (card.visibility != View.VISIBLE) {
            card.alpha = 0f
            card.translationY = 48f * resources.displayMetrics.density
            card.visibility = View.VISIBLE
            card.animate().translationY(0f).alpha(1f).setDuration(250)
        }
    }

    private fun updateTourModeUI(isActive: Boolean) {
        if (isActive) {
            tourModeStatusCard.visibility = View.VISIBLE
            fabTourMode.setImageResource(android.R.drawable.ic_media_pause)
        } else {
            tourModeStatusCard.visibility = View.GONE
            fabTourMode.setImageResource(android.R.drawable.ic_dialog_map)
        }
    }
    
    private fun startTourMode() {
        Timber.d("startTourMode called")
        // Check and request background location permission if needed
        if (!checkBackgroundLocationPermission()) {
            Timber.d("Background location permission needed")
            requestBackgroundLocationPermission()
            return
        }
        
        // Create intent for the tour mode service
        val intent = Intent(this, TourModeService::class.java).apply {
            action = AppConstants.ACTION_START_TOUR_MODE
        }
        
        // Start and bind to the service
        Timber.d("Starting and binding TourModeService")
        startService(intent)
        bindService(
            Intent(this, TourModeService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
        
        isTourModeActive = true
        updateTourModeUI(true)
    }
    
    private fun stopTourMode() {
        Timber.d("stopTourMode called")
        // Create intent to stop the tour mode service
        val intent = Intent(this, TourModeService::class.java).apply {
            action = AppConstants.ACTION_STOP_TOUR_MODE
        }
        
        // Stop the service
        startService(intent)
        
        // Unbind from the service
        if (tourModeService != null) {
            unbindService(serviceConnection)
            tourModeService = null
        }
        
        isTourModeActive = false
        updateTourModeUI(false)
    }

    // --- MapLayersBottomSheet.MapLayersHost ---
    // The layers sheet reads and mutates the map through these; guarded in
    // case the sheet is somehow opened before the map is ready.

    override fun currentMapStyle(): Int = currentMapStyle

    override fun onMapStyleSelected(style: Int) {
        currentMapStyle = style
        if (::mMap.isInitialized) applyMapStyle(style)
        lifecycleScope.launch { userPreferencesRepository.setMapStyle(style) }
    }

    // When the map is ready, load the style and enable location display
    override fun onMapReady(map: MapLibreMap) {
        Timber.d("onMapReady called")
        mMap = map
        mMap.setOnMarkerClickListener(this)

        mMap.uiSettings.isCompassEnabled = true
        mMap.uiSettings.isRotateGesturesEnabled = true
        mMap.uiSettings.isTiltGesturesEnabled = true

        // Move the map's own chrome (compass, logo, attribution) clear of
        // the system bars
        ViewCompat.getRootWindowInsets(binding.root)
            ?.getInsets(WindowInsetsCompat.Type.systemBars())
            ?.let { bars ->
                mMap.uiSettings.setCompassMargins(
                    mMap.uiSettings.compassMarginLeft,
                    mMap.uiSettings.compassMarginTop + bars.top,
                    mMap.uiSettings.compassMarginRight,
                    mMap.uiSettings.compassMarginBottom
                )
                mMap.uiSettings.setLogoMargins(
                    mMap.uiSettings.logoMarginLeft,
                    mMap.uiSettings.logoMarginTop,
                    mMap.uiSettings.logoMarginRight,
                    mMap.uiSettings.logoMarginBottom + bars.bottom
                )
                mMap.uiSettings.setAttributionMargins(
                    mMap.uiSettings.attributionMarginLeft,
                    mMap.uiSettings.attributionMarginTop,
                    mMap.uiSettings.attributionMarginRight,
                    mMap.uiSettings.attributionMarginBottom + bars.bottom
                )
            }

        // Stop following the user when they pan/zoom manually (Google Maps
        // behavior); the recenter FAB turns following back on.
        mMap.addOnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                isFollowingUser = false
            }
        }

        // Long-press anywhere to scout that area for interesting places —
        // preview a destination before driving there
        mMap.addOnMapLongClickListener { point ->
            scoutArea(LatLng(point.latitude, point.longitude))
            true
        }

        // Restore the layers-sheet style from the last session; location
        // display is enabled once the style has loaded
        lifecycleScope.launch {
            currentMapStyle = userPreferencesRepository.mapStyleFlow.first()
            applyMapStyle(currentMapStyle)
        }
    }

    /**
     * Load a map style (the map renders nothing until one is set). The
     * default style follows the system DayNight setting so night drives
     * aren't a full-screen white blast. Markers survive style swaps, but
     * the location component binds to a style, so it re-activates in the
     * loaded callback.
     */
    private fun applyMapStyle(style: Int) {
        val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val styleUrl = MapStyles.styleUrl(style, nightMode == Configuration.UI_MODE_NIGHT_YES)

        mMap.setStyle(Style.Builder().fromUri(styleUrl)) { loadedStyle ->
            enableMyLocation(loadedStyle)
        }
    }

    /**
     * Discover POIs around an arbitrary map point instead of the user's
     * location. Deliberately leaves lastPoiFetchCenter alone: while parked
     * and planning, scouted markers stay put; once the user moves far
     * enough, the regular around-me refresh replaces them.
     */
    private fun scoutArea(point: LatLng) {
        scoutCircle?.let { mMap.removePolygon(it) }
        // MapLibre's simple annotations have no circle; a 64-gon reads the same
        scoutCircle = mMap.addPolygon(
            PolygonOptions()
                .addAll(GeoUtils.circlePoints(point, SCOUT_RADIUS_METERS.toDouble()).map { it.toMap() })
                .strokeColor(0x8834A853.toInt())
                .fillColor(0x1434A853)
        )

        placesViewModel.fetchNearbyPlaces(center = point, radius = SCOUT_RADIUS_METERS)
        Toast.makeText(this, "Scouting this area for interesting places…", Toast.LENGTH_SHORT).show()
    }

    // Request location permission; when granted, show the blue position dot
    // (MapLibre's location component) and start location updates
    private fun enableMyLocation(style: Style? = if (::mMap.isInitialized) mMap.style else null) {
        Timber.d("enableMyLocation called")
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                LOCATION_PERMISSION_REQUEST
            )
            return
        }

        // The location component renders the blue dot; the camera stays
        // ours (CameraMode.NONE) — following is handled explicitly
        if (style != null && style.isFullyLoaded) {
            try {
                mMap.locationComponent.apply {
                    activateLocationComponent(
                        LocationComponentActivationOptions.builder(this@MainActivity, style).build()
                    )
                    isLocationComponentEnabled = true
                    cameraMode = CameraMode.NONE
                }
            } catch (e: SecurityException) {
                Timber.e(e, "Location permission lost while enabling the location component")
            }
        }

        startLocationUpdates()
    }
    
    // Check if background location permission is granted
    private fun checkBackgroundLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    // Request background location permission
    private fun requestBackgroundLocationPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            BACKGROUND_LOCATION_PERMISSION_REQUEST
        )
    }

    // Set up a location request for frequent updates
    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            // Every second / 1 meter for smooth movement
            locationClient.requestUpdates(1000, 1f, locationListener)
        }
    }

    // Update the map with the new location data. The map's location
    // component renders the blue position dot; we only manage the camera.
    private fun updateLocationOnMap(location: Location) {
        val newLatLng = LatLng(location.latitude, location.longitude)
        lastKnownLatLng = newLatLng
        if (!::mMap.isInitialized) return

        // The location component draws the dot from location updates we
        // forward; MapLibre's own engine is off (CameraMode.NONE + explicit
        // updates keeps one source of truth)
        if (mMap.locationComponent.isLocationComponentActivated) {
            mMap.locationComponent.forceLocationUpdate(location)
        }

        if (isFirstUpdate) {
            Timber.d("First location update, animating camera with zoom")
            mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(newLatLng.toMap(), FOLLOW_ZOOM))
            isFirstUpdate = false
        } else if (isFollowingUser && !isNavigating) {
            // Navigation drives its own tilted/bearing camera; outside of it,
            // follow the user unless they've panned away.
            mMap.animateCamera(CameraUpdateFactory.newLatLng(newLatLng.toMap()), 1000)
        }
    }

    // Fetch POIs around the current position, and again after moving far
    // enough that the old results are stale.
    private fun refreshNearbyPlacesIfNeeded() {
        val current = lastKnownLatLng ?: return
        val lastFetch = lastPoiFetchCenter

        if (lastFetch == null || GeoUtils.distanceMeters(lastFetch, current) > POI_REFETCH_DISTANCE_METERS) {
            lastPoiFetchCenter = current
            // Moving on replaces any scouted area with local results
            scoutCircle?.let { mMap.removePolygon(it) }
            scoutCircle = null
            placesViewModel.fetchNearbyPlaces(center = current, radius = 500)
        }
    }

    // Handle the result of the permission request
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            LOCATION_PERMISSION_REQUEST -> {
                if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                    Timber.d("Location permission granted")
                    enableMyLocation()
                } else {
                    Timber.w("Location permission denied")
                    Toast.makeText(this, "Location permission denied", Toast.LENGTH_SHORT).show()
                }
            }
            BACKGROUND_LOCATION_PERMISSION_REQUEST -> {
                if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                    Timber.d("Background location permission granted, starting tour mode")
                    startTourMode()
                } else {
                    Timber.w("Background location permission denied")
                    Toast.makeText(this, "Background location permission denied. Tour mode requires this permission.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Display points of interest on the map
    private fun displayPointsOfInterest(places: List<PointOfInterest>) {
        if (!::mMap.isInitialized) return

        // Clear existing POI markers
        poiMarkers.values.forEach { mMap.removeMarker(it) }
        poiMarkers.clear()
        markerPlaceIds.clear()

        // Add new markers for each point of interest
        places.forEach { poi ->
            // Only add a marker if the POI has a place id to look details up by
            poi.placeId?.let { validPlaceId ->
                val marker = mMap.addMarker(
                    MarkerOptions()
                        .position(poi.latLng.toMap())
                        .title(poi.name)
                        .snippet(if (poi.isVisited) "${poi.category} · already narrated" else poi.category)
                        .icon(
                            MarkerIcons.pin(
                                this,
                                MarkerStyling.hueFor(poi.category),
                                MarkerStyling.alphaFor(poi.isVisited)
                            )
                        )
                )
                poiMarkers[poi.id] = marker
                // Place id for detail lookups on click (markers have no tag slot)
                markerPlaceIds[marker.id] = validPlaceId
            } ?: run {
                // Log POIs without a placeId - might indicate an issue upstream
                Timber.w("Point of Interest '${poi.name}' (ID: ${poi.id}) has no placeId, skipping detail marker setup.")
            }
        }
    }

    // Handle marker clicks to show point of interest details
    override fun onMarkerClick(marker: Marker): Boolean {
        // Get the place id remembered for this marker
        val placeId = markerPlaceIds[marker.id] ?: return false

        // Select the place to show details
        placesViewModel.selectPlace(placeId)

        // Show the bottom sheet with place details
        showPlaceDetailsBottomSheet()

        return true // Return true to consume the event (don't show the info window)
    }
    
    // Show the bottom sheet with place details
    private fun showPlaceDetailsBottomSheet() {
        val bottomSheet = PlaceDetailsBottomSheet.newInstance()
        bottomSheet.show(supportFragmentManager, PlaceDetailsBottomSheet.TAG)
    }

    // The MapView renders with its own GL surface and needs the activity
    // lifecycle forwarded to it explicitly (the old map fragment did this
    // for itself).
    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    // Stop location updates when the activity is paused to save battery
    override fun onPause() {
        super.onPause()
        mapView.onPause()
        locationClient.removeUpdates(locationListener)
    }

    // Resume location updates when the activity is resumed
    override fun onResume() {
        super.onResume()
        mapView.onResume()
        if (!isFirstUpdate) {
            startLocationUpdates()
        }
    }

    override fun onStop() {
        super.onStop()
        mapView.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    // Clean up resources when the activity is destroyed
    override fun onDestroy() {
        // Stop navigation if active
        if (isNavigating) {
            navigationService.stopNavigation()
        }

        // Unbind from the service if bound
        if (tourModeService != null) {
            unbindService(serviceConnection)
            tourModeService = null
        }
        mapView.onDestroy()
        super.onDestroy()
    }

    /**
     * Show the tour settings bottom sheet.
     */
    private fun showTourSettings() {
        // Only show if not already showing
        if (supportFragmentManager.findFragmentByTag(TourSettingsFragment.TAG) == null) {
            val tourSettingsFragment = TourSettingsFragment.newInstance()
            tourSettingsFragment.show(supportFragmentManager, TourSettingsFragment.TAG)
        }
    }

    // Show the destination input card (it takes the search bar's slot)
    private fun showDestinationInput() {
        destinationInputView.visibility = View.VISIBLE
        navigationStatusCard.visibility = View.GONE
        searchBarCard.visibility = View.GONE
    }

    // Hide the destination input card
    private fun hideDestinationInput() {
        destinationInputView.visibility = View.GONE
        if (!isNavigating) {
            searchBarCard.visibility = View.VISIBLE
        }
    }
    
    // Show the navigation status card
    private fun showNavigationStatus() {
        navigationStatusCard.visibility = View.VISIBLE
        destinationInputView.visibility = View.GONE
    }
    
    // Hide the navigation status card
    private fun hideNavigationStatus() {
        navigationStatusCard.visibility = View.GONE
    }
    
    // Start navigation to the given destination address
    private fun startNavigation(destinationAddress: String) {
        navigationJob?.cancel()
        navigationJob = lifecycleScope.launch {
            try {
                // Geocoding is a network call that can hang on a bad
                // connection — show the status card immediately in a
                // cancelable state instead of dead air
                tvNavigationDestination.text = getString(R.string.navigating_to, destinationAddress)
                tvNavigationInfo.text = getString(R.string.calculating_route)
                binding.btnStopNavigation.text = getString(R.string.cancel_button)
                binding.btnStopNavigation.setOnClickListener { stopNavigation() }
                hideDestinationInput()
                showNavigationStatus()
                searchBarCard.visibility = View.GONE

                // Get coordinates from the address using interface method (no casting needed)
                val destinationLatLng = navigationService.geocodeAddress(destinationAddress)

                if (destinationLatLng != null) {
                    // Start common navigation setup
                    startActiveNavigation(destinationLatLng, destinationAddress)
                } else {
                    Toast.makeText(this@MainActivity, "Could not find location: $destinationAddress", Toast.LENGTH_SHORT).show()
                    hideNavigationStatus()
                    searchBarCard.visibility = View.VISIBLE
                }
            } catch (e: CancellationException) {
                throw e // cancellation is the user's doing, not an error
            } catch (e: Exception) {
                Timber.e(e, "Error starting navigation: ${e.message}")
                Toast.makeText(this@MainActivity, "Error starting navigation: ${e.message}", Toast.LENGTH_SHORT).show()
                hideNavigationStatus()
                searchBarCard.visibility = View.VISIBLE
            }
        }
    }
    
    // DestinationSearchHost: bias search results toward where the user is
    override fun searchLocationBias(): LatLng? = lastKnownLatLng

    // DestinationSearchHost: a search result was picked — navigate to it
    override fun onDestinationSelected(name: String, latLng: LatLng) {
        navigationJob?.cancel()
        navigationJob = lifecycleScope.launch {
            startActiveNavigation(latLng, name)
        }
    }

    // Common method for starting navigation regardless of the entry point
    private suspend fun startActiveNavigation(destinationLatLng: LatLng, displayName: String) {
        try {
            // Update UI
            tvNavigationDestination.text = getString(R.string.navigating_to, displayName)
            tvNavigationInfo.text = getString(R.string.eta_calculating)
            
            // Start navigation
            isNavigating = true
            hideDestinationInput()
            showNavigationStatus()
            searchBarCard.visibility = View.GONE
            
            // Update UI with Start Navigation button
            binding.btnStopNavigation.text = getString(R.string.start_navigation)
            
            // Set button click listener for actual navigation
            binding.btnStopNavigation.setOnClickListener {
                // Change button text to "End Navigation"
                binding.btnStopNavigation.text = getString(R.string.end_navigation)
                
                // Reset click listener to end navigation
                binding.btnStopNavigation.setOnClickListener { 
                    stopNavigation()
                }
                
                // Show a toast indicating navigation has started
                Toast.makeText(this@MainActivity, "Navigation started to $displayName", Toast.LENGTH_SHORT).show()

                // Start collecting navigation updates. Tracked so that "End
                // Navigation" (or a new destination) actually cancels the
                // collector — including mid-route-calculation, when the
                // Directions API is still in flight.
                navigationJob?.cancel()
                navigationJob = lifecycleScope.launch {
                    var corridorRouteVersion = -1
                    navigationService.startNavigation(destinationLatLng).collectLatest { status ->
                        // Also shows the next instruction when one is available
                        updateNavigationStatus(status, displayName)

                        // On every new route (initial calculation or off-route
                        // recalculation): clear the stale polyline so it is
                        // redrawn, and re-register the tour corridor so
                        // narration follows the *new* drive
                        if (status.routeVersion != corridorRouteVersion) {
                            val route = navigationService.getCurrentRoute()
                            if (route.isNotEmpty()) {
                                routePolyline?.remove()
                                routePolyline = null
                                tourModeService?.updateRouteCorridor(route)
                                corridorRouteVersion = status.routeVersion
                            }
                        }

                        // Draw the route - moved this here to ensure route data is available
                        drawRouteFromNavigationService()

                        // Update camera to follow user if in navigation mode
                        status.currentLocation.let { currentLocation ->
                            updateCameraForNavigation(currentLocation)
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e // cancellation is the user's doing, not an error
        } catch (e: Exception) {
            Timber.e(e, "Error starting navigation: ${e.message}")
            Toast.makeText(this@MainActivity, "Error starting navigation: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    // New method to draw route from the navigation service
    private suspend fun drawRouteFromNavigationService() {
        try {
            // Get the route from the navigation service
            val routePoints = navigationService.getCurrentRoute()
            
            // Only proceed if we have no polyline yet or new route points
            if (routePolyline == null && routePoints.isNotEmpty()) {
                Timber.d("Drawing route with ${routePoints.size} points from navigation service")

                // Get current location and destination
                val currentLocation = lastKnownLatLng ?: return
                val destination = routePoints.lastOrNull() ?: return

                // Make sure destination marker exists
                if (destinationMarker == null) {
                    destinationMarker = mMap.addMarker(
                        MarkerOptions()
                            .position(destination.toMap())
                            .title("Destination")
                            .icon(MarkerIcons.pin(this, MarkerStyling.HUE_RED))
                    )
                }

                // Draw the route
                routePolyline = mMap.addPolyline(
                    PolylineOptions()
                        .addAll(routePoints.map { it.toMap() })
                        .width(4f)
                        .color(0xFF0080FF.toInt()) // Bright blue
                )

                // Move camera to show the route
                val boundsBuilder = LatLngBounds.Builder()
                    .include(currentLocation.toMap())
                    .include(destination.toMap())

                // Add route points to bounds
                routePoints.forEach { boundsBuilder.include(it.toMap()) }

                val bounds = boundsBuilder.build()
                mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 100))
            }
        } catch (e: Exception) {
            Timber.e(e, "Error drawing route from navigation service: ${e.message}")
        }
    }
    
    // Update camera position for active navigation
    private fun updateCameraForNavigation(currentLocation: LatLng) {
        // Don't update if we're not navigating or the user panned away
        if (!isNavigating || !isFollowingUser) return

        // Add location to history
        locationHistory.add(currentLocation)
        if (locationHistory.size > 5) {
            locationHistory.removeFirst()
        }
        
        // Move camera to follow current location with some bearing and tilt;
        // zoom widens with speed so highway driving shows the road ahead
        val cameraPosition = CameraPosition.Builder()
            .target(currentLocation.toMap())
            .zoom((CameraLogic.zoomForSpeed(lastKnownSpeedMps) - ZOOM_OFFSET).toDouble())
            .bearing(getUserBearing().toDouble())
            .tilt(45.0)
            .build()

        mMap.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition))
    }
    
    // Get user bearing (direction of travel) from location history
    private fun getUserBearing(): Float {
        // Calculate bearing from recent locations if we have enough
        if (locationHistory.size >= 2) {
            val recent = locationHistory.toList().takeLast(2)
            return GeoUtils.bearingDegrees(recent[0], recent[1])
        }
        return 0f
    }

    // Update the navigation status UI
    private fun updateNavigationStatus(status: NavigationService.NavigationStatus, destinationName: String) {
        // Format distance
        val distanceText = DistanceFormatter.format(status.distanceRemaining)
        
        // Format ETA
        val etaText = if (status.timeRemaining > 0) {
            val minutes = TimeUnit.MILLISECONDS.toMinutes(status.timeRemaining)
            val arrivalTime = Date(System.currentTimeMillis() + status.timeRemaining)
            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            
            if (minutes < 60) {
                getString(R.string.eta_format, "$minutes min (${timeFormat.format(arrivalTime)})")
            } else {
                val hours = minutes / 60
                val remainingMinutes = minutes % 60
                getString(R.string.eta_format, "$hours h $remainingMinutes min (${timeFormat.format(arrivalTime)})")
            }
        } else {
            getString(R.string.eta_calculating)
        }
        
        // Update UI
        tvNavigationDestination.text = getString(R.string.navigating_to, destinationName)
        tvNavigationInfo.text = "$etaText • ${getString(R.string.distance_remaining, distanceText)}"
        // Update ETA progress bar roughly based on time remaining
        val remaining = status.timeRemaining
        binding.progressEta.progress = when {
            remaining <= 0 -> 1000
            else -> (1000.0 * (1.0 - (remaining.coerceAtMost(60 * 60 * 1000L).toDouble() / (60 * 60 * 1000L)))).toInt()
        }.coerceIn(0, 1000)
        
        // Show the next instruction if available
        status.nextInstruction?.let { instruction ->
            showNextInstruction(instruction, status.announcementTiming)
        }
    }
    
    // Display the next navigation instruction
    private fun showNextInstruction(instruction: NavigationService.NavigationInstruction,
                                    announcementTiming: NavigationService.AnnouncementTiming) {
        // Get the maneuver details
        val maneuverDetails = navigationService.getManeuverDetails(instruction)

        // Show instruction in the UI
        showTurnInstructionFragment(instruction, maneuverDetails, announcementTiming)

        // Speak the instruction based on announcement timing, but only once per
        // maneuver+timing — status updates arrive every few seconds
        if (announcementTiming == NavigationService.AnnouncementTiming.IMMEDIATE ||
            announcementTiming == NavigationService.AnnouncementTiming.APPROACHING) {
            val announcementKey = "${instruction.maneuverPoint}|${instruction.type}|$announcementTiming"
            if (announcementKey != lastAnnouncementKey) {
                lastAnnouncementKey = announcementKey
                var voiceInstruction = formatInstructionForVoice(instruction, announcementTiming)

                // On arrival, close the tour with a summary of the drive
                // ("you heard about 7 places along the way"). Appended to the
                // same utterance so it can't race the arrival prompt.
                if (instruction.type == NavigationService.InstructionType.ARRIVE) {
                    tourModeService?.consumeTripSummaryPhrase()?.let { summary ->
                        voiceInstruction += " $summary"
                    }
                }

                // Priority prompt: pauses tour narration and resumes it after
                placesViewModel.speakNavigationPrompt(voiceInstruction)
            }
        }
    }
    
    // Format an instruction for voice announcement
    private fun formatInstructionForVoice(
        instruction: NavigationService.NavigationInstruction,
        timing: NavigationService.AnnouncementTiming
    ): String {
        // Get primary and secondary instructions
        val details = navigationService.getManeuverDetails(instruction)
        
        // Format distance for voice
        val distanceText = DistanceFormatter.spoken(instruction.distance)
        
        // Format based on timing
        return when (timing) {
            NavigationService.AnnouncementTiming.IMMEDIATE -> 
                "${details.primaryInstruction} now"
                
            NavigationService.AnnouncementTiming.APPROACHING ->
                "In $distanceText, ${details.primaryInstruction.lowercase()}"
                
            NavigationService.AnnouncementTiming.ADVANCE ->
                "In $distanceText, ${details.primaryInstruction.lowercase()}"
                
            else -> details.primaryInstruction
        }
    }
    
    // Show the turn instruction fragment
    private fun showTurnInstructionFragment(
        instruction: NavigationService.NavigationInstruction,
        maneuverDetails: NavigationService.ManeuverDetails,
        announcementTiming: NavigationService.AnnouncementTiming
    ) {
        // Create fragment if it doesn't exist
        if (turnInstructionFragment == null) {
            turnInstructionFragment = TurnInstructionFragment.newInstance()
            supportFragmentManager.beginTransaction()
                .replace(R.id.turn_instruction_container, turnInstructionFragment!!)
                .commit()
        }
        
        // Show the container
        findViewById<View>(R.id.turn_instruction_container).visibility = View.VISIBLE
        
        // Update the instruction
        turnInstructionFragment?.updateInstruction(instruction, maneuverDetails, announcementTiming)
    }
    
    // Hide the turn instruction fragment - implements NavigationInstructionController
    override fun hideTurnInstructions() {
        findViewById<View>(R.id.turn_instruction_container).visibility = View.GONE
    }
    
    // Get maneuver details for an instruction - implements NavigationDetailsProvider
    override fun getManeuverDetails(instruction: NavigationService.NavigationInstruction): 
            NavigationService.ManeuverDetails {
        return navigationService.getManeuverDetails(instruction)
    }
    
    // Stop navigation
    private fun stopNavigation() {
        // Stop the in-flight work first, whether that's a geocode, a route
        // calculation, or the live status collection
        navigationJob?.cancel()
        navigationJob = null

        isNavigating = false
        hideNavigationStatus()
        hideTurnInstructions()
        searchBarCard.visibility = View.VISIBLE

        // Remove route from map
        routePolyline?.let { mMap.removePolyline(it) }
        routePolyline = null
        destinationMarker?.let { mMap.removeMarker(it) }
        destinationMarker = null

        // Stop navigation service
        navigationService.stopNavigation()

        // Back to discovering POIs around the current position
        tourModeService?.clearRouteCorridor()

        // Clear location history and announcement state
        locationHistory.clear()
        lastAnnouncementKey = null
    }

    // Open the destination search sheet (Photon geocoder — no Google APIs).
    // No type filter: users can search businesses and street addresses alike.
    private fun launchDestinationSearch() {
        if (supportFragmentManager.findFragmentByTag(DestinationSearchBottomSheet.TAG) == null) {
            DestinationSearchBottomSheet.newInstance()
                .show(supportFragmentManager, DestinationSearchBottomSheet.TAG)
        }
    }
}