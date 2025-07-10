package com.spiritwisestudios.gpstracker.data.repository

import com.google.android.gms.maps.model.LatLng
import com.spiritwisestudios.gpstracker.data.api.PlacesApiService
import com.spiritwisestudios.gpstracker.data.db.dao.PointOfInterestDao
import com.spiritwisestudios.gpstracker.data.db.entity.PointOfInterestEntity
import com.spiritwisestudios.gpstracker.domain.model.PointOfInterest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import timber.log.Timber

/**
 * Repository that handles operations related to places and points of interest
 */
class PlacesRepository(
    private val placesApiService: PlacesApiService,
    private val pointOfInterestDao: PointOfInterestDao
) {
    /**
     * Get nearby points of interest as a Flow
     */
    fun getNearbyPlaces(radius: Int = 500): Flow<List<PointOfInterest>> = flow {
        try {
            val places = placesApiService.getNearbyPlaces(radius)
            emit(places)
        } catch (e: Exception) {
            Timber.e(e, "Error fetching nearby places")
            emit(emptyList())
        }
    }

    /**
     * Get detailed information about a specific place
     */
    suspend fun getPlaceDetails(placeId: String): Result<PointOfInterest> {
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
    suspend fun saveVisitedPlace(pointOfInterest: PointOfInterest): Result<Unit> {
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
    fun getVisitedPlaces(): Flow<List<PointOfInterest>> {
        return pointOfInterestDao.getVisitedPlaces()
            .map { entities -> entities.map { it.toDomainModel() } }
    }
} 