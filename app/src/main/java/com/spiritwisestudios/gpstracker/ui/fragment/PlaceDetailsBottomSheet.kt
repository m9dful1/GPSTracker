package com.spiritwisestudios.gpstracker.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.spiritwisestudios.gpstracker.R
import com.spiritwisestudios.gpstracker.databinding.BottomSheetPlaceDetailsBinding
import com.spiritwisestudios.gpstracker.databinding.DialogVoiceSettingsBinding
import com.spiritwisestudios.gpstracker.domain.model.PointOfInterest
import com.spiritwisestudios.gpstracker.domain.model.TourContent
import com.spiritwisestudios.gpstracker.domain.service.AudioService
import com.spiritwisestudios.gpstracker.data.repository.TourContentRepository
import com.spiritwisestudios.gpstracker.ui.viewmodel.PlacesViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class PlaceDetailsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetPlaceDetailsBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var placesViewModel: PlacesViewModel

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetPlaceDetailsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Get the shared ViewModel from the activity
        placesViewModel = ViewModelProvider(requireActivity())[PlacesViewModel::class.java]
        
        // Observe selected place changes
        placesViewModel.selectedPlace.observe(viewLifecycleOwner, Observer { place ->
            place?.let { updateUI(it) }
        })
        
        // Observe content for the selected place
        placesViewModel.selectedPlaceContent.observe(viewLifecycleOwner, Observer { content ->
            content?.let { updateContentUI(it) }
        })
        
        // Observe speaking status
        placesViewModel.speakingStatus.observe(viewLifecycleOwner, Observer { status ->
            updateAudioControlsUI(status)
        })
        
        // Observe content generation status
        placesViewModel.contentGenerationStatus.observe(viewLifecycleOwner, Observer { result ->
            updateContentGenerationUI(result)
        })
        
        // Setup button click listeners
        setupClickListeners()
    }
    
    private fun updateUI(place: PointOfInterest) {
        with(binding) {
            tvPlaceName.text = place.name
            tvPlaceAddress.text = place.address
            tvPlaceCategory.text = place.category
            
            if (place.rating != null) {
                rbPlaceRating.rating = place.rating.toFloat()
                rbPlaceRating.visibility = View.VISIBLE
            } else {
                rbPlaceRating.visibility = View.GONE
            }
            
            // Update description if available
            tvPlaceDescription.text = place.description ?: "No description available."
            
            // Show user notes if they exist
            if (place.userNotes.isNullOrEmpty()) {
                tvUserNotesLabel.visibility = View.GONE
                tvUserNotes.visibility = View.GONE
            } else {
                tvUserNotesLabel.visibility = View.VISIBLE
                tvUserNotes.visibility = View.VISIBLE
                tvUserNotes.text = place.userNotes
            }
            
            // Update visit status button
            btnMarkVisited.isEnabled = !place.isVisited
            if (place.isVisited) {
                btnMarkVisited.text = "Visited"
            } else {
                btnMarkVisited.text = "Mark as Visited"
            }
        }
    }
    
    private fun updateContentUI(content: TourContent) {
        with(binding) {
            // Update the description with the tour content
            tvPlaceDescription.text = content.content
            
            // Enable the audio controls
            btnPlayAudio.isEnabled = true
            tvAudioStatus.text = "Content ready for playback"
        }
    }
    
    private fun updateAudioControlsUI(status: AudioService.SpeakingStatus?) {
        with(binding) {
            when (status) {
                AudioService.SpeakingStatus.STARTED -> {
                    btnPlayAudio.isEnabled = false
                    btnPauseAudio.isEnabled = true
                    btnStopAudio.isEnabled = true
                    progressAudio.visibility = View.VISIBLE
                    progressAudio.isIndeterminate = true
                    tvAudioStatus.text = "Starting playback..."
                }
                AudioService.SpeakingStatus.IN_PROGRESS -> {
                    btnPlayAudio.isEnabled = false
                    btnPauseAudio.isEnabled = true
                    btnStopAudio.isEnabled = true
                    progressAudio.visibility = View.VISIBLE
                    progressAudio.isIndeterminate = false
                    progressAudio.progress = 50 // Without proper duration tracking, just show 50%
                    tvAudioStatus.text = "Playing..."
                }
                AudioService.SpeakingStatus.PAUSED -> {
                    btnPlayAudio.isEnabled = true
                    btnPauseAudio.isEnabled = false
                    btnStopAudio.isEnabled = true
                    progressAudio.visibility = View.VISIBLE
                    tvAudioStatus.text = "Paused"
                }
                AudioService.SpeakingStatus.COMPLETED -> {
                    btnPlayAudio.isEnabled = true
                    btnPauseAudio.isEnabled = false
                    btnStopAudio.isEnabled = false
                    progressAudio.visibility = View.INVISIBLE
                    tvAudioStatus.text = "Playback completed"
                }
                AudioService.SpeakingStatus.ERROR -> {
                    btnPlayAudio.isEnabled = true
                    btnPauseAudio.isEnabled = false
                    btnStopAudio.isEnabled = false
                    progressAudio.visibility = View.INVISIBLE
                    tvAudioStatus.text = "Error during playback"
                }
                null -> {
                    btnPlayAudio.isEnabled = true
                    btnPauseAudio.isEnabled = false
                    btnStopAudio.isEnabled = false
                    progressAudio.visibility = View.INVISIBLE
                    tvAudioStatus.text = "Ready"
                }
            }
        }
    }
    
    private fun updateContentGenerationUI(result: TourContentRepository.ContentGenerationResult?) {
        with(binding) {
            when (result) {
                is TourContentRepository.ContentGenerationResult.Queued -> {
                    tvAudioStatus.text = "Generating content..."
                    progressAudio.visibility = View.VISIBLE
                    progressAudio.isIndeterminate = true
                    btnPlayAudio.isEnabled = false
                }
                is TourContentRepository.ContentGenerationResult.InProgress -> {
                    tvAudioStatus.text = "Generating content: ${(result.progress * 100).toInt()}%"
                    progressAudio.visibility = View.VISIBLE
                    progressAudio.isIndeterminate = false
                    progressAudio.progress = (result.progress * 100).toInt()
                    btnPlayAudio.isEnabled = false
                }
                is TourContentRepository.ContentGenerationResult.Success -> {
                    tvAudioStatus.text = "Content generated successfully"
                    progressAudio.visibility = View.INVISIBLE
                    btnPlayAudio.isEnabled = true
                }
                is TourContentRepository.ContentGenerationResult.Error -> {
                    tvAudioStatus.text = "Error: ${result.message}"
                    progressAudio.visibility = View.INVISIBLE
                    btnPlayAudio.isEnabled = false
                }
                null -> { /* Do nothing */ }
            }
        }
    }
    
    private fun setupClickListeners() {
        // Mark as visited button
        binding.btnMarkVisited.setOnClickListener {
            placesViewModel.selectedPlace.value?.let { place ->
                if (!place.isVisited) {
                    placesViewModel.markPlaceAsVisited(place)
                    Toast.makeText(context, "${place.name} marked as visited!", Toast.LENGTH_SHORT).show()
                }
            }
        }
        
        // Add notes button
        binding.btnAddNotes.setOnClickListener {
            showAddNotesDialog()
        }
        
        // Audio controls
        binding.btnPlayAudio.setOnClickListener {
            placesViewModel.speakSelectedPlaceContent()
        }
        
        binding.btnPauseAudio.setOnClickListener {
            placesViewModel.pauseSpeaking()
        }
        
        binding.btnStopAudio.setOnClickListener {
            placesViewModel.stopSpeaking()
        }
        
        // Voice settings
        binding.btnVoiceSettings.setOnClickListener {
            showVoiceSettingsDialog()
        }
    }
    
    private fun showAddNotesDialog() {
        val place = placesViewModel.selectedPlace.value ?: return
        
        // Create an EditText for the dialog
        val editText = android.widget.EditText(context).apply {
            setText(place.userNotes)
            hint = "Enter your notes..."
            setSingleLine(false)
            minLines = 3
        }
        
        // Create the dialog
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Add Notes for ${place.name}")
            .setView(editText)
            .setPositiveButton("Save") { _, _ ->
                val notes = editText.text.toString().trim()
                placesViewModel.addUserNotes(place, notes)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun showVoiceSettingsDialog() {
        val dialogBinding = DialogVoiceSettingsBinding.inflate(layoutInflater)
        val currentPreferences = placesViewModel.userPreferences.value
        
        // Set initial values based on current preferences
        dialogBinding.switchAudioEnabled.isChecked = currentPreferences?.audioEnabled ?: true
        dialogBinding.sliderVoiceSpeed.value = currentPreferences?.voiceSpeed ?: 1.0f
        dialogBinding.sliderVoicePitch.value = currentPreferences?.voicePitch ?: 1.0f
        
        // Create the dialog
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Voice Settings")
            .setView(dialogBinding.root)
            .setPositiveButton("Save") { _, _ ->
                // Save the voice settings
                placesViewModel.updateAudioSettings(
                    audioEnabled = dialogBinding.switchAudioEnabled.isChecked,
                    voiceSpeed = dialogBinding.sliderVoiceSpeed.value,
                    voicePitch = dialogBinding.sliderVoicePitch.value
                )
                Toast.makeText(context, "Voice settings updated", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
    
    companion object {
        const val TAG = "PlaceDetailsBottomSheet"
        
        fun newInstance(): PlaceDetailsBottomSheet {
            return PlaceDetailsBottomSheet()
        }
    }
} 