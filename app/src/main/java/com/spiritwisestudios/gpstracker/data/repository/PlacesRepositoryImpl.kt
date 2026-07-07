package com.spiritwisestudios.gpstracker.data.repository

import com.google.android.gms.maps.model.LatLng
import com.spiritwisestudios.gpstracker.data.api.PlacesApiService
import com.spiritwisestudios.gpstracker.data.db.dao.PointOfInterestDao
import com.spiritwisestudios.gpstracker.data.db.entity.PointOfInterestEntity
import com.spiritwisestudios.gpstracker.domain.model.PointOfInterest
import com.spiritwisestudios.gpstracker.domain.repository.PlacesRepository
import com.spiritwisestudios.gpstracker.util.RouteSampler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of PlacesRepository that uses the Places API and Room database
 */
@Singleton
class PlacesRepositoryImpl @Inject constructor(
    private val placesApiService: PlacesApiService,
    private val pointOfInterestDao: PointOfInterestDao
) : PlacesRepository {

    companion object {
        // Keep corridor discovery bounded: at most this many nearby searches
        // per route, spacing samples at least 2x the search radius apart.
        private const val MAX_ROUTE_SAMPLES = 15
        private const val MAX_ROUTE_POIS = 60
    }

    /**
     * Get points of interest around a location as a Flow
     */
    override fun getNearbyPlaces(center: LatLng, radius: Int): Flow<List<PointOfInterest>> = flow {
        try {
            val places = placesApiService.getNearbyPlaces(center, radius)
            Timber.d("Successfully fetched ${places.size} nearby places")
            emit(places)
        } catch (e: Exception) {
            when (e) {
                is SecurityException -> {
                    Timber.e(e, "Security error fetching nearby places: ${e.message}")
                    throw e // Rethrow security exceptions so they can be handled by the ViewModel
                }
                else -> {
                    Timber.e(e, "Error fetching nearby places: ${e.javaClass.simpleName} - ${e.message}")
                    emit(emptyList())
                }
            }
        }
    }

    /**
     * Get points of interest along a route corridor, ordered by route progress.
     */
    override suspend fun getPlacesAlongRoute(route: List<LatLng>, searchRadius: Int): List<PointOfInterest> {
        if (route.isEmpty()) return emptyList()

        var samples = RouteSampler.samplePoints(route, intervalMeters = searchRadius * 2f)
        if (samples.size > MAX_ROUTE_SAMPLES) {
            // Thin out evenly rather than truncating so we still cover the whole route
            val stride = Math.ceil(samples.size / MAX_ROUTE_SAMPLES.toDouble()).toInt()
            samples = samples.filterIndexed { index, _ -> index % stride == 0 }
        }

        val seenPlaceIds = mutableSetOf<String>()
        val pois = mutableListOf<PointOfInterest>()

        for (sample in samples) {
            try {
                for (poi in placesApiService.getNearbyPlaces(sample, searchRadius)) {
                    val key = poi.placeId ?: poi.id
                    if (seenPlaceIds.add(key)) {
                        pois.add(poi)
                    }
                }
            } catch (e: Exception) {
                // One failed sample shouldn't sink the whole corridor
                Timber.e(e, "Nearby search failed for route sample $sample")
            }
            if (pois.size >= MAX_ROUTE_POIS) break
        }

        Timber.d("Found ${pois.size} places along route (${samples.size} samples)")
        return pois
    }

    /**
     * Get detailed information about a specific place
     */
    override suspend fun getPlaceDetails(placeId: String): Result<PointOfInterest> {
        // First try to get from the database
        val localPoi = pointOfInterestDao.getPointOfInterestById(placeId)

        if (localPoi != null) {
            return Result.success(localPoi.toDomainModel())
        }

        // If not in database, try to get from API
        return try {
            Result.success(placesApiService.getPlaceDetails(placeId))
        } catch (e: Exception) {
            Timber.e(e, "Error fetching place details")
            Result.failure(e)
        }
    }

    /**
     * Save a visited point of interest to the database
     */
    override suspend fun saveVisitedPlace(pointOfInterest: PointOfInterest): Result<Unit> {
        return try {
            val entity = PointOfInterestEntity.fromDomainModel(pointOfInterest)
            pointOfInterestDao.insertPointOfInterest(entity)
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Error saving visited place")
            Result.failure(e)
        }
    }

    /**
     * Get all visited places from database
     */
    override fun getVisitedPlaces(): Flow<List<PointOfInterest>> {
        return pointOfInterestDao.getVisitedPlaces()
            .map { entities -> entities.map { it.toDomainModel() } }
    }
}
