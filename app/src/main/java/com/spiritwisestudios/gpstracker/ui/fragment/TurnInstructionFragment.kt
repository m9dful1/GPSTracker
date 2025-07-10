package com.spiritwisestudios.gpstracker.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.spiritwisestudios.gpstracker.R
import com.spiritwisestudios.gpstracker.domain.service.NavigationService

/**
 * Fragment that displays turn-by-turn navigation instructions.
 */
class TurnInstructionFragment : Fragment() {

    companion object {
        const val TAG = "TurnInstructionFragment"
        
        // Factory method to create a new instance
        fun newInstance(): TurnInstructionFragment {
            return TurnInstructionFragment()
        }
    }
    
    // UI Elements
    private lateinit var maneuverIconView: TextView
    private lateinit var primaryInstructionView: TextView
    private lateinit var secondaryInstructionView: TextView
    private lateinit var streetNameView: TextView
    private lateinit var distanceView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var closeButton: ImageButton
    
    // Current instruction data
    private var currentInstruction: NavigationService.NavigationInstruction? = null
    private var currentAnnouncementTiming: NavigationService.AnnouncementTiming = 
        NavigationService.AnnouncementTiming.NONE
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.layout_turn_instruction, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Initialize views
        maneuverIconView = view.findViewById(R.id.tv_maneuver_icon)
        primaryInstructionView = view.findViewById(R.id.tv_primary_instruction)
        secondaryInstructionView = view.findViewById(R.id.tv_secondary_instruction)
        streetNameView = view.findViewById(R.id.tv_street_name)
        distanceView = view.findViewById(R.id.tv_distance)
        progressBar = view.findViewById(R.id.progress_approach)
        closeButton = view.findViewById(R.id.btn_close_turn_instruction)
        
        // Set up close button
        closeButton.setOnClickListener {
            hideInstructionCard()
        }
        
        // Update UI if we already have an instruction
        currentInstruction?.let { updateInstructionUI(it, currentAnnouncementTiming) }
    }
    
    /**
     * Hide the instruction card.
     */
    private fun hideInstructionCard() {
        // Hide the container view via the parent activity
        if (activity is NavigationInstructionController) {
            (activity as NavigationInstructionController).hideTurnInstructions()
        } else {
            // Fall back to hiding just this fragment
            view?.visibility = View.GONE
        }
    }
    
    /**
     * Update the UI with new instruction and announcement timing.
     */
    fun updateInstruction(
        instruction: NavigationService.NavigationInstruction,
        maneuverDetails: NavigationService.ManeuverDetails,
        announcementTiming: NavigationService.AnnouncementTiming
    ) {
        // Store current values
        currentInstruction = instruction
        currentAnnouncementTiming = announcementTiming
        
        // Update UI if view is created
        if (::maneuverIconView.isInitialized) {
            updateInstructionUI(instruction, announcementTiming, maneuverDetails)
        }
    }
    
    /**
     * Update the UI elements with the instruction data.
     */
    private fun updateInstructionUI(
        instruction: NavigationService.NavigationInstruction,
        announcementTiming: NavigationService.AnnouncementTiming,
        maneuverDetails: NavigationService.ManeuverDetails? = null
    ) {
        // Get maneuver details if not provided
        val details = maneuverDetails ?: requireActivity().run {
            if (this is NavigationDetailsProvider) {
                getManeuverDetails(instruction)
            } else null
        }
        
        // If we have details, use them to update UI
        details?.let {
            // Update icon
            maneuverIconView.text = it.visualIcon
            
            // Update instructions
            primaryInstructionView.text = it.primaryInstruction
            secondaryInstructionView.text = it.secondaryInstruction
            
            // Update street name if available
            val streetName = instruction.description.substringAfter("onto ", "")
                .substringAfter("on ", "")
            if (streetName.isNotEmpty()) {
                streetNameView.visibility = View.VISIBLE
                streetNameView.text = streetName
            } else {
                streetNameView.visibility = View.GONE
            }
            
            // Format distance
            val distanceText = when {
                instruction.distance >= 1000 -> String.format("%.1f km", instruction.distance / 1000)
                else -> String.format("%d m", instruction.distance.toInt())
            }
            distanceView.text = distanceText
            
            // Update progress based on announcement timing
            val progress = when (announcementTiming) {
                NavigationService.AnnouncementTiming.IMMEDIATE -> 90
                NavigationService.AnnouncementTiming.APPROACHING -> 60
                NavigationService.AnnouncementTiming.ADVANCE -> 30
                NavigationService.AnnouncementTiming.NONE -> 10
                NavigationService.AnnouncementTiming.PASSED -> 100
            }
            progressBar.progress = progress
        }
    }
    
    /**
     * Interface for activities that can provide maneuver details.
     */
    interface NavigationDetailsProvider {
        fun getManeuverDetails(instruction: NavigationService.NavigationInstruction): 
                NavigationService.ManeuverDetails
    }
    
    /**
     * Interface for activities that can control the navigation instructions view.
     */
    interface NavigationInstructionController {
        fun hideTurnInstructions()
    }
} 