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
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.switchmaterial.SwitchMaterial
import com.spiritwisestudios.gpstracker.BuildConfig
import com.spiritwisestudios.gpstracker.R
import com.spiritwisestudios.gpstracker.ads.ConsentManager
import com.spiritwisestudios.gpstracker.data.repository.AccountTierHolder
import com.spiritwisestudios.gpstracker.data.repository.MapProviderHolder
import com.spiritwisestudios.gpstracker.domain.model.AccountTier
import com.spiritwisestudios.gpstracker.domain.model.MapProvider
import com.spiritwisestudios.gpstracker.domain.model.PointOfInterest
import com.spiritwisestudios.gpstracker.domain.model.UserPreferences
import com.spiritwisestudios.gpstracker.ui.viewmodel.PlacesViewModel
import com.spiritwisestudios.gpstracker.util.DistanceFormatter
import com.spiritwisestudios.gpstracker.util.TourLogic
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.EnumSet
import javax.inject.Inject

/**
 * Fragment for configuring tour mode settings.
 */
@AndroidEntryPoint
class TourSettingsFragment : BottomSheetDialogFragment() {
    
    // ViewModel
    private val viewModel: PlacesViewModel by activityViewModels()

    @Inject
    lateinit var mapProviderHolder: MapProviderHolder

    @Inject
    lateinit var accountTierHolder: AccountTierHolder

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
    private lateinit var switchAudioEnabled: SwitchMaterial
    private lateinit var seekBarVoiceSpeed: SeekBar
    private lateinit var tvVoiceSpeedValue: TextView
    private lateinit var seekBarVoicePitch: SeekBar
    private lateinit var tvVoicePitchValue: TextView
    private lateinit var switchAutoPlay: SwitchMaterial
    
    // Notification Settings
    private lateinit var seekBarNotifyDistance: SeekBar
    private lateinit var tvNotifyDistanceValue: TextView
    private lateinit var seekBarMaxNotifications: SeekBar
    private lateinit var tvMaxNotificationsValue: TextView
    
    // Battery Usage
    private lateinit var switchPrefetchContent: SwitchMaterial
    private lateinit var switchUseMobileData: SwitchMaterial

    // Map Provider
    private lateinit var rgMapProvider: RadioGroup
    private lateinit var rbProviderOsm: RadioButton
    private lateinit var rbProviderGoogle: RadioButton
    private lateinit var tvProviderHint: TextView

    // Ads
    private lateinit var btnAdPrivacy: Button

    // Cached stories
    private lateinit var btnClearStoryCache: Button

    // Account tier (debug testing toggle)
    private lateinit var accountTierSection: View
    private lateinit var rbAccountStandard: RadioButton
    private lateinit var rbAccountPremium: RadioButton

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

        // Map Provider
        rgMapProvider = view.findViewById(R.id.rg_map_provider)
        rbProviderOsm = view.findViewById(R.id.rb_provider_osm)
        rbProviderGoogle = view.findViewById(R.id.rb_provider_google)
        tvProviderHint = view.findViewById(R.id.tv_provider_hint)

        // Google Maps needs a build-time API key; without one the option
        // stays visible (so the feature is discoverable) but disabled
        if (BuildConfig.MAPS_API_KEY.isBlank()) {
            rbProviderGoogle.isEnabled = false
            tvProviderHint.visibility = View.VISIBLE
        }

        when (mapProviderHolder.current) {
            MapProvider.GOOGLE -> rbProviderGoogle.isChecked = true
            MapProvider.OPEN_STREET_MAP -> rbProviderOsm.isChecked = true
        }

        // Ads
        btnAdPrivacy = view.findViewById(R.id.btn_ad_privacy)

        // Cached stories
        btnClearStoryCache = view.findViewById(R.id.btn_clear_story_cache)

        // Account tier: the toggle exists purely so both tiers can be
        // tested; release builds keep it hidden and follow the persisted
        // tier (Standard until an upgrade purchase sets Premium)
        accountTierSection = view.findViewById(R.id.account_tier_section)
        rbAccountStandard = view.findViewById(R.id.rb_account_standard)
        rbAccountPremium = view.findViewById(R.id.rb_account_premium)
        if (BuildConfig.DEBUG) {
            accountTierSection.visibility = View.VISIBLE
            when (accountTierHolder.current) {
                AccountTier.PREMIUM -> rbAccountPremium.isChecked = true
                AccountTier.STANDARD -> rbAccountStandard.isChecked = true
            }
        }

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
                tvNotifyDistanceValue.text = DistanceFormatter.format(progress.toFloat())
            }
            
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // Max Notifications SeekBar
        seekBarMaxNotifications.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvMaxNotificationsValue.text = TourLogic.narrationCapLabel(progress)
            }
            
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // Ad privacy: reopen the UMP consent form so users can change
        // their ad consent whenever they like
        btnAdPrivacy.setOnClickListener {
            ConsentManager.showPrivacyOptions(requireActivity()) { shown ->
                if (!isAdded) return@showPrivacyOptions
                val message = if (shown) {
                    R.string.ad_privacy_updated
                } else {
                    R.string.ad_privacy_unavailable
                }
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
            }
        }

        // Cached stories: the guide drops old ones on its own, but a user who
        // wants fresh text (or the space back) shouldn't have to wait for that
        btnClearStoryCache.setOnClickListener {
            viewModel.clearCachedStories {
                if (!isAdded) return@clearCachedStories
                Toast.makeText(
                    requireContext(),
                    R.string.story_cache_cleared,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

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
        tvNotifyDistanceValue.text = DistanceFormatter.format(preferences.notifyDistance.toFloat())
        
        seekBarMaxNotifications.progress = preferences.maxNotificationsPerHour
        tvMaxNotificationsValue.text = TourLogic.narrationCapLabel(preferences.maxNotificationsPerHour)
        
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

        // Both changes apply through one activity recreation ("or", not
        // "||": each save must run)
        val needsRecreate = saveMapProvider() or saveAccountTier()
        if (needsRecreate) {
            activity?.recreate()
        }
    }

    /**
     * Apply a map provider change: persist it, update the in-memory holder,
     * and recreate the activity so the map for the new provider attaches.
     * The whole map stack — rendering, place discovery, search, routing —
     * follows the toggle.
     */
    private fun saveMapProvider(): Boolean {
        val selected = if (rbProviderGoogle.isChecked) MapProvider.GOOGLE else MapProvider.OPEN_STREET_MAP
        if (selected == mapProviderHolder.current) return false

        mapProviderHolder.set(selected)
        viewModel.setMapProvider(selected) // ViewModel scope survives the recreate
        return true
    }

    /**
     * Apply the debug testing toggle between the tiers. The recreation
     * re-runs the ad setup, so the banner appears or disappears to match;
     * narration routing reads the holder live and needs nothing else.
     */
    private fun saveAccountTier(): Boolean {
        if (!BuildConfig.DEBUG) return false
        val selected = if (rbAccountPremium.isChecked) AccountTier.PREMIUM else AccountTier.STANDARD
        if (selected == accountTierHolder.current) return false

        accountTierHolder.set(selected)
        viewModel.setAccountTier(selected) // ViewModel scope survives the recreate
        return true
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