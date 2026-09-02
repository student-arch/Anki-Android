// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ai

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ichi2.anki.databinding.ItemGeneratedCardBinding

/** A generated card plus whether the user accepted (selected) it. */
data class ReviewItem(
    val card: GeneratedFlashcard,
    val isSelected: Boolean,
)

/**
 * Adapter for the review step: shows the generated front/back, and lets the user
 * accept (checkbox), edit or reject (delete) each card. The selection state is owned
 * by [FlashcardGenerationViewModel]; the adapter only renders and forwards events.
 */
class GeneratedCardsAdapter : ListAdapter<ReviewItem, GeneratedCardsAdapter.ViewHolder>(DIFF_CALLBACK) {
    private var callbacks: Callbacks? = null

    fun setCallbacks(callbacks: Callbacks) {
        this.callbacks = callbacks
    }

    interface Callbacks {
        fun onSelectionChanged(
            card: GeneratedFlashcard,
            isSelected: Boolean,
        )

        fun onEdit(card: GeneratedFlashcard)

        fun onReject(card: GeneratedFlashcard)
    }

    /** Replaces the displayed cards, applying [acceptedIds] to the checkboxes. */
    fun submitCards(
        cards: List<GeneratedFlashcard>,
        acceptedIds: Set<Long>,
    ) {
        submitList(cards.map { card -> ReviewItem(card, isSelected = card.id in acceptedIds) })
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder = ViewHolder(ItemGeneratedCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) {
        val item = getItem(position)
        val callbacks = this.callbacks
        holder.binding.front.text = item.card.front
        holder.binding.back.text = item.card.back
        holder.binding.accepted.setOnCheckedChangeListener(null)
        holder.binding.accepted.isChecked = item.isSelected
        holder.binding.accepted.setOnCheckedChangeListener { _, isChecked ->
            callbacks?.onSelectionChanged(item.card, isChecked)
        }
        holder.binding.edit.setOnClickListener { callbacks?.onEdit(item.card) }
        holder.binding.reject.setOnClickListener { callbacks?.onReject(item.card) }
    }

    class ViewHolder(
        val binding: ItemGeneratedCardBinding,
    ) : RecyclerView.ViewHolder(binding.root)

    companion object {
        private val DIFF_CALLBACK =
            object : DiffUtil.ItemCallback<ReviewItem>() {
                override fun areItemsTheSame(
                    oldItem: ReviewItem,
                    newItem: ReviewItem,
                ): Boolean = oldItem.card.id == newItem.card.id

                override fun areContentsTheSame(
                    oldItem: ReviewItem,
                    newItem: ReviewItem,
                ): Boolean = oldItem == newItem
            }
    }
}
