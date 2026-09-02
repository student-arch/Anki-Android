// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ai

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ichi2.anki.R
import com.ichi2.anki.databinding.ItemAiProviderBinding

/** Adapter for the list of configured AI providers in [ProviderConfigFragment]. */
class ProviderAdapter(
    private val onEdit: (AiProvider) -> Unit,
    private val onDelete: (AiProvider) -> Unit,
) : ListAdapter<AiProvider, ProviderAdapter.ViewHolder>(DIFF_CALLBACK) {
    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder = ViewHolder(ItemAiProviderBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) {
        val provider = getItem(position)
        holder.binding.name.text = provider.name
        holder.binding.baseUrl.text = provider.baseUrl
        holder.binding.modelCount.isVisible = provider.modelIds.isNotEmpty()
        holder.binding.modelCount.text =
            holder.itemView.context.getString(R.string.ai_provider_model_count, provider.modelIds.size)
        holder.binding.edit.setOnClickListener { onEdit(provider) }
        holder.binding.delete.setOnClickListener { onDelete(provider) }
    }

    class ViewHolder(
        val binding: ItemAiProviderBinding,
    ) : RecyclerView.ViewHolder(binding.root)

    companion object {
        private val DIFF_CALLBACK =
            object : DiffUtil.ItemCallback<AiProvider>() {
                override fun areItemsTheSame(
                    oldItem: AiProvider,
                    newItem: AiProvider,
                ): Boolean = oldItem.id == newItem.id

                override fun areContentsTheSame(
                    oldItem: AiProvider,
                    newItem: AiProvider,
                ): Boolean = oldItem == newItem
            }
    }
}
