package com.spiritwisestudios.gpstracker.ui.fragment

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.spiritwisestudios.gpstracker.R
import com.spiritwisestudios.gpstracker.databinding.BottomSheetTourJournalBinding
import com.spiritwisestudios.gpstracker.databinding.ItemJournalEntryBinding
import com.spiritwisestudios.gpstracker.domain.model.PointOfInterest
import com.spiritwisestudios.gpstracker.ui.viewmodel.PlacesViewModel
import com.spiritwisestudios.gpstracker.util.JournalFormatter
import dagger.hilt.android.AndroidEntryPoint

/**
 * Journal of every place the tour guide has narrated, newest first.
 * Tapping an entry reopens its details sheet.
 */
@AndroidEntryPoint
class TourJournalBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetTourJournalBinding? = null
    private val binding get() = _binding!!

    private val placesViewModel: PlacesViewModel by activityViewModels()

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

        val adapter = JournalAdapter(::formatVisited) { place ->
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
            binding.tvJournalSubtitle.text = if (places.isEmpty()) {
                getString(R.string.journal_nothing_narrated)
            } else {
                resources.getQuantityString(
                    R.plurals.journal_places_discovered, places.size, places.size
                )
            }
            binding.btnJournalShare.isEnabled = places.isNotEmpty()
        })

        binding.btnJournalShare.setOnClickListener {
            val places = placesViewModel.visitedPlaces.value ?: return@setOnClickListener
            val text = JournalFormatter.shareText(places, ::formatVisited)
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            startActivity(Intent.createChooser(sendIntent, getString(R.string.share_journal_chooser)))
        }
    }

    /**
     * When a place was narrated, in the device's own format.
     *
     * `DateUtils` follows both the locale and the 12/24-hour setting, which a
     * pattern cannot: this used to be `"MMM d 'at' h:mm a"`, so a listener on
     * 24-hour time read "3:45 PM" here and "15:45" on the navigation card. A13
     * and A20 fixed that for the ETA; the journal never caught up. The literal
     * "at" was untranslated English inside a date pattern, too.
     */
    private fun formatVisited(millis: Long): String = DateUtils.formatDateTime(
        requireContext(),
        millis,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH
    )

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class JournalAdapter(
        private val formatVisited: (Long) -> String,
        private val onClick: (PointOfInterest) -> Unit
    ) : ListAdapter<PointOfInterest, JournalAdapter.EntryViewHolder>(DIFF) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EntryViewHolder {
            val binding = ItemJournalEntryBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return EntryViewHolder(binding, formatVisited, onClick)
        }

        override fun onBindViewHolder(holder: EntryViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        class EntryViewHolder(
            private val binding: ItemJournalEntryBinding,
            private val formatVisited: (Long) -> String,
            private val onClick: (PointOfInterest) -> Unit
        ) : RecyclerView.ViewHolder(binding.root) {

            fun bind(place: PointOfInterest) {
                binding.tvEntryName.text = place.name
                binding.tvEntryMeta.text = listOfNotNull(
                    place.category,
                    place.visitedDate?.let(formatVisited)
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
