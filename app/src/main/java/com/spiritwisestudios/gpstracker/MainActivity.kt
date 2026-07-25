package com.spiritwisestudios.gpstracker

import android.Manifest
import android.content.ComponentName
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.view.ViewGroup
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
import android.view.Gravity
import android.widget.FrameLayout
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.spiritwisestudios.gpstracker.ads.AdsInitializer
import com.spiritwisestudios.gpstracker.ads.ConsentManager
import com.spiritwisestudios.gpstracker.ads.InterstitialAdManager
import com.spiritwisestudios.gpstracker.data.repository.AccountTierHolder
import com.spiritwisestudios.gpstracker.data.repository.MapProviderHolder
import com.spiritwisestudios.gpstracker.data.service.FrameworkLocationClient
import com.spiritwisestudios.gpstracker.domain.model.LatLng
import com.spiritwisestudios.gpstracker.domain.model.MapProvider
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.spiritwisestudios.gpstracker.data.repository.UserPreferencesRepository
import com.spiritwisestudios.gpstracker.domain.model.PointOfInterest
import com.spiritwisestudios.gpstracker.domain.model.TourPlan
import com.spiritwisestudios.gpstracker.domain.service.NavigationService
import com.spiritwisestudios.gpstracker.service.TourModeService
import com.spiritwisestudios.gpstracker.ui.fragment.DestinationSearchBottomSheet
import com.spiritwisestudios.gpstracker.ui.fragment.MapLayersBottomSheet
import com.spiritwisestudios.gpstracker.ui.fragment.PlaceDetailsBottomSheet
import com.spiritwisestudios.gpstracker.ui.fragment.TakeATourBottomSheet
import com.spiritwisestudios.gpstracker.ui.fragment.TourJournalBottomSheet
import com.spiritwisestudios.gpstracker.ui.fragment.TourSettingsFragment
import com.spiritwisestudios.gpstracker.ui.fragment.TurnInstructionFragment
import com.spiritwisestudios.gpstracker.ui.map.GoogleMapController
import com.spiritwisestudios.gpstracker.ui.map.MapController
import com.spiritwisestudios.gpstracker.ui.map.MapLibreMapController
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
import com.spiritwisestudios.gpstracker.util.GeoUtils
import com.spiritwisestudios.gpstracker.util.MarkerStyling
import com.spiritwisestudios.gpstracker.util.VoicePromptGate
import com.spiritwisestudios.gpstracker.databinding.ActivityMainBinding
import java.util.ArrayDeque

@AndroidEntryPoint
class MainActivity : AppCompatActivity(), MapController.Host,
    TurnInstructionFragment.NavigationDetailsProvider, TurnInstructionFragment.NavigationInstructionController,
    MapLayersBottomSheet.MapLayersHost, DestinationSearchBottomSheet.DestinationSearchHost,
    TakeATourBottomSheet.TakeATourHost {

    // The map, behind the controller for whichever provider is active —
    // MainActivity never touches a map SDK directly
    private lateinit var map: MapController
    private lateinit var activeProvider: MapProvider
    private lateinit var locationClient: FrameworkLocationClient
    private lateinit var locationListener: LocationListenerCompat

    @Inject
    lateinit var userPreferencesRepository: UserPreferencesRepository

    @Inject
    lateinit var mapProviderHolder: MapProviderHolder

    @Inject
    lateinit var accountTierHolder: AccountTierHolder

    // Use the viewModels() delegate to get the ViewModel from Hilt
    private val placesViewModel: PlacesViewModel by viewModels()

    private val LOCATION_PERMISSION_REQUEST = 1
    private val TOUR_LOCATION_PERMISSION_REQUEST = 2
    private val NOTIFICATION_PERMISSION_REQUEST = 3

    // The layers-sheet choices currently applied to the map. The style int
    // is a MapStyles or GoogleMapStyles value, per the active provider.
    private var currentMapStyle = 0
    private var trafficEnabled = false

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

    // Heading-up driving view outside guidance: engages at driving speed,
    // releases after a sustained stop
    private val drivingCameraGate = CameraLogic.DrivingCameraGate()

    // What the camera is currently showing (as opposed to what the gate
    // decides), so the view settles back to top-down exactly once when a
    // drive ends
    private var cameraInDrivingView = false

    // Last trustworthy GPS course, held through stops so the camera
    // doesn't snap north at a red light
    private var lastGpsBearing: Float? = null

    // Where we last searched for POIs; refetch after moving far enough
    private var lastPoiFetchCenter: LatLng? = null

    // Keeps the same instruction from being spoken on every location tick,
    // and arrival from being repeated at every fix in the parking lot
    private val voicePromptGate = VoicePromptGate()

    // Tour mode service connection
    private var tourModeService: TourModeService? = null
    private var isTourModeActive = false

    // Whether a binding has been requested, tracked separately from
    // tourModeService: onServiceDisconnected clears the reference while the
    // connection stays registered, and unbinding one twice throws.
    private var isTourServiceBound = false

    // The collectors watching the bound service. One set at a time — a
    // rebind used to stack another set on top, all writing the same views.
    private var tourServiceObserverJob: Job? = null

    // Tour mode UI elements
    private lateinit var fabTourMode: FloatingActionButton
    private lateinit var tourModeStatusCard: CardView
    private lateinit var tourModeTitle: TextView
    private lateinit var tourModeDescription: TextView
    private lateinit var btnStopTour: Button
    private lateinit var btnTourSettings: Button

    // UI elements for navigation
    private lateinit var searchBarCard: CardView
    private lateinit var navigationStatusCard: CardView
    private lateinit var tvNavigationDestination: TextView
    private lateinit var tvNavigationInfo: TextView

    // Navigation service
    @Inject
    lateinit var navigationService: NavigationService

    /**
     * Navigation is a three-state machine. PREVIEW computes and shows the
     * route with a live ETA but stays quiet — no voice, no turn cards, no
     * camera takeover — until the user taps Start and it becomes GUIDING.
     */
    private enum class NavState { NONE, PREVIEW, GUIDING }
    private var navState = NavState.NONE

    // Name of the planned tour being driven, if any — the tour guide opens
    // a named tour with a welcome instead of the generic route preview
    private var activeTourName: String? = null

    // Service connection for binding to the TourModeService
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val bound = (service as? TourModeService.TourModeServiceBinder)?.getService() ?: return
            tourModeService = bound

            // Start observing service state changes
            observeTourModeServiceState(bound)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            tourModeService = null
            stopObservingTourModeService()
            isTourModeActive = false
            updateTourModeUI(false)
        }
    }

    companion object {
        private const val POI_REFETCH_DISTANCE_METERS = 300f
        private const val SCOUT_RADIUS_METERS = 750

        // A cached fix older than this is more likely misleading than helpful
        private const val MAX_SEED_AGE_MS = 10 * 60 * 1000L
    }

    private lateinit var binding: ActivityMainBinding

    // Banner ad at the foot of the bottom-card stack; created once the
    // ads SDK and consent flow finish, hidden while guidance is active
    private var bannerAdView: AdView? = null
    private var bannerAdLoaded = false

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

        // The map provider is a settings choice; captured once here so a
        // mid-session toggle applies via activity recreation, never as a
        // half-switched map
        activeProvider = mapProviderHolder.current
        map = when (activeProvider) {
            MapProvider.GOOGLE -> GoogleMapController(this)
            MapProvider.OPEN_STREET_MAP -> MapLibreMapController(this)
        }
        map.onCreate(binding.mapContainer, savedInstanceState, this)

        // Keep controls clear of the system bars: targetSdk 35 draws
        // edge-to-edge, so the 3-button nav bar otherwise covers the
        // bottom FABs and cards (gesture nav has a much smaller inset)
        applyWindowInsets()

        // Setup the framework location client (works with either provider)
        locationClient = FrameworkLocationClient(this)

        // Define the listener to handle location updates
        locationListener = LocationListenerCompat { location ->
            lastKnownSpeedMps = if (location.hasSpeed()) location.speed else 0f
            // The GPS course is only meaningful in motion; keep the last
            // good one through stops
            if (location.hasBearing() && lastKnownSpeedMps >= 1f) {
                lastGpsBearing = location.bearing
            }
            drivingCameraGate.onSpeed(lastKnownSpeedMps)
            updateLocationOnMap(location)
            refreshNearbyPlacesIfNeeded()
        }

        // Set up observers
        setupObservers()

        // Set up click listeners
        setupClickListeners()

        // Banner ad, once the deferred SDK init and consent flow finish
        setupAds()
    }

    /**
     * Load the bottom banner after the Mobile Ads SDK is up (deferred to
     * after the first frame) and the UMP consent flow has run — the ad
     * request must reflect the user's consent answer. Premium accounts
     * skip all of it; a tier change re-lands here via activity recreation.
     */
    private fun setupAds() {
        if (accountTierHolder.isPremium) return
        AdsInitializer.whenInitialized {
            if (isFinishing || isDestroyed) return@whenInitialized
            ConsentManager.gatherConsent(this) {
                if (isFinishing || isDestroyed) return@gatherConsent
                loadBannerAd()
            }
        }
    }

    private fun loadBannerAd() {
        if (bannerAdView != null) return
        val adView = AdView(this).apply {
            setAdSize(AdSize.BANNER)
            adUnitId = BuildConfig.BANNER_AD_UNIT_ID
            adListener = object : AdListener() {
                override fun onAdLoaded() {
                    bannerAdLoaded = true
                    // Stay hidden during guidance; the drive's end unhides it
                    if (navState != NavState.GUIDING) {
                        binding.adBannerContainer.visibility = View.VISIBLE
                    }
                }
            }
        }
        bannerAdView = adView
        binding.adBannerContainer.addView(
            adView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL
            )
        )
        adView.loadAd(ConsentManager.buildAdRequest())
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
        navigationStatusCard = findViewById(R.id.navigation_status_card)
        tvNavigationDestination = navigationStatusCard.findViewById(R.id.tv_navigation_destination)
        tvNavigationInfo = navigationStatusCard.findViewById(R.id.tv_navigation_info)

        // Initialize the turn instruction container
        findViewById<View>(R.id.turn_instruction_container)
    }

    /**
     * Add the system-bar insets to the edge-anchored controls. The map
     * itself stays edge-to-edge; only the controls (and the map's own
     * chrome, via the controller) move. The layout's margins are treated as
     * design margins with the inset added on top, so gesture nav gets a
     * small lift and the 3-button bar a full one.
     */
    private fun applyWindowInsets() {
        val bottomViews = listOf<View>(
            binding.fabTourMode, binding.fabTakeTour, binding.fabRecenter,
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
            map.applySystemBarInsets(bars.top, bars.bottom)
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

        // The search bar opens the destination search sheet, like Google Maps
        searchBarCard.setOnClickListener {
            launchDestinationSearch()
        }

        // Navigation card buttons keep one role each; the card's state
        // machine decides which are visible
        binding.btnNavStart.setOnClickListener { beginGuidance() }
        binding.btnNavStop.setOnClickListener { stopNavigation() }

        // Recenter FAB re-engages camera following, in whichever view fits
        // what the user is doing right now
        findViewById<FloatingActionButton>(R.id.fab_recenter).setOnClickListener {
            isFollowingUser = true
            lastKnownLatLng?.let { pos ->
                if (navState == NavState.GUIDING || drivingCameraGate.isDriving) {
                    animateDrivingCamera(pos)
                } else {
                    easeToTopDownFollow(pos)
                }
            }
        }

        // Layers FAB: pick the map style (and traffic, on the Google map)
        findViewById<FloatingActionButton>(R.id.fab_layers).setOnClickListener {
            MapLayersBottomSheet.newInstance()
                .show(supportFragmentManager, MapLayersBottomSheet.TAG)
        }

        // Journal FAB: every place the tour guide has narrated so far
        findViewById<FloatingActionButton>(R.id.fab_journal).setOnClickListener {
            TourJournalBottomSheet.newInstance()
                .show(supportFragmentManager, TourJournalBottomSheet.TAG)
        }

        // Take a Tour FAB: plan a curated sightseeing drive
        binding.fabTakeTour.setOnClickListener {
            if (supportFragmentManager.findFragmentByTag(TakeATourBottomSheet.TAG) == null) {
                TakeATourBottomSheet.newInstance()
                    .show(supportFragmentManager, TakeATourBottomSheet.TAG)
            }
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

    /**
     * Mirror the bound service's state into the UI. Every flow here is a
     * `StateFlow`, so a rebind after rotation replays the current narration
     * and tour state instead of waiting for the next change.
     */
    private fun observeTourModeServiceState(service: TourModeService) {
        stopObservingTourModeService()
        tourServiceObserverJob = lifecycleScope.launch {
            // Show a fact card while a POI is being narrated
            launch {
                service.currentNarration.collectLatest { narration ->
                    showNarrationCard(narration)
                }
            }

            // Keep the fact card's play/pause button showing the action it
            // would perform, in step with the notification's controls
            launch {
                service.isNarrationPlaying.collectLatest { playing ->
                    binding.btnNarrationPlayPause.setImageResource(
                        if (playing) R.drawable.ic_pause else R.drawable.ic_play_arrow
                    )
                }
            }

            // The service is the authority on whether a tour is running
            launch {
                service.serviceState.collectLatest { state ->
                    isTourModeActive = state.isRunning
                    updateTourModeUI(state.isRunning)
                    if (state is TourModeService.TourModeState.Error) {
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.tour_mode_error, state.message),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private fun stopObservingTourModeService() {
        tourServiceObserverJob?.cancel()
        tourServiceObserverJob = null
    }

    /**
     * Slide the fact card up while narration plays and slide it away when
     * the narration (and queue) finishes.
     */
    private fun showNarrationCard(narration: TourModeService.Narration?) {
        val card = binding.narrationCard

        // A pending hide animation would set the card GONE after this
        // update ran; every path below owns the card's state from here
        card.animate().cancel()

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
        card.translationY = 0f
        card.alpha = 1f

        binding.tvNarrationTitle.text = narration.poiName
        binding.tvNarrationCategory.text = narration.category ?: ""
        binding.tvNarrationCategory.visibility =
            if (narration.category.isNullOrEmpty()) View.GONE else View.VISIBLE
        binding.tvNarrationFact.movementMethod = ScrollingMovementMethod()
        binding.tvNarrationFact.text = narration.factText
        binding.tvNarrationFact.scrollTo(0, 0)

        val upNext = narration.upNextTitle
        binding.tvNarrationUpNext.text = upNext?.let { getString(R.string.up_next, it) } ?: ""
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
                    Toast.makeText(this, R.string.no_browser_available, Toast.LENGTH_SHORT).show()
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
            fabTourMode.setImageResource(R.drawable.ic_stop)
        } else {
            tourModeStatusCard.visibility = View.GONE
            fabTourMode.setImageResource(R.drawable.ic_tour)
        }
    }

    /**
     * Start tour mode once its two runtime permissions are settled. The
     * tour service is a foreground service with the `location` type, so
     * while-in-use location is enough — it keeps narrating with the screen
     * off without ever needing "Allow all the time".
     */
    private fun startTourMode() {
        Timber.d("startTourMode called")
        if (!hasLocationPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                TOUR_LOCATION_PERMISSION_REQUEST
            )
            return
        }

        // Android 13+ blocks notifications until the user opts in; the tour
        // status and its playback controls live in the shade, so ask before
        // the first tour. Denial is not a blocker — the tour still narrates.
        if (needsNotificationPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST
            )
            return
        }

        launchTourService()
    }

    private fun hasLocationPermission(): Boolean =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun needsNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED

    private fun launchTourService() {
        // Create intent for the tour mode service
        val intent = Intent(this, TourModeService::class.java).apply {
            action = AppConstants.ACTION_START_TOUR_MODE
        }

        // Start and bind to the service
        Timber.d("Starting and binding TourModeService")
        startService(intent)
        bindTourService()

        // Optimistic: the FAB has to answer the tap now, and the service
        // reports Starting a moment later and owns the state from then on
        isTourModeActive = true
        updateTourModeUI(true)
    }

    /**
     * Attach to a running tour, if there is one.
     *
     * Deliberately no `BIND_AUTO_CREATE`: this must observe a tour that is
     * already under way, never conjure a service with no tour behind it. The
     * connection is registered either way and connects as soon as the service
     * exists, which is why the same call also works right after
     * `startService()`.
     */
    private fun bindTourService() {
        if (isTourServiceBound) return
        // Set before binding: the connection is registered even when the
        // bind fails, so it always needs the matching unbind
        isTourServiceBound = true
        if (!bindService(Intent(this, TourModeService::class.java), serviceConnection, 0)) {
            Timber.d("No tour service to bind to yet")
        }
    }

    private fun unbindTourService() {
        if (!isTourServiceBound) return
        unbindService(serviceConnection)
        isTourServiceBound = false
        tourModeService = null
        stopObservingTourModeService()
    }

    private fun stopTourMode() {
        Timber.d("stopTourMode called")
        // Create intent to stop the tour mode service
        val intent = Intent(this, TourModeService::class.java).apply {
            action = AppConstants.ACTION_STOP_TOUR_MODE
        }

        // Stop the service. The binding follows the activity's lifecycle
        // rather than the tour's, so it stays until onStop; the service
        // publishes Inactive before it goes away.
        startService(intent)

        isTourModeActive = false
        updateTourModeUI(false)
    }

    // --- MapLayersBottomSheet.MapLayersHost ---
    // The layers sheet reads and mutates the map through these; guarded in
    // case the sheet is somehow opened before the map is ready.

    override fun mapProvider(): MapProvider = activeProvider

    override fun currentMapStyle(): Int = currentMapStyle

    override fun onMapStyleSelected(style: Int) {
        currentMapStyle = style
        if (map.isReady) applyMapStyle(style)
        lifecycleScope.launch {
            when (activeProvider) {
                MapProvider.GOOGLE -> userPreferencesRepository.setGoogleMapStyle(style)
                MapProvider.OPEN_STREET_MAP -> userPreferencesRepository.setMapStyle(style)
            }
        }
    }

    override fun isTrafficEnabled(): Boolean = trafficEnabled

    override fun onTrafficToggled(enabled: Boolean) {
        trafficEnabled = enabled
        map.setTrafficEnabled(enabled)
        lifecycleScope.launch { userPreferencesRepository.setMapTraffic(enabled) }
    }

    // --- MapController.Host ---

    override fun onMapReady() {
        Timber.d("onMapReady called")
        // Restore the layers-sheet choices from the last session; location
        // display is enabled once the style is in place
        lifecycleScope.launch {
            currentMapStyle = when (activeProvider) {
                MapProvider.GOOGLE -> userPreferencesRepository.googleMapStyleFlow.first()
                MapProvider.OPEN_STREET_MAP -> userPreferencesRepository.mapStyleFlow.first()
            }
            applyMapStyle(currentMapStyle)
            if (activeProvider == MapProvider.GOOGLE) {
                trafficEnabled = userPreferencesRepository.mapTrafficFlow.first()
                map.setTrafficEnabled(trafficEnabled)
            }
            enableMyLocation()
        }
    }

    // Stop following the user when they pan/zoom manually (Google Maps
    // behavior); the recenter FAB turns following back on.
    override fun onUserGesture() {
        isFollowingUser = false
    }

    // Long-press anywhere to scout that area for interesting places —
    // preview a destination before driving there
    override fun onMapLongClick(point: LatLng) {
        scoutArea(point)
    }

    // Handle marker clicks to show point of interest details
    override fun onMarkerClick(placeId: String) {
        placesViewModel.selectPlace(placeId)
        showPlaceDetailsBottomSheet()
    }

    /**
     * Apply a layers-sheet style. The default style follows the system
     * DayNight setting so night drives aren't a full-screen white blast.
     */
    private fun applyMapStyle(style: Int) {
        val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        map.applyStyle(style, nightMode == Configuration.UI_MODE_NIGHT_YES)
    }

    /**
     * Discover POIs around an arbitrary map point instead of the user's
     * location. Deliberately leaves lastPoiFetchCenter alone: while parked
     * and planning, scouted markers stay put; once the user moves far
     * enough, the regular around-me refresh replaces them.
     */
    private fun scoutArea(point: LatLng) {
        map.showScoutCircle(point, SCOUT_RADIUS_METERS.toDouble())
        placesViewModel.fetchNearbyPlaces(center = point, radius = SCOUT_RADIUS_METERS)
        Toast.makeText(this, R.string.scouting_area, Toast.LENGTH_SHORT).show()
    }

    // Request location permission; when granted, show the blue position dot
    // and start location updates
    private fun enableMyLocation() {
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

        map.enableLocationDisplay()
        startLocationUpdates()
    }

    // Set up a location request for frequent updates
    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            // Place the map on the freshest cached fix right away instead of
            // sitting empty while the first live fix is acquired. Position
            // only: the cached speed and bearing are stale and would engage
            // the driving camera from a previous drive.
            if (isFirstUpdate) {
                locationClient.lastKnownLocation()
                    ?.takeIf { System.currentTimeMillis() - it.time <= MAX_SEED_AGE_MS }
                    ?.let { cached ->
                        val seed = Location(cached.provider ?: "").apply {
                            latitude = cached.latitude
                            longitude = cached.longitude
                            time = cached.time
                        }
                        locationListener.onLocationChanged(seed)
                    }
            }
            // Every second, no displacement filter — a distance gate only
            // delays delivery, and still fixes are cheap to handle
            locationClient.requestUpdates(1000, 0f, locationListener)
        }
    }

    // Update the map with the new location data. The map renders the blue
    // position dot; we only manage the camera.
    private fun updateLocationOnMap(location: Location) {
        val newLatLng = LatLng(location.latitude, location.longitude)
        lastKnownLatLng = newLatLng
        if (!map.isReady) return

        map.forwardLocation(location)

        if (isFirstUpdate) {
            Timber.d("First location update, animating camera with zoom")
            map.animateToFirstFix(newLatLng)
            isFirstUpdate = false
        } else if (isFollowingUser && navState == NavState.NONE) {
            // Navigation drives its own camera, and a route preview holds
            // the route overview; outside of those, follow the user unless
            // they've panned away — heading-up while driving, plain
            // top-down otherwise
            when {
                drivingCameraGate.isDriving -> animateDrivingCamera(newLatLng)
                cameraInDrivingView -> easeToTopDownFollow(newLatLng)
                else -> map.easeFollow(newLatLng, CameraLogic.CAMERA_EASE_MS)
            }
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
            map.clearScoutCircle()
            placesViewModel.fetchNearbyPlaces(center = current, radius = 500)
        }
    }

    // Handle the result of the permission request
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            LOCATION_PERMISSION_REQUEST -> {
                if (grantResults.isNotEmpty() && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
                    Timber.d("Location permission granted")
                    enableMyLocation()
                } else {
                    Timber.w("Location permission denied")
                    Toast.makeText(this, R.string.location_permission_denied, Toast.LENGTH_SHORT).show()
                }
            }
            TOUR_LOCATION_PERMISSION_REQUEST -> {
                if (grantResults.isNotEmpty() && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
                    Timber.d("Location permission granted for tour mode")
                    enableMyLocation()
                    // Continue the tour start; next stop is the notification check
                    startTourMode()
                } else {
                    Timber.w("Location permission denied; tour mode cannot start")
                    Toast.makeText(this, R.string.tour_needs_location, Toast.LENGTH_LONG).show()
                }
            }
            NOTIFICATION_PERMISSION_REQUEST -> {
                // Either way the tour can run; without the permission the
                // status card in the app is the only tour surface
                if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                    Timber.w("Notification permission denied; tour notifications will not show")
                    Toast.makeText(this, R.string.notifications_disabled_hint, Toast.LENGTH_LONG).show()
                }
                launchTourService()
            }
        }
    }

    // Display points of interest on the map
    private fun displayPointsOfInterest(places: List<PointOfInterest>) {
        if (!map.isReady) return

        val markers = places.mapNotNull { poi ->
            // Only add a marker if the POI has a place id to look details up by
            val placeId = poi.placeId
            if (placeId == null) {
                // Log POIs without a placeId - might indicate an issue upstream
                Timber.w("Point of Interest '${poi.name}' (ID: ${poi.id}) has no placeId, skipping detail marker setup.")
                return@mapNotNull null
            }
            MapController.PoiMarkerSpec(
                placeId = placeId,
                position = poi.latLng,
                title = poi.name,
                snippet = if (poi.isVisited) "${poi.category} · already narrated" else poi.category,
                hue = MarkerStyling.hueFor(poi.category),
                alpha = MarkerStyling.alphaFor(poi.isVisited)
            )
        }
        map.setPoiMarkers(markers)
    }

    // Show the bottom sheet with place details
    private fun showPlaceDetailsBottomSheet() {
        val bottomSheet = PlaceDetailsBottomSheet.newInstance()
        bottomSheet.show(supportFragmentManager, PlaceDetailsBottomSheet.TAG)
    }

    // The map view renders with its own GL surface and needs the activity
    // lifecycle forwarded to it explicitly (through the controller).
    override fun onStart() {
        super.onStart()
        map.onStart()

        // A tour that ended while we were unbound — stopped from the
        // notification, or reclaimed by the system — had no way to say so,
        // and binding can only ever bring good news. Clear first, then let
        // the service correct us if it is still there.
        if (isTourModeActive && !TourModeService.isAlive) {
            isTourModeActive = false
            updateTourModeUI(false)
            showNarrationCard(null)
        }

        // Reattach to a tour that is already running. Rotation, the activity
        // recreation a settings save triggers, and returning after the
        // process was killed all arrive here with the guide still narrating;
        // without this the fact card was gone and the FAB offered to start a
        // tour that was already under way.
        bindTourService()
    }

    // Stop location updates when the activity is paused to save battery
    override fun onPause() {
        super.onPause()
        map.onPause()
        bannerAdView?.pause()
        locationClient.removeUpdates(locationListener)
    }

    // Resume location updates when the activity is resumed
    override fun onResume() {
        super.onResume()
        map.onResume()
        bannerAdView?.resume()
        if (!isFirstUpdate) {
            startLocationUpdates()
        }
    }

    override fun onStop() {
        super.onStop()
        map.onStop()

        // The tour keeps running without us; onStart binds again and the
        // service's state flows replay where it got to
        unbindTourService()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        map.onSaveInstanceState(outState)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        map.onLowMemory()
    }

    // Clean up resources when the activity is destroyed
    override fun onDestroy() {
        // Stop navigation if active
        if (navState != NavState.NONE) {
            navigationService.stopNavigation()
        }

        // Normally already done in onStop; guarded, so this is a no-op then
        unbindTourService()
        bannerAdView?.destroy()
        bannerAdView = null
        map.onDestroy()
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

    // Show the navigation status card
    private fun showNavigationStatus() {
        navigationStatusCard.visibility = View.VISIBLE
    }

    // Hide the navigation status card
    private fun hideNavigationStatus() {
        navigationStatusCard.visibility = View.GONE
    }

    // DestinationSearchHost: bias search results toward where the user is
    override fun searchLocationBias(): LatLng? = lastKnownLatLng

    // DestinationSearchHost: a search result was picked — preview the route
    override fun onDestinationSelected(name: String, latLng: LatLng) {
        activeTourName = null // an ordinary drive, not a planned tour
        navigationJob?.cancel()
        navigationJob = lifecycleScope.launch {
            startActiveNavigation(latLng, name)
        }
    }

    // TakeATourHost: the tour picker biases cities/search the same way
    override fun tourLocationBias(): LatLng? = lastKnownLatLng

    // TakeATourHost: a tour was planned — make sure the guide is running,
    // then preview the loop route through the tour's stops. Tapping Start
    // begins guidance, and the corridor registration points narration at
    // the tour's route like any other drive.
    override fun onTourPlanned(plan: TourPlan) {
        if (!isTourModeActive) {
            startTourMode()
        }
        activeTourName = plan.name // the guide opens with a tour welcome
        navigationJob?.cancel()
        navigationJob = lifecycleScope.launch {
            startActiveNavigation(plan.destination, plan.name, plan.stops.map { it.latLng })
        }
    }

    /**
     * Route preview: compute the route right away and show it with a live
     * ETA, but hold guidance — voice prompts, turn cards, the chase camera —
     * until the user taps Start. One status collection runs for the whole
     * session; beginGuidance() only changes what the collector is allowed
     * to do with the updates. Tracked in navigationJob so Cancel/End (or a
     * new destination) stops it even mid-route-calculation.
     */
    private suspend fun startActiveNavigation(
        destinationLatLng: LatLng,
        displayName: String,
        waypoints: List<LatLng> = emptyList()
    ) {
        try {
            navState = NavState.PREVIEW
            // A new destination is a new drive: its turns and its arrival are
            // all still unspoken, even if the last drive ended at one of them
            voicePromptGate.reset()
            updateNavButtons()
            tvNavigationDestination.text = getString(R.string.route_to, displayName)
            tvNavigationInfo.text = getString(R.string.calculating_route)
            binding.progressEta.progress = 0
            showNavigationStatus()
            searchBarCard.visibility = View.GONE

            var corridorRouteVersion = -1
            navigationService.startNavigation(destinationLatLng, waypoints).collectLatest { status ->
                // Also shows the next instruction when one is available
                updateNavigationStatus(status, displayName)

                // On every new route (initial calculation or off-route
                // recalculation): clear the stale polyline so it is
                // redrawn, and while guiding re-register the tour corridor
                // so narration follows the *new* drive
                if (status.routeVersion != corridorRouteVersion) {
                    val route = navigationService.getCurrentRoute()
                    if (route.isNotEmpty()) {
                        map.clearRoutePolyline()
                        if (navState == NavState.GUIDING) {
                            tourModeService?.updateRouteCorridor(route, activeTourName)
                        }
                        corridorRouteVersion = status.routeVersion
                    }
                }

                // Draw the route - moved this here to ensure route data is available
                drawRouteFromNavigationService()

                // While guiding, the camera follows the drive
                status.currentLocation.let { currentLocation ->
                    updateCameraForNavigation(currentLocation)
                }
            }
        } catch (e: CancellationException) {
            throw e // cancellation is the user's doing, not an error
        } catch (e: Exception) {
            Timber.e(e, "Error starting navigation: ${e.message}")
            Toast.makeText(this@MainActivity, getString(R.string.navigation_error, e.message), Toast.LENGTH_SHORT).show()
            stopNavigation()
        }
    }

    /** PREVIEW → GUIDING: turn on voice prompts, turn cards, and the chase camera. */
    private fun beginGuidance() {
        if (navState != NavState.PREVIEW) return
        navState = NavState.GUIDING
        updateNavButtons()
        isFollowingUser = true

        // No ads while driving; the banner comes back when the drive ends
        binding.adBannerContainer.visibility = View.GONE

        // Tour narration follows the planned drive instead of the current spot
        lifecycleScope.launch {
            val route = navigationService.getCurrentRoute()
            if (route.isNotEmpty()) {
                tourModeService?.updateRouteCorridor(route, activeTourName)
            }
        }
    }

    /** Keep the two nav-card buttons in sync with the state machine. */
    private fun updateNavButtons() {
        when (navState) {
            NavState.PREVIEW -> {
                binding.btnNavStart.visibility = View.VISIBLE
                binding.btnNavStop.text = getString(R.string.cancel_button)
            }
            NavState.GUIDING -> {
                binding.btnNavStart.visibility = View.GONE
                binding.btnNavStop.text = getString(R.string.end_navigation)
            }
            NavState.NONE -> Unit
        }
    }

    // New method to draw route from the navigation service
    private suspend fun drawRouteFromNavigationService() {
        try {
            // Get the route from the navigation service
            val routePoints = navigationService.getCurrentRoute()

            // Only proceed if we have no polyline yet or new route points
            if (!map.hasRoutePolyline && routePoints.isNotEmpty()) {
                Timber.d("Drawing route with ${routePoints.size} points from navigation service")

                // Get current location and destination
                val currentLocation = lastKnownLatLng ?: return
                val destination = routePoints.lastOrNull() ?: return

                map.showDestinationMarker(destination)
                map.showRoutePolyline(routePoints)

                // Move the camera to show the whole route, flat and north-up
                cameraInDrivingView = false
                map.animateToRouteOverview(routePoints, currentLocation)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error drawing route from navigation service: ${e.message}")
        }
    }

    // Update camera position for active navigation
    private fun updateCameraForNavigation(currentLocation: LatLng) {
        // Only the guidance phase drives the camera, and not if the user panned away
        if (navState != NavState.GUIDING || !isFollowingUser) return
        animateDrivingCamera(currentLocation)
    }

    // Google-style driving view: heading-up, tilted toward the horizon,
    // with the position puck in the lower third so the road ahead fills
    // the screen; zoom widens with speed so highway driving shows further
    private fun animateDrivingCamera(currentLocation: LatLng) {
        cameraInDrivingView = true

        // Track recent positions as the bearing fallback
        locationHistory.add(currentLocation)
        if (locationHistory.size > 5) {
            locationHistory.removeFirst()
        }

        // A fix shows where the user was; by the time the ease lands they
        // are one ease further down the road. Aim there, not at the fix,
        // so the view doesn't permanently trail reality. The puck itself
        // stays on the true fix.
        val bearing = getUserBearing()
        val lead = CameraLogic.cameraLeadMeters(lastKnownSpeedMps)
        val target = if (lead > 0f) {
            GeoUtils.offsetMeters(currentLocation, bearing, lead)
        } else {
            currentLocation
        }

        // Linear ease matched to the 1 s location cadence, so the camera
        // glides between fixes instead of lurching
        map.easeDrivingCamera(
            target,
            CameraLogic.zoomForSpeed(lastKnownSpeedMps),
            bearing,
            CameraLogic.CAMERA_EASE_MS
        )
    }

    // Settle back to the flat, north-up follow view once a drive ends
    private fun easeToTopDownFollow(target: LatLng) {
        cameraInDrivingView = false
        map.animateToTopDownFollow(target)
    }

    // Get user bearing (direction of travel): the GPS course when we have
    // one, else derived from recent positions
    private fun getUserBearing(): Float {
        lastGpsBearing?.let { return it }
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

        // Update UI; a preview is a plan, not a drive
        tvNavigationDestination.text = if (navState == NavState.GUIDING) {
            getString(R.string.navigating_to, destinationName)
        } else {
            getString(R.string.route_to, destinationName)
        }
        tvNavigationInfo.text = "$etaText • ${getString(R.string.distance_remaining, distanceText)}"
        // Update ETA progress bar roughly based on time remaining
        val remaining = status.timeRemaining
        binding.progressEta.progress = when {
            remaining <= 0 -> 1000
            else -> (1000.0 * (1.0 - (remaining.coerceAtMost(60 * 60 * 1000L).toDouble() / (60 * 60 * 1000L)))).toInt()
        }.coerceIn(0, 1000)

        // Turn instructions (and their voice prompts) belong to guidance;
        // a preview stays quiet
        if (navState == NavState.GUIDING) {
            status.nextInstruction?.let { instruction ->
                showNextInstruction(instruction, status.announcementTiming)
            }
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
            val isArrival = instruction.type == NavigationService.InstructionType.ARRIVE
            val announcementKey = "${instruction.maneuverPoint}|${instruction.type}|$announcementTiming"
            if (voicePromptGate.shouldSpeak(announcementKey, isArrival)) {
                var voiceInstruction = formatInstructionForVoice(instruction, announcementTiming)

                // On arrival, close the tour with a summary of the drive
                // ("you heard about 7 places along the way"). Appended to the
                // same utterance so it can't race the arrival prompt.
                if (isArrival) {
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
        val wasGuiding = navState == NavState.GUIDING

        // Stop the in-flight work first, whether that's a geocode, a route
        // calculation, or the live status collection
        navigationJob?.cancel()
        navigationJob = null

        navState = NavState.NONE
        hideNavigationStatus()
        hideTurnInstructions()
        searchBarCard.visibility = View.VISIBLE

        // Remove route from map
        map.clearRoutePolyline()
        map.clearDestinationMarker()

        // Stop navigation service
        navigationService.stopNavigation()

        // Back to discovering POIs around the current position
        tourModeService?.clearRouteCorridor()
        activeTourName = null

        // Clear location history and announcement state
        locationHistory.clear()
        voicePromptGate.reset()

        // The end of a drive is the one natural ad break: bring the banner
        // back and show the preloaded interstitial (a no-op if none is
        // ready — never make the user wait on an ad)
        if (bannerAdLoaded) {
            binding.adBannerContainer.visibility = View.VISIBLE
        }
        if (wasGuiding) {
            InterstitialAdManager.showAd(this)
        }
    }

    // Open the destination search sheet (Photon or Google Places Text
    // Search, per the map-provider setting). No type filter: users can
    // search businesses and street addresses alike.
    private fun launchDestinationSearch() {
        if (supportFragmentManager.findFragmentByTag(DestinationSearchBottomSheet.TAG) == null) {
            DestinationSearchBottomSheet.newInstance()
                .show(supportFragmentManager, DestinationSearchBottomSheet.TAG)
        }
    }
}
