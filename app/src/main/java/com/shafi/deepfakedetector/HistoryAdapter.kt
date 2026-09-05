// HistoryAdapter.kt
package com.shafi.deepfakedetector

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.shafi.deepfakedetector.databinding.ItemHistoryBinding
import java.io.File
import java.util.Locale

class HistoryAdapter(
    private val onItemClick: (HistoryEntry) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.HistoryViewHolder>() {

    private val items = mutableListOf<HistoryEntry>()

    fun submit(entries: List<HistoryEntry>) {
        items.clear()
        items.addAll(entries)
        notifyDataSetChanged()
    }

    class HistoryViewHolder(val binding: ItemHistoryBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val binding = ItemHistoryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return HistoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        val entry = items[position]
        val context = holder.itemView.context

        Glide.with(context)
            .load(File(entry.filePath))
            .centerCrop()
            .into(holder.binding.ivHistoryThumb)

        holder.binding.tvHistoryLabel.text = entry.label
        holder.binding.tvHistoryLabel.setTextColor(
            ContextCompat.getColor(
                context,
                if (entry.label == "REAL") R.color.colorRealGreen else R.color.colorFakeRed
            )
        )
        holder.binding.tvHistoryConf.text =
            String.format(Locale.US, "%.0f%%", entry.confidence)

        holder.itemView.setOnClickListener { onItemClick(entry) }
    }

    override fun getItemCount(): Int = items.size
}