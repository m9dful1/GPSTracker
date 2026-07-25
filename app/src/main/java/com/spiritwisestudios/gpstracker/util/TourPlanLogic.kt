package com.spiritwisestudios.gpstracker.util

import com.spiritwisestudios.gpstracker.domain.model.LatLng
import com.spiritwisestudios.gpstracker.domain.model.PointOfInterest
import com.spiritwisestudios.gpstracker.domain.model.TourFocus
import com.spiritwisestudios.gpstracker.domain.model.TourLength

/**
 * Pure planning logic for Take a Tour: which places make the tour and in
 * what order. Kept free of Android and network classes so it can be unit
 * tested on the JVM; the ViewModel supplies the candidates and drives the
 * routing.
 */
object TourPlanLogic {

    /** How far around the tour center to look for candidate places. */
    fun poiSearchRadiusMeters(length: TourLength): Int =
        (length.meters / 5).coerceIn(1_500, 8_000)

    /**
     * Keep stops spread out: a tour where every stop is on the same block
     * reads as one stop. Scaled so the spacing tightens for short tours.
     */
    fun minSpacingMeters(length: TourLength): Float =
        length.meters / (length.stopCount * 2f)

    /** How strongly a category matches the chosen focus. */
    internal fun focusWeight(category: String, focus: TourFocus): Double = when (focus) {
        TourFocus.BALANCED -> 1.0
        TourFocus.HISTORY_AND_CULTURE ->
            if (category in setOf("HISTORICAL", "CULTURAL", "ARCHITECTURAL")) 2.5 else 1.0
        TourFocus.NATURE_AND_VIEWS ->
            if (category == "NATURAL") 2.5 else 1.0
        TourFocus.FOOD_AND_FUN ->
            if (category in setOf("DINING", "ENTERTAINMENT", "SHOPPING")) 2.5 else 1.0
    }

    /**
     * Tour-worthiness of one candidate: intrinsic category interest (the
     * same weighting narration priority uses), the crowd's rating when the
     * data source has one, the user's preferred categories, all scaled by
     * the chosen focus.
     */
    internal fun scoreStop(
        poi: PointOfInterest,
        focus: TourFocus,
        preferredCategories: Set<String>
    ): Double {
        var score = 1.0 + TourLogic.categoryInterestWeight(poi.category)
        poi.rating?.let { score += it / 2.0 }
        if (poi.category in preferredCategories) score += 1.0
        return score * focusWeight(poi.category, focus)
    }

    /**
     * Pick the tour's stops: best-scored first, skipping places closer than
     * [minSpacingMeters] to an already-chosen stop so the tour spreads
     * across the area. If the spacing rule can't fill the tour (a compact
     * downtown), the best remaining places fill it anyway.
     */
    fun selectStops(
        candidates: List<PointOfInterest>,
        count: Int,
        minSpacingMeters: Float,
        focus: TourFocus,
        preferredCategories: Set<String>
    ): List<PointOfInterest> {
        val ranked = candidates.sortedByDescending { scoreStop(it, focus, preferredCategories) }

        val picked = mutableListOf<PointOfInterest>()
        for (poi in ranked) {
            if (picked.size >= count) break
            val crowded = picked.any {
                GeoUtils.distanceMeters(it.latLng, poi.latLng) < minSpacingMeters
            }
            if (!crowded) picked.add(poi)
        }
        if (picked.size < count) {
            for (poi in ranked) {
                if (picked.size >= count) break
                if (poi !in picked) picked.add(poi)
            }
        }
        return picked
    }

    /**
     * Put the stops in driving order with a nearest-neighbor walk from the
     * start. Not optimal, but tours are small (≤15 stops) and the routing
     * service smooths the path onto real roads anyway.
     */
    fun orderStops(start: LatLng, stops: List<PointOfInterest>): List<PointOfInterest> {
        val remaining = stops.toMutableList()
        val ordered = mutableListOf<PointOfInterest>()
        var cursor = start
        while (remaining.isNotEmpty()) {
            val next = remaining.minByOrNull { GeoUtils.distanceMeters(cursor, it.latLng) }!!
            remaining.remove(next)
            ordered.add(next)
            cursor = next.latLng
        }
        return ordered
    }

    /**
     * The places to watch along a drive: the tour's own stops first, then
     * whatever discovery found in the corridor around the route.
     *
     * Order is the point. Corridor discovery caps how much it returns, so a
     * planned stop listed after it could be the one that gets cut — and a
     * place the user chose is the reason the drive exists. Duplicates keep the
     * planned copy, matched on the provider's place id where there is one.
     */
    fun corridorPlaces(
        plannedStops: List<PointOfInterest>,
        discovered: List<PointOfInterest>
    ): List<PointOfInterest> {
        return (plannedStops + discovered).distinctBy { it.placeId ?: it.id }
    }
}
