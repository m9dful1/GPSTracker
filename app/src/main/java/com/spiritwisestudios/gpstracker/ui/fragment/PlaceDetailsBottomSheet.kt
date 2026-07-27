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
import com.spiritwisestudios.gpstracker.domain.model.UserPreferences
import com.spiritwisestudios.gpstracker.ui.viewmodel.PlacesViewModel
import com.spiritwisestudios.gpstracker.util.VoiceSliders
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
            tvPlaceDescription.text = place.description ?: getString(R.string.no_description_available)
            
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
            btnMarkVisited.text = getString(
                if (place.isVisited) R.string.visited else R.string.mark_as_visited
            )
        }
    }
    
    private fun updateContentUI(content: TourContent) {
        with(binding) {
            // Update the description with the tour content
            tvPlaceDescription.text = content.content
            
            // Enable the audio controls
            btnPlayAudio.isEnabled = true
            tvAudioStatus.text = getString(R.string.audio_status_content_ready)
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
                    tvAudioStatus.text = getString(R.string.audio_status_starting)
                }
                AudioService.SpeakingStatus.IN_PROGRESS -> {
                    btnPlayAudio.isEnabled = false
                    btnPauseAudio.isEnabled = true
                    btnStopAudio.isEnabled = true
                    progressAudio.visibility = View.VISIBLE
                    progressAudio.isIndeterminate = false
                    progressAudio.progress = 50 // Without proper duration tracking, just show 50%
                    tvAudioStatus.text = getString(R.string.audio_status_playing)
                }
                AudioService.SpeakingStatus.PAUSED -> {
                    btnPlayAudio.isEnabled = true
                    btnPauseAudio.isEnabled = false
                    btnStopAudio.isEnabled = true
                    progressAudio.visibility = View.VISIBLE
                    tvAudioStatus.text = getString(R.string.audio_status_paused)
                }
                AudioService.SpeakingStatus.COMPLETED -> {
                    btnPlayAudio.isEnabled = true
                    btnPauseAudio.isEnabled = false
                    btnStopAudio.isEnabled = false
                    progressAudio.visibility = View.INVISIBLE
                    tvAudioStatus.text = getString(R.string.audio_status_completed)
                }
                AudioService.SpeakingStatus.ERROR -> {
                    btnPlayAudio.isEnabled = true
                    btnPauseAudio.isEnabled = false
                    btnStopAudio.isEnabled = false
                    progressAudio.visibility = View.INVISIBLE
                    tvAudioStatus.text = getString(R.string.audio_status_error)
                }
                null -> {
                    btnPlayAudio.isEnabled = true
                    btnPauseAudio.isEnabled = false
                    btnStopAudio.isEnabled = false
                    progressAudio.visibility = View.INVISIBLE
                    tvAudioStatus.text = getString(R.string.audio_status_ready)
                }
            }
        }
    }
    
    private fun updateContentGenerationUI(result: TourContentRepository.ContentGenerationResult?) {
        with(binding) {
            when (result) {
                is TourContentRepository.ContentGenerationResult.Queued -> {
                    tvAudioStatus.text = getString(R.string.content_generating)
                    progressAudio.visibility = View.VISIBLE
                    progressAudio.isIndeterminate = true
                    btnPlayAudio.isEnabled = false
                }
                is TourContentRepository.ContentGenerationResult.InProgress -> {
                    tvAudioStatus.text =
                        getString(R.string.content_generating_progress, (result.progress * 100).toInt())
                    progressAudio.visibility = View.VISIBLE
                    progressAudio.isIndeterminate = false
                    progressAudio.progress = (result.progress * 100).toInt()
                    btnPlayAudio.isEnabled = false
                }
                is TourContentRepository.ContentGenerationResult.Success -> {
                    tvAudioStatus.text = getString(R.string.content_generated)
                    progressAudio.visibility = View.INVISIBLE
                    btnPlayAudio.isEnabled = true
                }
                is TourContentRepository.ContentGenerationResult.Error -> {
                    tvAudioStatus.text = getString(R.string.content_error, result.message)
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
                    Toast.makeText(context, getString(R.string.marked_visited_toast, place.name), Toast.LENGTH_SHORT).show()
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
            hint = getString(R.string.notes_hint)
            setSingleLine(false)
            minLines = 3
        }

        // Create the dialog
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.add_notes_title, place.name))
            .setView(editText)
            .setPositiveButton(R.string.save) { _, _ ->
                val notes = editText.text.toString().trim()
                placesViewModel.addUserNotes(place, notes)
            }
            .setNegativeButton(R.string.cancel_button, null)
            .show()
    }
    
    private fun showVoiceSettingsDialog() {
        val dialogBinding = DialogVoiceSettingsBinding.inflate(layoutInflater)
        val currentPreferences = placesViewModel.userPreferences.value
        
        // Set initial values based on current preferences. Both sliders take
        // their range and step from VoiceSliders — the same scale the settings
        // sheet uses — and the stored value is put on that grid before it is
        // handed over: a Material Slider throws IllegalStateException for a
        // value that is not valueFrom plus a whole number of steps, and the
        // settings sheet stores off-grid values as a matter of course.
        dialogBinding.switchAudioEnabled.isChecked = currentPreferences?.audioEnabled ?: true
        for ((slider, stored) in listOf(
            dialogBinding.sliderVoiceSpeed to currentPreferences?.voiceSpeed,
            dialogBinding.sliderVoicePitch to currentPreferences?.voicePitch
        )) {
            slider.valueFrom = VoiceSliders.MIN
            slider.valueTo = VoiceSliders.MAX
            slider.stepSize = VoiceSliders.STEP
            slider.value = VoiceSliders.onGrid(stored ?: UserPreferences().voiceSpeed)
        }
        
        // Create the dialog
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.voice_settings)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save) { _, _ ->
                // Save the voice settings
                placesViewModel.updateAudioSettings(
                    audioEnabled = dialogBinding.switchAudioEnabled.isChecked,
                    voiceSpeed = dialogBinding.sliderVoiceSpeed.value,
                    voicePitch = dialogBinding.sliderVoicePitch.value
                )
                Toast.makeText(context, R.string.voice_settings_updated, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel_button, null)
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