package com.spiritwisestudios.gpstracker.data.repository

import com.google.android.libraries.places.api.net.PlacesClient
import com.spiritwisestudios.gpstracker.data.api.PlacesApiService
import com.spiritwisestudios.gpstracker.data.db.dao.PointOfInterestDao
import com.spiritwisestudios.gpstracker.data.db.entity.PointOfInterestEntity
import com.spiritwisestudios.gpstracker.domain.model.PointOfInterest
import com.spiritwisestudios.gpstracker.domain.repository.PlacesRepository
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
    private val placesClient: PlacesClient,
    private val pointOfInterestDao: PointOfInterestDao
) : PlacesRepository {
    
    // Create a PlacesApiService with the provided client
    private val placesApiService = PlacesApiService(placesClient)

    /**
     * Get nearby points of interest as a Flow
     */
    override fun getNearbyPlaces(radius: Int): Flow<List<PointOfInterest>> = flow {
        try {
            val places = placesApiService.getNearbyPlaces(radius)
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