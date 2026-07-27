package com.spiritwisestudios.gpstracker.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.spiritwisestudios.gpstracker.R
import com.spiritwisestudios.gpstracker.data.api.GeocodingApi

/**
 * The geocoder's results as a tappable list, shared by the destination
 * search sheet and Take a Tour's city search — they were two identical
 * adapters, down to the item layout and the holder.
 *
 * A [ListAdapter]: results arrive per keystroke, and rows that survive a
 * keystroke should not be rebound. The full-list `notifyDataSetChanged` this
 * replaces rebuilt every visible row on every response.
 */
class SearchResultAdapter(
    private val onClick: (GeocodingApi.SearchResult) -> Unit
) : ListAdapter<GeocodingApi.SearchResult, SearchResultAdapter.Holder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_search_result, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val result = getItem(position)
        holder.name.text = result.name
        holder.detail.text = result.detail
        holder.detail.visibility = if (result.detail.isBlank()) View.GONE else View.VISIBLE
        holder.itemView.setOnClickListener { onClick(result) }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.tv_result_name)
        val detail: TextView = view.findViewById(R.id.tv_result_detail)
    }

    private companion object {
        /**
         * A search result has no id, so a place is "the same place" when its
         * name and coordinates match — which is also what makes a refined
         * query keep the rows it already had.
         */
        val DIFF = object : DiffUtil.ItemCallback<GeocodingApi.SearchResult>() {
            override fun areItemsTheSame(
                oldItem: GeocodingApi.SearchResult,
                newItem: GeocodingApi.SearchResult
            ): Boolean = oldItem.name == newItem.name && oldItem.latLng == newItem.latLng

            override fun areContentsTheSame(
                oldItem: GeocodingApi.SearchResult,
                newItem: GeocodingApi.SearchResult
            ): Boolean = oldItem == newItem
        }
    }
}
