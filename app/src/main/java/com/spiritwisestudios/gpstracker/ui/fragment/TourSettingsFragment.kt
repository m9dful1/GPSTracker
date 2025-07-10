package com.spiritwisestudios.gpstracker.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.spiritwisestudios.gpstracker.R
import com.spiritwisestudios.gpstracker.domain.model.PointOfInterest
import com.spiritwisestudios.gpstracker.domain.model.UserPreferences
import com.spiritwisestudios.gpstracker.ui.viewmodel.PlacesViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.EnumSet

/**
 * Fragment for configuring tour mode settings.
 */
@AndroidEntryPoint
class TourSettingsFragment : BottomSheetDialogFragment() {
    
    // ViewModel
    private val viewModel: PlacesViewModel by activityViewModels()
    
    // Category CheckBoxes
    private lateinit var cbHistorical: CheckBox
    private lateinit var cbCultural: CheckBox
    private lateinit var cbNatural: CheckBox
    private lateinit var cbArchitectural: CheckBox
    private lateinit var cbEntertainment: CheckBox
    private lateinit var cbDining: CheckBox
    private lateinit var cbShopping: CheckBox
    
    // Detail Level RadioGroup
    private lateinit var rgDetailLevel: RadioGroup
    private lateinit var rbDetailBrief: RadioButton
    private lateinit var rbDetailMedium: RadioButton
    private lateinit var rbDetailDetailed: RadioButton
    
    // Audio Settings
    private lateinit var switchAudioEnabled: Switch
    private lateinit var seekBarVoiceSpeed: SeekBar
    private lateinit var tvVoiceSpeedValue: TextView
    private lateinit var seekBarVoicePitch: SeekBar
    private lateinit var tvVoicePitchValue: TextView
    private lateinit var switchAutoPlay: Switch
    
    // Notification Settings
    private lateinit var seekBarNotifyDistance: SeekBar
    private lateinit var tvNotifyDistanceValue: TextView
    private lateinit var seekBarMaxNotifications: SeekBar
    private lateinit var tvMaxNotificationsValue: TextView
    
    // Battery Usage
    private lateinit var switchPrefetchContent: Switch
    private lateinit var switchUseMobileData: Switch
    
    // Buttons
    private lateinit var btnCancel: Button
    private lateinit var btnSave: Button
    
    // Current preferences
    private lateinit var currentPreferences: UserPreferences
    
    companion object {
        const val TAG = "TourSettingsFragment"
        
        fun newInstance(): TourSettingsFragment = TourSettingsFragment()
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_tour_settings, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Initialize views
        initViews(view)
        
        // Set up listeners
        setupListeners()
        
        // Load current preferences
        loadPreferences()
    }
    
    private fun initViews(view: View) {
        // Category CheckBoxes
        cbHistorical = view.findViewById(R.id.cb_category_historical)
        cbCultural = view.findViewById(R.id.cb_category_cultural)
        cbNatural = view.findViewById(R.id.cb_category_natural)
        cbArchitectural = view.findViewById(R.id.cb_category_architectural)
        cbEntertainment = view.findViewById(R.id.cb_category_entertainment)
        cbDining = view.findViewById(R.id.cb_category_dining)
        cbShopping = view.findViewById(R.id.cb_category_shopping)
        
        // Detail Level RadioGroup
        rgDetailLevel = view.findViewById(R.id.rg_detail_level)
        rbDetailBrief = view.findViewById(R.id.rb_detail_brief)
        rbDetailMedium = view.findViewById(R.id.rb_detail_medium)
        rbDetailDetailed = view.findViewById(R.id.rb_detail_detailed)
        
        // Audio Settings
        switchAudioEnabled = view.findViewById(R.id.switch_audio_enabled)
        seekBarVoiceSpeed = view.findViewById(R.id.seekbar_voice_speed)
        tvVoiceSpeedValue = view.findViewById(R.id.tv_voice_speed_value)
        seekBarVoicePitch = view.findViewById(R.id.seekbar_voice_pitch)
        tvVoicePitchValue = view.findViewById(R.id.tv_voice_pitch_value)
        switchAutoPlay = view.findViewById(R.id.switch_auto_play)
        
        // Notification Settings
        seekBarNotifyDistance = view.findViewById(R.id.seekbar_notify_distance)
        tvNotifyDistanceValue = view.findViewById(R.id.tv_notify_distance_value)
        seekBarMaxNotifications = view.findViewById(R.id.seekbar_max_notifications)
        tvMaxNotificationsValue = view.findViewById(R.id.tv_max_notifications_value)
        
        // Battery Usage
        switchPrefetchContent = view.findViewById(R.id.switch_prefetch_content)
        switchUseMobileData = view.findViewById(R.id.switch_use_mobile_data)
        
        // Buttons
        btnCancel = view.findViewById(R.id.btn_cancel)
        btnSave = view.findViewById(R.id.btn_save)
    }
    
    private fun setupListeners() {
        // Voice Speed SeekBar
        seekBarVoiceSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val speed = progressToSpeed(progress)
                tvVoiceSpeedValue.text = String.format("%.1fx", speed)
            }
            
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // Voice Pitch SeekBar
        seekBarVoicePitch.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val pitch = progressToPitch(progress)
                tvVoicePitchValue.text = String.format("%.1fx", pitch)
            }
            
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // Notification Distance SeekBar
        seekBarNotifyDistance.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvNotifyDistanceValue.text = "${progress}m"
            }
            
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // Max Notifications SeekBar
        seekBarMaxNotifications.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvMaxNotificationsValue.text = "$progress per hour"
            }
            
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // Cancel button
        btnCancel.setOnClickListener {
            dismiss()
        }
        
        // Save button
        btnSave.setOnClickListener {
            savePreferences()
            dismiss()
        }
    }
    
    private fun loadPreferences() {
        viewModel.userPreferences.observe(viewLifecycleOwner) { preferences ->
            currentPreferences = preferences
            
            // Update UI to reflect current preferences
            updateUIFromPreferences(preferences)
        }
    }
    
    private fun updateUIFromPreferences(preferences: UserPreferences) {
        // Categories
        cbHistorical.isChecked = preferences.preferredCategories.contains(PointOfInterest.Category.HISTORICAL)
        cbCultural.isChecked = preferences.preferredCategories.contains(PointOfInterest.Category.CULTURAL)
        cbNatural.isChecked = preferences.preferredCategories.contains(PointOfInterest.Category.NATURAL)
        cbArchitectural.isChecked = preferences.preferredCategories.contains(PointOfInterest.Category.ARCHITECTURAL)
        cbEntertainment.isChecked = preferences.preferredCategories.contains(PointOfInterest.Category.ENTERTAINMENT)
        cbDining.isChecked = preferences.preferredCategories.contains(PointOfInterest.Category.DINING)
        cbShopping.isChecked = preferences.preferredCategories.contains(PointOfInterest.Category.SHOPPING)
        
        // Detail Level
        when (preferences.contentDetailLevel) {
            UserPreferences.DetailLevel.BRIEF -> rbDetailBrief.isChecked = true
            UserPreferences.DetailLevel.MEDIUM -> rbDetailMedium.isChecked = true
            UserPreferences.DetailLevel.DETAILED -> rbDetailDetailed.isChecked = true
        }
        
        // Audio Settings
        switchAudioEnabled.isChecked = preferences.audioEnabled
        
        // Convert voice speed to progress (0.5 to 2.0 -> 0 to 20)
        val speedProgress = speedToProgress(preferences.voiceSpeed)
        seekBarVoiceSpeed.progress = speedProgress
        tvVoiceSpeedValue.text = String.format("%.1fx", preferences.voiceSpeed)
        
        // Convert voice pitch to progress (0.5 to 2.0 -> 0 to 20)
        val pitchProgress = pitchToProgress(preferences.voicePitch)
        seekBarVoicePitch.progress = pitchProgress
        tvVoicePitchValue.text = String.format("%.1fx", preferences.voicePitch)
        
        switchAutoPlay.isChecked = preferences.autoPlayContent
        
        // Notification Settings
        seekBarNotifyDistance.progress = preferences.notifyDistance
        tvNotifyDistanceValue.text = "${preferences.notifyDistance}m"
        
        seekBarMaxNotifications.progress = preferences.maxNotificationsPerHour
        tvMaxNotificationsValue.text = "${preferences.maxNotificationsPerHour} per hour"
        
        // Battery Usage
        switchPrefetchContent.isChecked = preferences.prefetchContent
        switchUseMobileData.isChecked = preferences.useMobileData
    }
    
    private fun savePreferences() {
        // Build the preferred categories set
        val preferredCategories = EnumSet.noneOf(PointOfInterest.Category::class.java)
        if (cbHistorical.isChecked) preferredCategories.add(PointOfInterest.Category.HISTORICAL)
        if (cbCultural.isChecked) preferredCategories.add(PointOfInterest.Category.CULTURAL)
        if (cbNatural.isChecked) preferredCategories.add(PointOfInterest.Category.NATURAL)
        if (cbArchitectural.isChecked) preferredCategories.add(PointOfInterest.Category.ARCHITECTURAL)
        if (cbEntertainment.isChecked) preferredCategories.add(PointOfInterest.Category.ENTERTAINMENT)
        if (cbDining.isChecked) preferredCategories.add(PointOfInterest.Category.DINING)
        if (cbShopping.isChecked) preferredCategories.add(PointOfInterest.Category.SHOPPING)
        
        // Determine detail level
        val detailLevel = when (rgDetailLevel.checkedRadioButtonId) {
            R.id.rb_detail_brief -> UserPreferences.DetailLevel.BRIEF
            R.id.rb_detail_medium -> UserPreferences.DetailLevel.MEDIUM
            R.id.rb_detail_detailed -> UserPreferences.DetailLevel.DETAILED
            else -> UserPreferences.DetailLevel.MEDIUM
        }
        
        // Convert progress to voice speed (0 to 20 -> 0.5 to 2.0)
        val voiceSpeed = progressToSpeed(seekBarVoiceSpeed.progress)
        
        // Convert progress to voice pitch (0 to 20 -> 0.5 to 2.0)
        val voicePitch = progressToPitch(seekBarVoicePitch.progress)
        
        // Create updated preferences
        val updatedPreferences = currentPreferences.copy(
            audioEnabled = switchAudioEnabled.isChecked,
            voiceSpeed = voiceSpeed,
            voicePitch = voicePitch,
            voiceLanguage = currentPreferences.voiceLanguage, // Not changed in this UI
            autoPlayContent = switchAutoPlay.isChecked,
            preferredCategories = preferredCategories,
            contentDetailLevel = detailLevel,
            notifyDistance = seekBarNotifyDistance.progress,
            maxNotificationsPerHour = seekBarMaxNotifications.progress,
            prefetchContent = switchPrefetchContent.isChecked,
            useMobileData = switchUseMobileData.isChecked
        )
        
        // Update preferences in the ViewModel
        viewModel.updateUserPreferences(updatedPreferences)
        
        // Also update audio settings specifically
        lifecycleScope.launch {
            viewModel.updateAudioSettings(
                audioEnabled = switchAudioEnabled.isChecked,
                voiceSpeed = voiceSpeed,
                voicePitch = voicePitch,
                autoPlayContent = switchAutoPlay.isChecked
            )
        }
    }
    
    /**
     * Convert SeekBar progress (0-20) to voice speed (0.5-2.0)
     */
    private fun progressToSpeed(progress: Int): Float {
        return 0.5f + (progress / 20.0f) * 1.5f
    }
    
    /**
     * Convert voice speed (0.5-2.0) to SeekBar progress (0-20)
     */
    private fun speedToProgress(speed: Float): Int {
        return ((speed - 0.5f) / 1.5f * 20.0f).toInt()
    }
    
    /**
     * Convert SeekBar progress (0-20) to voice pitch (0.5-2.0)
     */
    private fun progressToPitch(progress: Int): Float {
        return 0.5f + (progress / 20.0f) * 1.5f
    }
    
    /**
     * Convert voice pitch (0.5-2.0) to SeekBar progress (0-20)
     */
    private fun pitchToProgress(pitch: Float): Int {
        return ((pitch - 0.5f) / 1.5f * 20.0f).toInt()
    }
} 