package com.spiritwisestudios.gpstracker.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.switchmaterial.SwitchMaterial
import com.spiritwisestudios.gpstracker.R
import com.spiritwisestudios.gpstracker.domain.model.MapProvider
import com.spiritwisestudios.gpstracker.util.GoogleMapStyles
import com.spiritwisestudios.gpstracker.util.MapStyles

/**
 * Layers sheet for picking the map style. The options follow the active
 * map provider: OpenFreeMap's MapLibre styles, or Google's map types plus
 * a traffic toggle. Changes apply to the map immediately; the sheet
 * dismisses by swipe or tapping outside.
 */
class MapLayersBottomSheet : BottomSheetDialogFragment() {

    /** Implemented by the hosting activity, which owns the map. */
    interface MapLayersHost {
        fun mapProvider(): MapProvider

        /** A [MapStyles] or [GoogleMapStyles] value, per the provider. */
        fun currentMapStyle(): Int
        fun onMapStyleSelected(style: Int)

        /** Traffic overlay; only the Google map renders one. */
        fun isTrafficEnabled(): Boolean
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

        when (host.mapProvider()) {
            MapProvider.OPEN_STREET_MAP -> bindOpenStreetMapStyles(view, host)
            MapProvider.GOOGLE -> bindGoogleStyles(view, host)
        }
    }

    private fun bindOpenStreetMapStyles(view: View, host: MapLayersHost) {
        val styleGroup = view.findViewById<RadioGroup>(R.id.rg_map_style)

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

    private fun bindGoogleStyles(view: View, host: MapLayersHost) {
        view.findViewById<View>(R.id.rg_map_style).visibility = View.GONE
        val googleGroup = view.findViewById<RadioGroup>(R.id.rg_map_style_google)
        val trafficLabel = view.findViewById<View>(R.id.tv_map_details_label)
        val trafficSwitch = view.findViewById<SwitchMaterial>(R.id.switch_traffic)
        googleGroup.visibility = View.VISIBLE
        trafficLabel.visibility = View.VISIBLE
        trafficSwitch.visibility = View.VISIBLE

        googleGroup.check(
            when (host.currentMapStyle()) {
                GoogleMapStyles.SATELLITE -> R.id.rb_gmap_satellite
                GoogleMapStyles.TERRAIN -> R.id.rb_gmap_terrain
                else -> R.id.rb_gmap_default
            }
        )
        trafficSwitch.isChecked = host.isTrafficEnabled()

        googleGroup.setOnCheckedChangeListener { _, checkedId ->
            host.onMapStyleSelected(
                when (checkedId) {
                    R.id.rb_gmap_satellite -> GoogleMapStyles.SATELLITE
                    R.id.rb_gmap_terrain -> GoogleMapStyles.TERRAIN
                    else -> GoogleMapStyles.DEFAULT
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
