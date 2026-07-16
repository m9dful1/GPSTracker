package com.spiritwisestudios.gpstracker.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.spiritwisestudios.gpstracker.R
import com.spiritwisestudios.gpstracker.data.api.GeocodingApi
import com.spiritwisestudios.gpstracker.domain.model.LatLng
import com.spiritwisestudios.gpstracker.domain.model.TourFocus
import com.spiritwisestudios.gpstracker.domain.model.TourLength
import com.spiritwisestudios.gpstracker.domain.model.TourPlan
import com.spiritwisestudios.gpstracker.ui.viewmodel.TakeATourViewModel
import com.spiritwisestudios.gpstracker.util.CuratedTours
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Take a Tour: pick where (a nearby city, a searched city, or right here),
 * how far, and what to lean toward — or tap a curated tour of a famous
 * destination nearby. Planning finds the area's most tour-worthy places,
 * orders them into a drive, and pre-writes the guide's scripts; the tour
 * then runs through the normal navigation + narration flow.
 */
@AndroidEntryPoint
class TakeATourBottomSheet : BottomSheetDialogFragment() {

    /** Implemented by the hosting activity, which runs the tour. */
    interface TakeATourHost {
        /** The user's position: tour default center and search bias. */
        fun tourLocationBias(): LatLng?
        fun onTourPlanned(plan: TourPlan)
    }

    @Inject
    lateinit var geocodingApi: GeocodingApi

    private val viewModel: TakeATourViewModel by activityViewModels()

    private var searchJob: Job? = null

    /** A place the tour can start from (dropdown entry). */
    private data class StartOption(val name: String, val latLng: LatLng, val isHere: Boolean = false)

    private val startOptions = mutableListOf<StartOption>()
    private lateinit var startAdapter: ArrayAdapter<String>

    private lateinit var citySpinner: Spinner
    private lateinit var citySearch: EditText
    private lateinit var cityResults: RecyclerView
    private lateinit var lengthGroup: RadioGroup
    private lateinit var focusGroup: RadioGroup
    private lateinit var planButton: Button
    private lateinit var statusRow: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var statusProgress: ProgressBar

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_take_a_tour, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val host = activity as? TakeATourHost ?: return
        val here = host.tourLocationBias()

        citySpinner = view.findViewById(R.id.spinner_city)
        citySearch = view.findViewById(R.id.et_city_search)
        cityResults = view.findViewById(R.id.rv_city_results)
        lengthGroup = view.findViewById(R.id.rg_tour_length)
        focusGroup = view.findViewById(R.id.rg_tour_focus)
        planButton = view.findViewById(R.id.btn_plan_tour)
        statusRow = view.findViewById(R.id.planning_status_row)
        statusText = view.findViewById(R.id.tv_planning_status)
        statusProgress = view.findViewById(R.id.progress_planning)

        setupStartPicker(here)
        setupCitySearch(here)
        setupCuratedTours(view, here)

        planButton.setOnClickListener {
            val start = startOptions.getOrNull(citySpinner.selectedItemPosition) ?: return@setOnClickListener
            val name = if (start.isHere) {
                getString(R.string.local_tour_name)
            } else {
                getString(R.string.tour_name_format, start.name)
            }
            viewModel.planTour(name, start.latLng, selectedLength(), selectedFocus())
        }

        observePlanState(host)
    }

    private fun setupStartPicker(here: LatLng?) {
        startAdapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_item, mutableListOf<String>()
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        citySpinner.adapter = startAdapter

        here?.let {
            addStartOption(StartOption(getString(R.string.tour_current_location), it, isHere = true))
            viewModel.loadNearbyCities(it)
        }

        viewModel.nearbyCities.observe(viewLifecycleOwner) { cities ->
            cities.forEach { city ->
                if (startOptions.none { it.name == city.name }) {
                    addStartOption(StartOption(city.name, city.latLng))
                }
            }
        }
    }

    private fun addStartOption(option: StartOption, select: Boolean = false) {
        startOptions.add(option)
        startAdapter.add(option.name)
        if (select) citySpinner.setSelection(startOptions.size - 1)
    }

    /** Type 3+ characters to find any city in the world; tapping one picks it. */
    private fun setupCitySearch(here: LatLng?) {
        val adapter = CityResultAdapter { result ->
            addStartOption(StartOption(result.name, result.latLng), select = true)
            citySearch.setText("")
            cityResults.visibility = View.GONE
        }
        cityResults.layoutManager = LinearLayoutManager(requireContext())
        cityResults.adapter = adapter

        citySearch.doAfterTextChanged { text ->
            val query = text?.toString().orEmpty().trim()
            searchJob?.cancel()

            if (query.length < MIN_QUERY_LENGTH) {
                cityResults.visibility = View.GONE
                adapter.submit(emptyList())
                return@doAfterTextChanged
            }

            searchJob = viewLifecycleOwner.lifecycleScope.launch {
                delay(SEARCH_DEBOUNCE_MS)
                val results = geocodingApi.search(query, here, MAX_CITY_RESULTS)
                adapter.submit(results)
                cityResults.visibility = if (results.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    /** One-tap tours of famous destinations within driving reach. */
    private fun setupCuratedTours(view: View, here: LatLng?) {
        val nearby = here?.let { CuratedTours.near(it) }.orEmpty()
        if (nearby.isEmpty()) return

        view.findViewById<View>(R.id.tv_curated_label).visibility = View.VISIBLE
        val container = view.findViewById<LinearLayout>(R.id.container_curated)
        container.visibility = View.VISIBLE

        nearby.forEach { tour ->
            val button = MaterialButton(
                requireContext(), null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = tour.name
                setOnClickListener {
                    viewModel.planTour(
                        getString(R.string.tour_name_format, tour.name),
                        tour.center, tour.length, tour.focus
                    )
                }
            }
            container.addView(button)
        }
    }

    private fun observePlanState(host: TakeATourHost) {
        viewModel.planState.observe(viewLifecycleOwner) { state ->
            when (state) {
                is TakeATourViewModel.PlanState.Idle -> {
                    statusRow.visibility = View.GONE
                    planButton.isEnabled = true
                }
                is TakeATourViewModel.PlanState.Planning -> {
                    statusRow.visibility = View.VISIBLE
                    statusProgress.visibility = View.VISIBLE
                    statusText.text = getString(state.statusRes)
                    planButton.isEnabled = false
                }
                is TakeATourViewModel.PlanState.Failed -> {
                    statusRow.visibility = View.VISIBLE
                    statusProgress.visibility = View.GONE
                    statusText.text = getString(state.messageRes)
                    planButton.isEnabled = true
                }
                is TakeATourViewModel.PlanState.Ready -> {
                    viewModel.consumePlan()
                    host.onTourPlanned(state.plan)
                    dismiss()
                }
            }
        }
    }

    private fun selectedLength(): TourLength = when (lengthGroup.checkedRadioButtonId) {
        R.id.rb_length_short -> TourLength.SHORT
        R.id.rb_length_long -> TourLength.LONG
        else -> TourLength.MEDIUM
    }

    private fun selectedFocus(): TourFocus = when (focusGroup.checkedRadioButtonId) {
        R.id.rb_focus_history -> TourFocus.HISTORY_AND_CULTURE
        R.id.rb_focus_nature -> TourFocus.NATURE_AND_VIEWS
        R.id.rb_focus_food -> TourFocus.FOOD_AND_FUN
        else -> TourFocus.BALANCED
    }

    override fun onDestroyView() {
        searchJob?.cancel()
        super.onDestroyView()
    }

    private class CityResultAdapter(
        private val onClick: (GeocodingApi.SearchResult) -> Unit
    ) : RecyclerView.Adapter<CityResultAdapter.Holder>() {

        private val results = mutableListOf<GeocodingApi.SearchResult>()

        fun submit(newResults: List<GeocodingApi.SearchResult>) {
            results.clear()
            results.addAll(newResults)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_search_result, parent, false)
            return Holder(view)
        }

        override fun getItemCount(): Int = results.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val result = results[position]
            holder.name.text = result.name
            holder.detail.text = result.detail
            holder.detail.visibility = if (result.detail.isBlank()) View.GONE else View.VISIBLE
            holder.itemView.setOnClickListener { onClick(result) }
        }

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.tv_result_name)
            val detail: TextView = view.findViewById(R.id.tv_result_detail)
        }
    }

    companion object {
        const val TAG = "TakeATourBottomSheet"
        private const val SEARCH_DEBOUNCE_MS = 350L
        private const val MIN_QUERY_LENGTH = 3
        private const val MAX_CITY_RESULTS = 6

        fun newInstance(): TakeATourBottomSheet = TakeATourBottomSheet()
    }
}
