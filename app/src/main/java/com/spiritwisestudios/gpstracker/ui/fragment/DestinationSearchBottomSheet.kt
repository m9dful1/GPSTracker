package com.spiritwisestudios.gpstracker.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import com.spiritwisestudios.gpstracker.ui.adapter.SearchResultAdapter
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.spiritwisestudios.gpstracker.domain.model.LatLng
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.spiritwisestudios.gpstracker.R
import com.spiritwisestudios.gpstracker.data.api.GeocodingApi
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Destination search sheet: type a place or address, results update as you
 * type, tapping one starts navigation. Backed by the Photon geocoder
 * (OpenStreetMap) or Google Places Text Search, per the map-provider
 * setting.
 */
@AndroidEntryPoint
class DestinationSearchBottomSheet : BottomSheetDialogFragment() {

    /** Implemented by the hosting activity, which starts the navigation. */
    interface DestinationSearchHost {
        /** Location used to bias results toward the user, or null. */
        fun searchLocationBias(): LatLng?
        fun onDestinationSelected(name: String, latLng: LatLng)
    }

    @Inject
    lateinit var geocodingApi: GeocodingApi

    private var searchJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_destination_search, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val host = activity as? DestinationSearchHost ?: return

        val queryInput = view.findViewById<EditText>(R.id.et_search_query)
        val statusText = view.findViewById<TextView>(R.id.tv_search_status)
        val resultsList = view.findViewById<RecyclerView>(R.id.rv_search_results)

        val adapter = SearchResultAdapter { result ->
            host.onDestinationSelected(result.name, result.latLng)
            dismiss()
        }
        resultsList.layoutManager = LinearLayoutManager(requireContext())
        resultsList.adapter = adapter

        queryInput.doAfterTextChanged { text ->
            val query = text?.toString().orEmpty().trim()
            searchJob?.cancel()

            if (query.length < MIN_QUERY_LENGTH) {
                statusText.visibility = View.GONE
                adapter.submitList(emptyList())
                return@doAfterTextChanged
            }

            searchJob = viewLifecycleOwner.lifecycleScope.launch {
                delay(SEARCH_DEBOUNCE_MS) // wait for the user to stop typing
                val results = geocodingApi.search(query, host.searchLocationBias())

                statusText.visibility = if (results.isEmpty()) View.VISIBLE else View.GONE
                statusText.text = getString(R.string.search_no_results, query)
                adapter.submitList(results)
            }
        }

        queryInput.requestFocus()
    }

    override fun onDestroyView() {
        searchJob?.cancel()
        super.onDestroyView()
    }

    companion object {
        const val TAG = "DestinationSearchBottomSheet"
        private const val SEARCH_DEBOUNCE_MS = 350L
        private const val MIN_QUERY_LENGTH = 3

        fun newInstance(): DestinationSearchBottomSheet = DestinationSearchBottomSheet()
    }
}
