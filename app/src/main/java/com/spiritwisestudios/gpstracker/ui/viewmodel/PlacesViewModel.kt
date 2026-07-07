package com.spiritwisestudios.gpstracker.ui.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.asLiveData
import com.google.android.gms.maps.model.LatLng
import com.spiritwisestudios.gpstracker.domain.repository.PlacesRepository
import com.spiritwisestudios.gpstracker.domain.model.PointOfInterest
import com.spiritwisestudios.gpstracker.domain.model.TourContent
import com.spiritwisestudios.gpstracker.domain.model.UserPreferences
import com.spiritwisestudios.gpstracker.domain.service.AudioService
import com.spiritwisestudios.gpstracker.data.repository.TourContentRepository
import com.spiritwisestudios.gpstracker.data.repository.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeoutException
import javax.inject.Inject

/**
 * ViewModel for places and points of interest in the city tour guide
 */
@HiltViewModel
class PlacesViewModel @Inject constructor(
    private val placesRepository: PlacesRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val tourContentRepository: TourContentRepository,
    private val audioService: AudioService
) : ViewModel() {

    private val _nearbyPlaces = MutableLiveData<List<PointOfInterest>>(emptyList())
    val nearbyPlaces: LiveData<List<PointOfInterest>> = _nearbyPlaces

    private val _selectedPlace = MutableLiveData<PointOfInterest?>()
    val selectedPlace: LiveData<PointOfInterest?> = _selectedPlace
    
    // LiveData for visited places, directly from the Flow
    val visitedPlaces = placesRepository.getVisitedPlaces().asLiveData()
    
    // User preferences from the repository
    val userPreferences = userPreferencesRepository.userPreferencesFlow.asLiveData()
    
    // Tour content for the selected place
    private val _selectedPlaceContent = MutableLiveData<TourContent?>()
    val selectedPlaceContent: LiveData<TourContent?> = _selectedPlaceContent
    
    // Audio speaking status
    private val _speakingStatus = MutableLiveData<AudioService.SpeakingStatus?>()
    val speakingStatus: LiveData<AudioService.SpeakingStatus?> = _speakingStatus
    
    // Content generation status
    private val _contentGenerationStatus = MutableLiveData<TourContentRepository.ContentGenerationResult?>()
    val contentGenerationStatus: LiveData<TourContentRepository.ContentGenerationResult?> = _contentGenerationStatus

    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String?>(null)
    val error: LiveData<String?> = _error
    
    init {
        // Initialize the audio service with the current user preferences
        viewModelScope.launch {
            userPreferencesRepository.userPreferencesFlow.collectLatest { prefs ->
                audioService.initialize(prefs)
            }
        }
    }

    /**
     * Fetch places around a location from the repository
     */
    fun fetchNearbyPlaces(center: LatLng, radius: Int = 500) {
        _isLoading.value = true
        _error.value = null

        placesRepository.getNearbyPlaces(center, radius)
            .onEach { places ->
                _nearbyPlaces.value = places
                _isLoading.value = false
            }
            .catch { e ->
                Timber.e(e, "Error fetching nearby places: ${e.javaClass.simpleName} - ${e.message}")
                
                // Provide more user-friendly error messages based on exception type
                val errorMessage = when (e) {
                    is SecurityException -> {
                        if (e.message?.contains("API key") == true || 
                            e.message?.contains("authorization") == true) {
                            "Places API authorization error. Please ensure the API key is properly configured in Google Cloud Console."
                        } else if (e.message?.contains("Permission") == true || 
                                e.message?.contains("permission") == true) {
                            "Location permission needed. Please grant location permission to see nearby places."
                        } else {
                            "Security error: ${e.message}"
                        }
                    }
                    is IOException -> "Network error. Please check your connection and try again."
                    is TimeoutException -> "Request timed out. Please try again."
                    else -> "Failed to load nearby places: ${e.message}"
                }
                
                _error.value = errorMessage
                _isLoading.value = false
            }
            .launchIn(viewModelScope)
    }

    /**
     * Select a place to view its details
     */
    fun selectPlace(placeId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            placesRepository.getPlaceDetails(placeId)
                .onSuccess { place ->
                    _selectedPlace.value = place
                    _isLoading.value = false
                    
                    // Try to load content for the selected place
                    loadContentForSelectedPlace()
                }
                .onFailure { e ->
                    Timber.e(e, "Error fetching place details")
                    _error.value = "Failed to load place details: ${e.message}"
                    _isLoading.value = false
                }
        }
    }

    /**
     * Mark a place as visited
     */
    fun markPlaceAsVisited(pointOfInterest: PointOfInterest) {
        viewModelScope.launch {
            val updatedPoi = pointOfInterest.copy(isVisited = true)
            placesRepository.saveVisitedPlace(updatedPoi)
                .onSuccess {
                    // Update the selected place with the visited status
                    _selectedPlace.value = updatedPoi
                }
                .onFailure { e ->
                    Timber.e(e, "Error saving visited place")
                    _error.value = "Failed to save visited place: ${e.message}"
                }
        }
    }

    /**
     * Add user notes to a place
     */
    fun addUserNotes(pointOfInterest: PointOfInterest, notes: String) {
        viewModelScope.launch {
            val updatedPoi = pointOfInterest.copy(userNotes = notes)
            placesRepository.saveVisitedPlace(updatedPoi)
                .onSuccess {
                    // Update the selected place with the new notes
                    _selectedPlace.value = updatedPoi
                }
                .onFailure { e ->
                    Timber.e(e, "Error saving user notes")
                    _error.value = "Failed to save notes: ${e.message}"
                }
        }
    }
    
    /**
     * Load content for the currently selected place
     */
    private suspend fun loadContentForSelectedPlace() {
        val place = _selectedPlace.value ?: return
        try {
            // Get the latest preferences by collecting once from the flow
            val preferences = userPreferencesRepository.userPreferencesFlow.first()
            val content = tourContentRepository.getContentForPlace(place, preferences)
            _selectedPlaceContent.value = content
        } catch (e: Exception) {
            Timber.e(e, "Error loading content for place: ${place.name}")
            _error.value = "Failed to load content: ${e.message}"
        }
    }

    /**
     * Generate content for the currently selected place
     */
    fun generateContentForSelectedPlace() {
        val place = _selectedPlace.value ?: return

        viewModelScope.launch {
            val preferences = userPreferencesRepository.userPreferencesFlow.first()

            tourContentRepository.generateContent(place, preferences)
                .onEach { result ->
                    _contentGenerationStatus.value = result
                    if (result is TourContentRepository.ContentGenerationResult.Success) {
                        _selectedPlaceContent.value = result.content
                    }
                }
                .catch { e ->
                    Timber.e(e, "Error generating content")
                    _error.value = "Failed to generate content: ${e.message}"
                    _contentGenerationStatus.value = TourContentRepository.ContentGenerationResult.Error(e.message ?: "Unknown error")
                }
                .launchIn(viewModelScope)
        }
    }
    
    /**
     * Speak the content of the currently selected place
     */
    fun speakSelectedPlaceContent() {
        val content = _selectedPlaceContent.value ?: return
        
        audioService.speak(content)
            .onEach { status ->
                _speakingStatus.value = status
            }
            .launchIn(viewModelScope)
    }
    
    /**
     * Speak specific text
     */
    fun speakText(text: String) {
        audioService.speak(text)
            .onEach { status ->
                _speakingStatus.value = status
            }
            .launchIn(viewModelScope)
    }

    /**
     * Speak a navigation prompt. Ongoing tour narration is paused and resumes
     * automatically after the prompt.
     */
    fun speakNavigationPrompt(text: String) {
        audioService.speakPriority(text)
            .onEach { status ->
                _speakingStatus.value = status
            }
            .launchIn(viewModelScope)
    }
    
    /**
     * Pause speaking
     */
    fun pauseSpeaking() {
        audioService.pause()
        _speakingStatus.value = AudioService.SpeakingStatus.PAUSED
    }
    
    /**
     * Resume speaking
     */
    fun resumeSpeaking() {
        if (audioService.resume()) {
            _speakingStatus.value = AudioService.SpeakingStatus.IN_PROGRESS
        }
    }
    
    /**
     * Stop speaking
     */
    fun stopSpeaking() {
        audioService.stop()
        _speakingStatus.value = null
    }
    
    /**
     * Update user preferences
     */
    fun updateUserPreferences(userPreferences: UserPreferences) {
        viewModelScope.launch {
            userPreferencesRepository.updateUserPreferences(userPreferences)
            audioService.updateVoiceSettings(userPreferences)
        }
    }
    
    /**
     * Update audio settings
     */
    fun updateAudioSettings(
        audioEnabled: Boolean? = null,
        voiceSpeed: Float? = null,
        voicePitch: Float? = null,
        voiceLanguage: String? = null,
        autoPlayContent: Boolean? = null
    ) {
        viewModelScope.launch {
            userPreferencesRepository.updateAudioSettings(
                audioEnabled, voiceSpeed, voicePitch, voiceLanguage, autoPlayContent
            )

            // Also update the TTS engine with the freshly stored settings
            val prefs = userPreferencesRepository.userPreferencesFlow.first()
            audioService.updateVoiceSettings(prefs)
        }
    }

    /**
     * Clear any current error
     */
    fun clearError() {
        _error.value = null
    }
    
    /**
     * Clean up resources when ViewModel is cleared
     */
    override fun onCleared() {
        super.onCleared()
        audioService.shutdown()
    }
} 