package com.spiritwisestudios.gpstracker.domain.usecase

import com.google.android.gms.maps.model.LatLng
import com.spiritwisestudios.gpstracker.domain.model.PointOfInterest
import com.spiritwisestudios.gpstracker.domain.model.UserPreferences
import com.spiritwisestudios.gpstracker.domain.repository.PlacesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * Use case for retrieving nearby points of interest.
 */
class GetNearbyPointsOfInterestUseCase @Inject constructor(
    private val placesRepository: PlacesRepository
) {
    /**
     * Get nearby points of interest filtered based on user preferences.
     *
     * @param userPreferences User preferences for filtering
     * @param radiusInMeters Search radius in meters
     * @return Flow of filtered points of interest
     */
    operator fun invoke(
        userPreferences: UserPreferences? = null,
        radiusInMeters: Int = userPreferences?.notifyDistance ?: 500
    ): Flow<List<PointOfInterest>> {
        return placesRepository.getNearbyPlaces(radiusInMeters).map { places ->
            // Filter places based on user preferences
            if (userPreferences != null && userPreferences.preferredCategories.isNotEmpty()) {
                places.filter { 
                    userPreferences.preferredCategories.any { category -> 
                        it.category.contains(category.name, ignoreCase = true) 
                    }
                }
            } else {
                places
            }
        }
    }
} 