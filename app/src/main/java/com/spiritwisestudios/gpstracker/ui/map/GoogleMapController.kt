package com.spiritwisestudios.gpstracker.ui.map

import android.app.Activity
import android.content.res.Resources
import android.location.Location
import android.os.Bundle
import android.view.ViewGroup
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.Circle
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng as GoogleLatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import com.spiritwisestudios.gpstracker.R
import com.spiritwisestudios.gpstracker.domain.model.LatLng
import com.spiritwisestudios.gpstracker.util.CameraLogic
import com.spiritwisestudios.gpstracker.util.GoogleMapStyles
import timber.log.Timber

/**
 * [MapController] over the Google Maps SDK. Needs the MAPS_API_KEY manifest
 * meta-data (injected from local.properties); selectable in settings only
 * when that key is configured.
 */
class GoogleMapController(private val activity: Activity) : MapController {

    private lateinit var mapView: MapView
    private var map: GoogleMap? = null
    private var host: MapController.Host? = null

    private val poiMarkers = mutableListOf<Marker>()
    private var destinationMarker: Marker? = null
    private var routePolyline: Polyline? = null
    private var scoutCircle: Circle? = null

    // setPadding also positions Google's own chrome, so the system-bar
    // insets and the driving-view offset are combined into one padding
    private var topInsetPx = 0
    private var bottomInsetPx = 0
    private var inDrivingView = false

    override val isReady: Boolean
        get() = map != null

    private fun LatLng.toMap() = GoogleLatLng(latitude, longitude)

    override fun onCreate(container: ViewGroup, savedInstanceState: Bundle?, host: MapController.Host) {
        this.host = host
        mapView = MapView(activity)
        container.addView(mapView)
        // The Google MapView wants a bundle of its own, not the activity's
        mapView.onCreate(savedInstanceState?.getBundle(MAP_VIEW_STATE_KEY))
        mapView.getMapAsync { map -> onMapReady(map) }
    }

    private fun onMapReady(map: GoogleMap) {
        this.map = map

        map.uiSettings.isCompassEnabled = true
        map.uiSettings.isZoomControlsEnabled = false // keep clean; pinch to zoom
        map.uiSettings.isRotateGesturesEnabled = true
        map.uiSettings.isTiltGesturesEnabled = true

        // Force a tile refresh once loaded; without it the first render can
        // stay blank on some devices
        map.setOnMapLoadedCallback {
            map.moveCamera(CameraUpdateFactory.newCameraPosition(map.cameraPosition))
        }

        map.setOnCameraMoveStartedListener { reason ->
            if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) {
                host?.onUserGesture()
            }
        }

        map.setOnMapLongClickListener { point ->
            host?.onMapLongClick(LatLng(point.latitude, point.longitude))
        }

        map.setOnMarkerClickListener { marker ->
            val placeId = marker.tag as? String
            if (placeId != null) {
                host?.onMarkerClick(placeId)
                true // consume the event (don't show the info window)
            } else {
                false
            }
        }

        applyPadding()
        host?.onMapReady()
    }

    override fun applyStyle(style: Int, nightMode: Boolean) {
        val map = map ?: return
        // The map ignores the app's DayNight theme; style the NORMAL type
        // dark ourselves (satellite and terrain render their own imagery)
        val nightStyle = if (nightMode && GoogleMapStyles.normalize(style) == GoogleMapStyles.DEFAULT) {
            try {
                MapStyleOptions.loadRawResourceStyle(activity, R.raw.map_style_night)
            } catch (e: Resources.NotFoundException) {
                Timber.e(e, "Night map style resource missing")
                null
            }
        } else {
            null
        }
        if (!map.setMapStyle(nightStyle) && nightStyle != null) {
            Timber.w("Night map style failed to parse")
        }
        map.mapType = GoogleMapStyles.mapType(style)
    }

    override fun setTrafficEnabled(enabled: Boolean) {
        map?.isTrafficEnabled = enabled
    }

    override fun applySystemBarInsets(topPx: Int, bottomPx: Int) {
        topInsetPx = topPx
        bottomInsetPx = bottomPx
        applyPadding()
    }

    private fun applyPadding() {
        // Puck in the lower third while driving so the road ahead fills the
        // screen; the insets keep Google's chrome clear of the system bars
        val drivingTop = if (inDrivingView) mapView.height / 3 else 0
        map?.setPadding(0, topInsetPx + drivingTop, 0, bottomInsetPx)
    }

    override fun enableLocationDisplay() {
        val map = map ?: return
        try {
            map.isMyLocationEnabled = true
            // The my-location layer brings its own top-right button; hide
            // it — the recenter FAB already does that job
            map.uiSettings.isMyLocationButtonEnabled = false
        } catch (e: SecurityException) {
            Timber.e(e, "Location permission lost while enabling the my-location layer")
        }
    }

    override fun forwardLocation(location: Location) {
        // The my-location layer tracks the device itself
    }

    override fun animateToFirstFix(target: LatLng) {
        val map = map ?: return
        map.animateCamera(
            CameraUpdateFactory.newLatLngZoom(target.toMap(), MapController.FOLLOW_ZOOM),
            object : GoogleMap.CancelableCallback {
                override fun onFinish() {
                    // Ensure tiles render properly after the initial zoom
                    map.moveCamera(CameraUpdateFactory.newCameraPosition(map.cameraPosition))
                }

                override fun onCancel() {
                    Timber.w("Initial camera animation cancelled")
                }
            }
        )
    }

    override fun easeFollow(target: LatLng, durationMs: Int) {
        map?.animateCamera(CameraUpdateFactory.newLatLng(target.toMap()), durationMs, null)
    }

    override fun easeDrivingCamera(target: LatLng, zoom: Float, bearing: Float, durationMs: Int) {
        val map = map ?: return
        if (!inDrivingView) {
            inDrivingView = true
            applyPadding()
        }

        val cameraPosition = CameraPosition.Builder()
            .target(target.toMap())
            .zoom(zoom)
            .bearing(bearing)
            .tilt(CameraLogic.DRIVING_TILT.toFloat())
            .build()

        map.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition), durationMs, null)
    }

    override fun animateToTopDownFollow(target: LatLng) {
        val map = map ?: return
        leaveDrivingView()
        val cameraPosition = CameraPosition.Builder()
            .target(target.toMap())
            .zoom(MapController.FOLLOW_ZOOM)
            .bearing(0f)
            .tilt(0f)
            .build()
        map.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition))
    }

    override fun animateToRouteOverview(routePoints: List<LatLng>, origin: LatLng) {
        val map = map ?: return
        if (routePoints.isEmpty()) return
        // Clear any driving-view offset first so the overview frames well
        leaveDrivingView()

        val boundsBuilder = LatLngBounds.Builder().include(origin.toMap())
        routePoints.forEach { boundsBuilder.include(it.toMap()) }
        map.animateCamera(
            CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), OVERVIEW_PADDING_PX)
        )
    }

    private fun leaveDrivingView() {
        if (inDrivingView) {
            inDrivingView = false
            applyPadding()
        }
    }

    override fun setPoiMarkers(markers: List<MapController.PoiMarkerSpec>) {
        val map = map ?: return
        poiMarkers.forEach { it.remove() }
        poiMarkers.clear()

        markers.forEach { spec ->
            val marker = map.addMarker(
                MarkerOptions()
                    .position(spec.position.toMap())
                    .title(spec.title)
                    .snippet(spec.snippet)
                    .icon(BitmapDescriptorFactory.defaultMarker(spec.hue))
                    .alpha(spec.alpha)
            )
            marker?.let {
                it.tag = spec.placeId
                poiMarkers.add(it)
            }
        }
    }

    override fun showDestinationMarker(position: LatLng) {
        val map = map ?: return

        // Move the pin rather than ignoring the call. Refusing to touch an
        // existing marker left the previous drive's destination on the map
        // when a new one was chosen — the route was redrawn, the pin wasn't.
        destinationMarker?.let {
            it.position = position.toMap()
            return
        }

        destinationMarker = map.addMarker(
            MarkerOptions()
                .position(position.toMap())
                .title(activity.getString(R.string.destination_marker_title))
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
        )
    }

    override fun clearDestinationMarker() {
        destinationMarker?.remove()
        destinationMarker = null
    }

    override val hasRoutePolyline: Boolean
        get() = routePolyline != null

    override fun showRoutePolyline(points: List<LatLng>) {
        val map = map ?: return
        routePolyline?.remove()
        routePolyline = map.addPolyline(
            PolylineOptions()
                .addAll(points.map { it.toMap() })
                .width(7f)
                .color(ROUTE_COLOR)
                .geodesic(true)
        )
    }

    override fun clearRoutePolyline() {
        routePolyline?.remove()
        routePolyline = null
    }

    override fun showScoutCircle(center: LatLng, radiusMeters: Double) {
        val map = map ?: return
        scoutCircle?.remove()
        scoutCircle = map.addCircle(
            CircleOptions()
                .center(center.toMap())
                .radius(radiusMeters)
                .strokeWidth(2f)
                .strokeColor(SCOUT_STROKE_COLOR)
                .fillColor(SCOUT_FILL_COLOR)
        )
    }

    override fun clearScoutCircle() {
        scoutCircle?.remove()
        scoutCircle = null
    }

    override fun onStart() = mapView.onStart()
    override fun onResume() = mapView.onResume()
    override fun onPause() = mapView.onPause()
    override fun onStop() = mapView.onStop()

    override fun onSaveInstanceState(outState: Bundle) {
        val mapState = Bundle()
        mapView.onSaveInstanceState(mapState)
        outState.putBundle(MAP_VIEW_STATE_KEY, mapState)
    }

    override fun onLowMemory() = mapView.onLowMemory()

    override fun onDestroy() {
        map = null
        host = null
        mapView.onDestroy()
    }

    companion object {
        private const val MAP_VIEW_STATE_KEY = "google_map_view_state"

        private const val OVERVIEW_PADDING_PX = 100
        private const val ROUTE_COLOR = 0xFF0080FF.toInt() // bright blue
        private const val SCOUT_STROKE_COLOR = 0x8834A853.toInt()
        private const val SCOUT_FILL_COLOR = 0x1434A853
    }
}
