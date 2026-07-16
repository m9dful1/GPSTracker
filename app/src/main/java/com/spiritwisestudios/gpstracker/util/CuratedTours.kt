package com.spiritwisestudios.gpstracker.util

import com.spiritwisestudios.gpstracker.domain.model.LatLng
import com.spiritwisestudios.gpstracker.domain.model.TourFocus
import com.spiritwisestudios.gpstracker.domain.model.TourLength

/**
 * Hand-picked tours around famous destinations. The Take a Tour sheet
 * offers whichever of these are close to the user; each carries its own
 * natural length and focus (the Vegas Strip is a short neon crawl, Desert
 * View Drive a long scenic run). Stops are still discovered and scripted
 * live — curation here means the center, length, and lean are known-good.
 */
object CuratedTours {

    data class CuratedTour(
        val name: String,
        val center: LatLng,
        val length: TourLength,
        val focus: TourFocus
    )

    val ALL = listOf(
        CuratedTour("Las Vegas Strip", LatLng(36.1147, -115.1728), TourLength.SHORT, TourFocus.FOOD_AND_FUN),
        CuratedTour("Hoover Dam", LatLng(36.0161, -114.7377), TourLength.SHORT, TourFocus.HISTORY_AND_CULTURE),
        CuratedTour("Grand Canyon South Rim", LatLng(36.0544, -112.1401), TourLength.LONG, TourFocus.NATURE_AND_VIEWS),
        CuratedTour("Zion Canyon", LatLng(37.2982, -113.0263), TourLength.MEDIUM, TourFocus.NATURE_AND_VIEWS),
        CuratedTour("Sedona Red Rocks", LatLng(34.8697, -111.7610), TourLength.MEDIUM, TourFocus.NATURE_AND_VIEWS),
        CuratedTour("Monument Valley", LatLng(36.9980, -110.0985), TourLength.LONG, TourFocus.NATURE_AND_VIEWS),
        CuratedTour("Death Valley", LatLng(36.4623, -116.8656), TourLength.LONG, TourFocus.NATURE_AND_VIEWS),
        CuratedTour("Temple Square & Salt Lake City", LatLng(40.7707, -111.8911), TourLength.SHORT, TourFocus.HISTORY_AND_CULTURE),
        CuratedTour("San Francisco Waterfront & Golden Gate", LatLng(37.8080, -122.4177), TourLength.MEDIUM, TourFocus.BALANCED),
        CuratedTour("Hollywood & Beverly Hills", LatLng(34.0928, -118.3287), TourLength.MEDIUM, TourFocus.FOOD_AND_FUN),
        CuratedTour("San Diego Bay & Balboa Park", LatLng(32.7157, -117.1611), TourLength.MEDIUM, TourFocus.BALANCED),
        CuratedTour("Manhattan Highlights", LatLng(40.7580, -73.9855), TourLength.MEDIUM, TourFocus.BALANCED),
        CuratedTour("National Mall", LatLng(38.8893, -77.0502), TourLength.SHORT, TourFocus.HISTORY_AND_CULTURE),
        CuratedTour("Freedom Trail & Boston Harbor", LatLng(42.3601, -71.0589), TourLength.SHORT, TourFocus.HISTORY_AND_CULTURE),
        CuratedTour("Chicago Loop & Lakefront", LatLng(41.8827, -87.6233), TourLength.MEDIUM, TourFocus.BALANCED),
        CuratedTour("French Quarter", LatLng(29.9584, -90.0644), TourLength.SHORT, TourFocus.FOOD_AND_FUN),
        CuratedTour("Seattle Center & Waterfront", LatLng(47.6205, -122.3493), TourLength.SHORT, TourFocus.BALANCED),
        CuratedTour("Miami Beach & Art Deco District", LatLng(25.7817, -80.1310), TourLength.SHORT, TourFocus.FOOD_AND_FUN)
    )

    /** Within comfortable driving reach of a tour's starting point. */
    const val NEARBY_RADIUS_METERS = 150_000f

    /** Curated tours close to a location, nearest first. */
    fun near(
        location: LatLng,
        maxDistanceMeters: Float = NEARBY_RADIUS_METERS
    ): List<CuratedTour> =
        ALL.filter { GeoUtils.distanceMeters(location, it.center) <= maxDistanceMeters }
            .sortedBy { GeoUtils.distanceMeters(location, it.center) }
}
