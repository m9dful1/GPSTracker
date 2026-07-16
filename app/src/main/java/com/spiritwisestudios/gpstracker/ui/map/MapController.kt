package com.spiritwisestudios.gpstracker.ui.map

import android.location.Location
import android.os.Bundle
import android.view.ViewGroup
import com.spiritwisestudios.gpstracker.domain.model.LatLng

/**
 * Everything MainActivity needs from a map, so the map SDK is swappable per
 * the map-provider setting: [MapLibreMapController] renders OpenFreeMap
 * styles, [GoogleMapController] renders Google Maps. Each controller owns
 * its own map view (created into the activity's container) and needs the
 * activity lifecycle forwarded to it.
 *
 * Zoom levels are in Google zoom units throughout; MapLibre renders 512px
 * tiles, so its implementation sits one zoom level below for the same view.
 */
interface MapController {

    /** Implemented by the hosting activity. */
    interface Host {
        /** The map is ready: restore the style, then enable location. */
        fun onMapReady()

        /** The user panned/zoomed manually (stop following them). */
        fun onUserGesture()

        /** Long-press anywhere on the map. */
        fun onMapLongClick(point: LatLng)

        /** A POI marker was tapped; [placeId] keys the details lookup. */
        fun onMarkerClick(placeId: String)
    }

    data class PoiMarkerSpec(
        val placeId: String,
        val position: LatLng,
        val title: String,
        val snippet: String,
        /** Marker color as a hue in [0, 360) (Google marker convention). */
        val hue: Float,
        /** 1 = normal; visited places fade below 1. */
        val alpha: Float
    )

    val isReady: Boolean

    // --- Lifecycle. The map view renders with its own GL surface and needs
    // --- every activity lifecycle event forwarded explicitly.

    /** Create the map view inside [container] and start loading the map. */
    fun onCreate(container: ViewGroup, savedInstanceState: Bundle?, host: Host)
    fun onStart()
    fun onResume()
    fun onPause()
    fun onStop()
    fun onSaveInstanceState(outState: Bundle)
    fun onLowMemory()
    fun onDestroy()

    // --- Appearance

    /**
     * Apply a layers-sheet style selection ([com.spiritwisestudios.gpstracker.util.MapStyles]
     * values for MapLibre, [com.spiritwisestudios.gpstracker.util.GoogleMapStyles]
     * values for Google). The default style follows [nightMode] so night
     * drives aren't a full-screen white blast.
     */
    fun applyStyle(style: Int, nightMode: Boolean)

    /** Traffic overlay; only the Google map renders one (no-op otherwise). */
    fun setTrafficEnabled(enabled: Boolean)

    /** Keep the map's own chrome (compass, logo, ...) clear of system bars. */
    fun applySystemBarInsets(topPx: Int, bottomPx: Int)

    // --- The blue position dot. Location permission must already be
    // --- granted when enabling.

    fun enableLocationDisplay()

    /** Forward a location fix to the dot (no-op when the map draws its own). */
    fun forwardLocation(location: Location)

    // --- Camera

    /** First fix of the session: fly to the user at follow zoom. */
    fun animateToFirstFix(target: LatLng)

    /** Plain follow: pan to the target keeping current zoom/bearing/tilt. */
    fun easeFollow(target: LatLng, durationMs: Int)

    /**
     * Google-style driving view: heading-up, tilted toward the horizon,
     * with the position puck in the lower third so the road ahead fills
     * the screen.
     */
    fun easeDrivingCamera(target: LatLng, zoom: Float, bearing: Float, durationMs: Int)

    /** Settle back to the flat, north-up follow view once a drive ends. */
    fun animateToTopDownFollow(target: LatLng)

    /** Frame a whole route, flat and north-up. */
    fun animateToRouteOverview(routePoints: List<LatLng>, origin: LatLng)

    // --- Annotations

    /** Replace all POI markers. */
    fun setPoiMarkers(markers: List<PoiMarkerSpec>)

    fun showDestinationMarker(position: LatLng)
    fun clearDestinationMarker()

    val hasRoutePolyline: Boolean
    fun showRoutePolyline(points: List<LatLng>)
    fun clearRoutePolyline()

    /** Outline of an area scouted via long-press. */
    fun showScoutCircle(center: LatLng, radiusMeters: Double)
    fun clearScoutCircle()

    companion object {
        /** Camera-follow zoom, in Google zoom units. */
        const val FOLLOW_ZOOM = 18f
    }
}
