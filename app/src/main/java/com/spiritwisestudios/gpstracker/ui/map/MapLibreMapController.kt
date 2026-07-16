package com.spiritwisestudios.gpstracker.ui.map

import android.app.Activity
import android.location.Location
import android.os.Bundle
import android.view.ViewGroup
import com.spiritwisestudios.gpstracker.domain.model.LatLng
import com.spiritwisestudios.gpstracker.util.CameraLogic
import com.spiritwisestudios.gpstracker.util.GeoUtils
import com.spiritwisestudios.gpstracker.util.MapStyles
import com.spiritwisestudios.gpstracker.util.MarkerIcons
import com.spiritwisestudios.gpstracker.util.MarkerStyling
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.Polygon
import org.maplibre.android.annotations.PolygonOptions
import org.maplibre.android.annotations.Polyline
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng as MapLatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import timber.log.Timber

/**
 * [MapController] over the MapLibre SDK rendering OpenFreeMap's hosted
 * styles — free, keyless, OpenStreetMap data.
 */
class MapLibreMapController(private val activity: Activity) : MapController {

    private lateinit var mapView: MapView
    private var map: MapLibreMap? = null
    private var host: MapController.Host? = null

    // MapLibre markers have no tag slot; keyed by Marker.id instead
    private val poiMarkers = mutableListOf<Marker>()
    private val markerPlaceIds = mutableMapOf<Long, String>()

    private var destinationMarker: Marker? = null
    private var routePolyline: Polyline? = null
    private var scoutCircle: Polygon? = null

    // The location component binds to a style; remember the ask so it can
    // (re-)activate whenever a style finishes loading
    private var locationDisplayRequested = false

    // Insets arrive before the map is ready; applied once on ready
    private var pendingInsets: Pair<Int, Int>? = null
    private var insetsApplied = false

    override val isReady: Boolean
        get() = map != null

    /** Domain coordinates → MapLibre coordinates at the map boundary. */
    private fun LatLng.toMap() = MapLatLng(latitude, longitude)

    override fun onCreate(container: ViewGroup, savedInstanceState: Bundle?, host: MapController.Host) {
        this.host = host
        mapView = MapView(activity)
        container.addView(mapView)
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { map -> onMapReady(map) }
    }

    private fun onMapReady(map: MapLibreMap) {
        this.map = map

        map.uiSettings.isCompassEnabled = true
        map.uiSettings.isRotateGesturesEnabled = true
        map.uiSettings.isTiltGesturesEnabled = true

        // Stop following the user when they pan/zoom manually (Google Maps
        // behavior); the recenter FAB turns following back on.
        map.addOnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                host?.onUserGesture()
            }
        }

        map.addOnMapLongClickListener { point ->
            host?.onMapLongClick(LatLng(point.latitude, point.longitude))
            true
        }

        map.setOnMarkerClickListener { marker ->
            val placeId = markerPlaceIds[marker.id]
            if (placeId != null) {
                host?.onMarkerClick(placeId)
                true // consume the event (don't show the info window)
            } else {
                false
            }
        }

        pendingInsets?.let { (top, bottom) -> applyChromeMargins(top, bottom) }
        host?.onMapReady()
    }

    /**
     * Load a style (the map renders nothing until one is set). Markers
     * survive style swaps, but the location component binds to a style, so
     * it re-activates in the loaded callback.
     */
    override fun applyStyle(style: Int, nightMode: Boolean) {
        val map = map ?: return
        val styleUrl = MapStyles.styleUrl(style, nightMode)
        map.setStyle(Style.Builder().fromUri(styleUrl)) { loadedStyle ->
            if (locationDisplayRequested) {
                activateLocationComponent(loadedStyle)
            }
        }
    }

    override fun setTrafficEnabled(enabled: Boolean) {
        // OpenFreeMap styles carry no traffic layer
    }

    override fun applySystemBarInsets(topPx: Int, bottomPx: Int) {
        pendingInsets = topPx to bottomPx
        if (isReady) applyChromeMargins(topPx, bottomPx)
    }

    /** Move the map's own chrome (compass, logo, attribution) clear of the bars. */
    private fun applyChromeMargins(topPx: Int, bottomPx: Int) {
        if (insetsApplied) return // margins are adjusted additively; only once
        val ui = map?.uiSettings ?: return
        insetsApplied = true
        ui.setCompassMargins(
            ui.compassMarginLeft,
            ui.compassMarginTop + topPx,
            ui.compassMarginRight,
            ui.compassMarginBottom
        )
        ui.setLogoMargins(
            ui.logoMarginLeft,
            ui.logoMarginTop,
            ui.logoMarginRight,
            ui.logoMarginBottom + bottomPx
        )
        ui.setAttributionMargins(
            ui.attributionMarginLeft,
            ui.attributionMarginTop,
            ui.attributionMarginRight,
            ui.attributionMarginBottom + bottomPx
        )
    }

    override fun enableLocationDisplay() {
        locationDisplayRequested = true
        map?.style?.takeIf { it.isFullyLoaded }?.let { activateLocationComponent(it) }
    }

    // The location component renders the blue dot; the camera stays ours
    // (CameraMode.NONE) — following is handled explicitly by the activity
    private fun activateLocationComponent(style: Style) {
        val map = map ?: return
        try {
            map.locationComponent.apply {
                activateLocationComponent(
                    LocationComponentActivationOptions.builder(activity, style).build()
                )
                isLocationComponentEnabled = true
                cameraMode = CameraMode.NONE
            }
        } catch (e: SecurityException) {
            Timber.e(e, "Location permission lost while enabling the location component")
        }
    }

    // The location component draws the dot from location updates we forward;
    // MapLibre's own engine is off, keeping one source of truth
    override fun forwardLocation(location: Location) {
        val map = map ?: return
        if (map.locationComponent.isLocationComponentActivated) {
            map.locationComponent.forceLocationUpdate(location)
        }
    }

    override fun animateToFirstFix(target: LatLng) {
        map?.animateCamera(
            CameraUpdateFactory.newLatLngZoom(target.toMap(), followZoom())
        )
    }

    override fun easeFollow(target: LatLng, durationMs: Int) {
        map?.easeCamera(CameraUpdateFactory.newLatLng(target.toMap()), durationMs, false)
    }

    override fun easeDrivingCamera(target: LatLng, zoom: Float, bearing: Float, durationMs: Int) {
        val map = map ?: return
        val cameraPosition = CameraPosition.Builder()
            .target(target.toMap())
            .zoom((zoom - ZOOM_OFFSET).toDouble())
            .bearing(bearing.toDouble())
            .tilt(CameraLogic.DRIVING_TILT)
            // Puck in the lower third so the road ahead fills the screen
            .padding(0.0, mapView.height / 3.0, 0.0, 0.0)
            .build()

        // Linear ease so the camera glides between fixes instead of lurching
        map.easeCamera(CameraUpdateFactory.newCameraPosition(cameraPosition), durationMs, false)
    }

    override fun animateToTopDownFollow(target: LatLng) {
        val cameraPosition = CameraPosition.Builder()
            .target(target.toMap())
            .zoom(followZoom())
            .bearing(0.0)
            .tilt(0.0)
            .padding(0.0, 0.0, 0.0, 0.0)
            .build()
        map?.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition))
    }

    override fun animateToRouteOverview(routePoints: List<LatLng>, origin: LatLng) {
        val map = map ?: return
        if (routePoints.isEmpty()) return

        val boundsBuilder = LatLngBounds.Builder().include(origin.toMap())
        routePoints.forEach { boundsBuilder.include(it.toMap()) }

        // Clear any driving-view offset first, and fit the bounds flat and
        // north-up, or the overview frames the route badly
        map.moveCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder(map.cameraPosition)
                    .padding(0.0, 0.0, 0.0, 0.0)
                    .build()
            )
        )
        map.animateCamera(
            CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 0.0, 0.0, OVERVIEW_PADDING_PX)
        )
    }

    override fun setPoiMarkers(markers: List<MapController.PoiMarkerSpec>) {
        val map = map ?: return
        poiMarkers.forEach { map.removeMarker(it) }
        poiMarkers.clear()
        markerPlaceIds.clear()

        markers.forEach { spec ->
            val marker = map.addMarker(
                MarkerOptions()
                    .position(spec.position.toMap())
                    .title(spec.title)
                    .snippet(spec.snippet)
                    .icon(MarkerIcons.pin(activity, spec.hue, spec.alpha))
            )
            poiMarkers.add(marker)
            markerPlaceIds[marker.id] = spec.placeId
        }
    }

    override fun showDestinationMarker(position: LatLng) {
        val map = map ?: return
        if (destinationMarker != null) return
        destinationMarker = map.addMarker(
            MarkerOptions()
                .position(position.toMap())
                .title("Destination")
                .icon(MarkerIcons.pin(activity, MarkerStyling.HUE_RED))
        )
    }

    override fun clearDestinationMarker() {
        destinationMarker?.let { map?.removeMarker(it) }
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
                .width(4f)
                .color(ROUTE_COLOR)
        )
    }

    override fun clearRoutePolyline() {
        routePolyline?.let { map?.removePolyline(it) }
        routePolyline = null
    }

    override fun showScoutCircle(center: LatLng, radiusMeters: Double) {
        val map = map ?: return
        scoutCircle?.let { map.removePolygon(it) }
        // MapLibre's simple annotations have no circle; a 64-gon reads the same
        scoutCircle = map.addPolygon(
            PolygonOptions()
                .addAll(GeoUtils.circlePoints(center, radiusMeters).map { it.toMap() })
                .strokeColor(SCOUT_STROKE_COLOR)
                .fillColor(SCOUT_FILL_COLOR)
        )
    }

    override fun clearScoutCircle() {
        scoutCircle?.let { map?.removePolygon(it) }
        scoutCircle = null
    }

    override fun onStart() = mapView.onStart()
    override fun onResume() = mapView.onResume()
    override fun onPause() = mapView.onPause()
    override fun onStop() = mapView.onStop()
    override fun onSaveInstanceState(outState: Bundle) = mapView.onSaveInstanceState(outState)
    override fun onLowMemory() = mapView.onLowMemory()

    override fun onDestroy() {
        map = null
        host = null
        mapView.onDestroy()
    }

    private fun followZoom(): Double = (MapController.FOLLOW_ZOOM - ZOOM_OFFSET).toDouble()

    companion object {
        // MapLibre renders 512px tiles, so its zoom levels sit one below
        // Google's for the same view; offsets the interface's Google-unit
        // zooms (including CameraLogic's Google-tuned zoom curve).
        private const val ZOOM_OFFSET = 1f

        private const val OVERVIEW_PADDING_PX = 100
        private const val ROUTE_COLOR = 0xFF0080FF.toInt() // bright blue
        private const val SCOUT_STROKE_COLOR = 0x8834A853.toInt()
        private const val SCOUT_FILL_COLOR = 0x1434A853
    }
}
