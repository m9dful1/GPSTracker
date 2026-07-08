package com.spiritwisestudios.gpstracker.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioGroup
import android.widget.Switch
import com.google.android.gms.maps.GoogleMap
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.spiritwisestudios.gpstracker.R

/**
 * Google-Maps-style layers sheet: map type is an explicit choice and
 * traffic is its own toggle, instead of one button changing both at once.
 * Changes apply to the map immediately; the sheet stays open so choices
 * can be combined, and dismisses by swipe or tapping outside.
 */
class MapLayersBottomSheet : BottomSheetDialogFragment() {

    /** Implemented by the hosting activity, which owns the GoogleMap. */
    interface MapLayersHost {
        fun currentMapType(): Int
        fun isTrafficEnabled(): Boolean
        fun onMapTypeSelected(mapType: Int)
        fun onTrafficToggled(enabled: Boolean)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_map_layers, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val host = activity as? MapLayersHost ?: return

        val mapTypeGroup = view.findViewById<RadioGroup>(R.id.rg_map_type)
        val trafficSwitch = view.findViewById<Switch>(R.id.switch_traffic)

        mapTypeGroup.check(
            when (host.currentMapType()) {
                // Plain satellite can linger from older versions; both read
                // as "Satellite" here
                GoogleMap.MAP_TYPE_SATELLITE,
                GoogleMap.MAP_TYPE_HYBRID -> R.id.rb_map_satellite
                GoogleMap.MAP_TYPE_TERRAIN -> R.id.rb_map_terrain
                else -> R.id.rb_map_default
            }
        )
        trafficSwitch.isChecked = host.isTrafficEnabled()

        mapTypeGroup.setOnCheckedChangeListener { _, checkedId ->
            host.onMapTypeSelected(
                when (checkedId) {
                    // "Satellite" is hybrid: imagery without road labels is
                    // useless for finding your way
                    R.id.rb_map_satellite -> GoogleMap.MAP_TYPE_HYBRID
                    R.id.rb_map_terrain -> GoogleMap.MAP_TYPE_TERRAIN
                    else -> GoogleMap.MAP_TYPE_NORMAL
                }
            )
        }

        trafficSwitch.setOnCheckedChangeListener { _, isChecked ->
            host.onTrafficToggled(isChecked)
        }
    }

    companion object {
        const val TAG = "MapLayersBottomSheet"

        fun newInstance(): MapLayersBottomSheet = MapLayersBottomSheet()
    }
}
