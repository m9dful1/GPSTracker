package com.spiritwisestudios.gpstracker.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.spiritwisestudios.gpstracker.R
import com.spiritwisestudios.gpstracker.util.MapStyles

/**
 * Layers sheet for picking the map style (OpenFreeMap-hosted MapLibre
 * styles). Changes apply to the map immediately; the sheet dismisses by
 * swipe or tapping outside.
 */
class MapLayersBottomSheet : BottomSheetDialogFragment() {

    /** Implemented by the hosting activity, which owns the map. */
    interface MapLayersHost {
        fun currentMapStyle(): Int
        fun onMapStyleSelected(style: Int)
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

        val styleGroup = view.findViewById<RadioGroup>(R.id.rg_map_type)

        styleGroup.check(
            when (host.currentMapStyle()) {
                MapStyles.BRIGHT -> R.id.rb_map_bright
                MapStyles.MINIMAL -> R.id.rb_map_minimal
                MapStyles.DARK -> R.id.rb_map_dark
                else -> R.id.rb_map_default
            }
        )

        styleGroup.setOnCheckedChangeListener { _, checkedId ->
            host.onMapStyleSelected(
                when (checkedId) {
                    R.id.rb_map_bright -> MapStyles.BRIGHT
                    R.id.rb_map_minimal -> MapStyles.MINIMAL
                    R.id.rb_map_dark -> MapStyles.DARK
                    else -> MapStyles.DEFAULT
                }
            )
        }
    }

    companion object {
        const val TAG = "MapLayersBottomSheet"

        fun newInstance(): MapLayersBottomSheet = MapLayersBottomSheet()
    }
}
