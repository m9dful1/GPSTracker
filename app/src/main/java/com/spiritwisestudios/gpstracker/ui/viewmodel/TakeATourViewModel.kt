package com.spiritwisestudios.gpstracker.ui.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spiritwisestudios.gpstracker.R
import com.spiritwisestudios.gpstracker.data.api.NearbyCityApiService
import com.spiritwisestudios.gpstracker.data.api.PlacesApi
import com.spiritwisestudios.gpstracker.data.repository.UserPreferencesRepository
import com.spiritwisestudios.gpstracker.domain.model.LatLng
import com.spiritwisestudios.gpstracker.domain.model.PointOfInterest
import com.spiritwisestudios.gpstracker.domain.model.TourFocus
import com.spiritwisestudios.gpstracker.domain.model.TourLength
import com.spiritwisestudios.gpstracker.domain.model.TourPlan
import com.spiritwisestudios.gpstracker.domain.service.ContentService
import com.spiritwisestudios.gpstracker.util.TourPlanLogic
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Plans a Take a Tour outing: finds candidate places around the chosen
 * center, picks and orders the most tour-worthy stops, and pre-writes
 * their narration scripts (Gemini when configured) so the guide never
 * stalls mid-drive. Activity-scoped so preloading survives the sheet
 * dismissing and the tour starting.
 */
@HiltViewModel
class TakeATourViewModel @Inject constructor(
    private val placesApi: PlacesApi,
    private val nearbyCityApiService: NearbyCityApiService,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val contentService: ContentService
) : ViewModel() {

    sealed class PlanState {
        data object Idle : PlanState()
        data class Planning(val statusRes: Int) : PlanState()
        data class Ready(val plan: TourPlan) : PlanState()
        data class Failed(val messageRes: Int) : PlanState()
    }

    private val _planState = MutableLiveData<PlanState>(PlanState.Idle)
    val planState: LiveData<PlanState> = _planState

    private val _nearbyCities = MutableLiveData<List<NearbyCityApiService.City>>()
    val nearbyCities: LiveData<List<NearbyCityApiService.City>> = _nearbyCities

    /** Load the city dropdown; keeps the last result while the sheet reopens. */
    fun loadNearbyCities(location: LatLng) {
        if (_nearbyCities.value != null) return
        viewModelScope.launch {
            _nearbyCities.value = nearbyCityApiService.nearbyCities(location)
        }
    }

    fun planTour(name: String, center: LatLng, length: TourLength, focus: TourFocus) {
        if (_planState.value is PlanState.Planning) return
        viewModelScope.launch {
            try {
                _planState.value = PlanState.Planning(R.string.tour_planning_places)

                val candidates = placesApi.getNearbyPlaces(
                    center, TourPlanLogic.poiSearchRadiusMeters(length)
                )
                if (candidates.size < MIN_STOPS) {
                    _planState.value = PlanState.Failed(R.string.tour_planning_no_places)
                    return@launch
                }

                val preferences = userPreferencesRepository.userPreferencesFlow.first()
                val preferredCategories = preferences.preferredCategories.map { it.name }.toSet()

                _planState.value = PlanState.Planning(R.string.tour_planning_route)
                val stops = TourPlanLogic.orderStops(
                    center,
                    TourPlanLogic.selectStops(
                        candidates,
                        length.stopCount,
                        TourPlanLogic.minSpacingMeters(length),
                        focus,
                        preferredCategories
                    )
                )

                Timber.i("Planned \"$name\": ${stops.size} stops from ${candidates.size} candidates")
                _planState.value = PlanState.Ready(TourPlan(name, center, stops, center))

                preloadScripts(stops)
            } catch (e: Exception) {
                Timber.e(e, "Tour planning failed")
                _planState.value = PlanState.Failed(R.string.tour_planning_failed)
            }
        }
    }

    /** The sheet took the plan; don't re-deliver it next time it opens. */
    fun consumePlan() {
        _planState.value = PlanState.Idle
    }

    /**
     * Warm the narration cache for every stop, in tour order so the first
     * stops are ready first. Uses the on-demand content path (the user
     * asked for this tour, so it isn't gated like speculative prefetch);
     * each script is generated once and cached in Room, so the guide reads
     * instantly at the stop even if connectivity drops mid-drive.
     */
    private fun preloadScripts(stops: List<PointOfInterest>) {
        viewModelScope.launch {
            val preferences = userPreferencesRepository.userPreferencesFlow.first()
            var ready = 0
            for (stop in stops) {
                try {
                    contentService.getContentForPlace(stop, preferences)
                    ready++
                } catch (e: Exception) {
                    Timber.w(e, "Couldn't preload a script for ${stop.name}")
                }
            }
            Timber.i("Preloaded tour scripts for $ready/${stops.size} stops")
        }
    }

    companion object {
        private const val MIN_STOPS = 3
    }
}
