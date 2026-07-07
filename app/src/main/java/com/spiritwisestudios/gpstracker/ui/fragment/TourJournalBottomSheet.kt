package com.spiritwisestudios.gpstracker.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.spiritwisestudios.gpstracker.databinding.BottomSheetTourJournalBinding
import com.spiritwisestudios.gpstracker.databinding.ItemJournalEntryBinding
import com.spiritwisestudios.gpstracker.domain.model.PointOfInterest
import com.spiritwisestudios.gpstracker.ui.viewmodel.PlacesViewModel
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Journal of every place the tour guide has narrated, newest first.
 * Tapping an entry reopens its details sheet.
 */
@AndroidEntryPoint
class TourJournalBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetTourJournalBinding? = null
    private val binding get() = _binding!!

    private lateinit var placesViewModel: PlacesViewModel

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetTourJournalBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        placesViewModel = ViewModelProvider(requireActivity())[PlacesViewModel::class.java]

        val adapter = JournalAdapter { place ->
            placesViewModel.selectPlace(place.placeId ?: place.id)
            PlaceDetailsBottomSheet.newInstance()
                .show(parentFragmentManager, PlaceDetailsBottomSheet.TAG)
            dismiss()
        }
        binding.rvJournalEntries.layoutManager = LinearLayoutManager(requireContext())
        binding.rvJournalEntries.adapter = adapter

        placesViewModel.visitedPlaces.observe(viewLifecycleOwner, Observer { places ->
            adapter.submitList(places)
            binding.tvJournalEmpty.visibility = if (places.isEmpty()) View.VISIBLE else View.GONE
            binding.tvJournalSubtitle.text = when (places.size) {
                0 -> "Nothing narrated yet"
                1 -> "1 place discovered"
                else -> "${places.size} places discovered"
            }
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class JournalAdapter(
        private val onClick: (PointOfInterest) -> Unit
    ) : ListAdapter<PointOfInterest, JournalAdapter.EntryViewHolder>(DIFF) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EntryViewHolder {
            val binding = ItemJournalEntryBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return EntryViewHolder(binding, onClick)
        }

        override fun onBindViewHolder(holder: EntryViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        class EntryViewHolder(
            private val binding: ItemJournalEntryBinding,
            private val onClick: (PointOfInterest) -> Unit
        ) : RecyclerView.ViewHolder(binding.root) {

            private val dateFormat = SimpleDateFormat("MMM d 'at' h:mm a", Locale.getDefault())

            fun bind(place: PointOfInterest) {
                binding.tvEntryName.text = place.name
                binding.tvEntryMeta.text = listOfNotNull(
                    place.category,
                    place.visitedDate?.let { dateFormat.format(Date(it)) }
                ).joinToString(" · ")
                binding.root.setOnClickListener { onClick(place) }
            }
        }

        companion object {
            val DIFF = object : DiffUtil.ItemCallback<PointOfInterest>() {
                override fun areItemsTheSame(old: PointOfInterest, new: PointOfInterest) =
                    old.id == new.id

                override fun areContentsTheSame(old: PointOfInterest, new: PointOfInterest) =
                    old == new
            }
        }
    }

    companion object {
        const val TAG = "TourJournalBottomSheet"

        fun newInstance() = TourJournalBottomSheet()
    }
}
