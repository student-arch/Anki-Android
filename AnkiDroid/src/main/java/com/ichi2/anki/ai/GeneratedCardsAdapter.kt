// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ai

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ichi2.anki.databinding.ItemGeneratedCardBinding

/** A generated card plus whether the user accepted (selected) it and its image state. */
data class ReviewItem(
    val card: GeneratedFlashcard,
    val isSelected: Boolean,
    val image: CardImageState? = null,
    val isRetrySelected: Boolean = false,
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

        /** Requests regeneration of [card]'s failed image. */
        fun onRetryImage(card: GeneratedFlashcard)

        /** Marks [card]'s failed image as (de)selected for the batch "Try Again". */
        fun onRetrySelectionChanged(
            card: GeneratedFlashcard,
            isSelected: Boolean,
        )
    }

    /** Replaces the displayed cards, applying [acceptedIds] to the checkboxes and [imageStates] to the previews. */
    fun submitCards(
        cards: List<GeneratedFlashcard>,
        acceptedIds: Set<Long>,
        imageStates: Map<Long, CardImageState> = emptyMap(),
        retrySelection: Set<Long> = emptySet(),
    ) {
        submitList(
            cards.map { card ->
                ReviewItem(
                    card = card,
                    isSelected = card.id in acceptedIds,
                    image = imageStates[card.id],
                    isRetrySelected = card.id in retrySelection,
                )
            },
        )
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
        val header = listOfNotNull(item.card.subject, item.card.topic).joinToString(" \u00b7 ")
        holder.binding.subject.isVisible = header.isNotEmpty()
        holder.binding.subject.text = header
        holder.binding.front.text = item.card.front
        holder.binding.back.text = FlashcardRenderer.reviewText(item.card)
        holder.binding.accepted.setOnCheckedChangeListener(null)
        holder.binding.accepted.isChecked = item.isSelected
        holder.binding.accepted.setOnCheckedChangeListener { _, isChecked ->
            callbacks?.onSelectionChanged(item.card, isChecked)
        }
        holder.binding.edit.setOnClickListener { callbacks?.onEdit(item.card) }
        holder.binding.reject.setOnClickListener { callbacks?.onReject(item.card) }
        holder.binding.retryImage.setOnClickListener { callbacks?.onRetryImage(item.card) }
        holder.binding.retrySelected.setOnCheckedChangeListener(null)
        holder.binding.retrySelected.isChecked = item.isRetrySelected
        holder.binding.retrySelected.setOnCheckedChangeListener { _, isChecked ->
            callbacks?.onRetrySelectionChanged(item.card, isChecked)
        }
        bindImage(holder, item.card, item.image)
    }

    /**
     * Shows the generated image (with caption), a "generating" spinner, or a failed state with
     * per-image Try Again, per [state].
     */
    private fun bindImage(
        holder: ViewHolder,
        card: GeneratedFlashcard,
        state: CardImageState?,
    ) {
        with(holder.binding) {
            when (state) {
                is CardImageState.Ready -> {
                    imageGenerating.isVisible = false
                    imageFailed.isVisible = false
                    cardImage.isVisible = true
                    cardImage.setImageBitmap(BitmapFactory.decodeByteArray(state.bytes, 0, state.bytes.size))
                    val caption = card.image.caption
                    imageCaption.isVisible = caption != null
                    imageCaption.text = caption
                }
                CardImageState.Generating -> {
                    cardImage.isVisible = false
                    imageCaption.isVisible = false
                    imageFailed.isVisible = false
                    imageGenerating.isVisible = true
                }
                CardImageState.Failed, null -> {
                    cardImage.isVisible = false
                    imageCaption.isVisible = false
                    imageGenerating.isVisible = false
                    // a Failed state only exists for cards that requested an image at all
                    imageFailed.isVisible = state is CardImageState.Failed
                }
            }
        }
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
