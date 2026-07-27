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
import java.util.Locale
import com.spiritwisestudios.gpstracker.util.DistanceFormatter
import com.spiritwisestudios.gpstracker.util.TourLogic
import com.spiritwisestudios.gpstracker.util.VoiceSliders
import dagger.hilt.android.AndroidEntryPoint
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
    
    // Whatever the stored preferences turned out to be — null until the
    // DataStore read lands, which is after this sheet is on screen and
    // tappable. Save stays disabled until then rather than copying nothing.
    private var currentPreferences: UserPreferences? = null
    
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
        btnSave.isEnabled = false // until there are preferences to save
    }
    
    private fun setupListeners() {
        // Voice Speed SeekBar
        seekBarVoiceSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvVoiceSpeedValue.text = formatMultiplier(VoiceSliders.valueFor(progress))
            }
            
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        // Voice Pitch SeekBar
        seekBarVoicePitch.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvVoicePitchValue.text = formatMultiplier(VoiceSliders.valueFor(progress))
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
            btnSave.isEnabled = true

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
        
        seekBarVoiceSpeed.progress = VoiceSliders.progressFor(preferences.voiceSpeed)
        tvVoiceSpeedValue.text = formatMultiplier(preferences.voiceSpeed)

        seekBarVoicePitch.progress = VoiceSliders.progressFor(preferences.voicePitch)
        tvVoicePitchValue.text = formatMultiplier(preferences.voicePitch)
        
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
        // Nothing to base a save on until the stored preferences arrive; the
        // button is disabled until then, and this is the belt to that braces
        val existing = currentPreferences ?: return

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
        
        val voiceSpeed = VoiceSliders.valueFor(seekBarVoiceSpeed.progress)
        val voicePitch = VoiceSliders.valueFor(seekBarVoicePitch.progress)

        // Create updated preferences
        val updatedPreferences = existing.copy(
            audioEnabled = switchAudioEnabled.isChecked,
            voiceSpeed = voiceSpeed,
            voicePitch = voicePitch,
            voiceLanguage = existing.voiceLanguage, // Not changed in this UI
            autoPlayContent = switchAutoPlay.isChecked,
            preferredCategories = preferredCategories,
            contentDetailLevel = detailLevel,
            notifyDistance = seekBarNotifyDistance.progress,
            maxNotificationsPerHour = seekBarMaxNotifications.progress,
            prefetchContent = switchPrefetchContent.isChecked,
            useMobileData = switchUseMobileData.isChecked
        )
        
        // One write, in the ViewModel's scope: it stores every field and
        // updates the speech engine. A second updateAudioSettings call used to
        // follow this, re-writing four of the same fields — from the
        // *fragment's* lifecycleScope, one line before dismiss(), so it might
        // never have run at all. Its only saving grace was being redundant.
        viewModel.updateUserPreferences(updatedPreferences)

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
    
    /** "1.2x", in the device's own number format. */
    private fun formatMultiplier(value: Float): String =
        String.format(Locale.getDefault(), "%.2fx", value)
} 