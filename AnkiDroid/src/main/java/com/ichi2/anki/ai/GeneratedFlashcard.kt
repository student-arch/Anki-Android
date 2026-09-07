// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ai

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * A named content section of a [GeneratedFlashcard] (e.g. "Explanation", "Code"),
 * in the order it should be displayed.
 */
@Parcelize
data class CardSection(
    val label: String,
    val content: String,
) : Parcelable

/** A flashcard proposed by the AI, before the user accepts or rejects it. */
@Parcelize
data class GeneratedFlashcard(
    val id: Long = nextId(),
    val front: String,
    val back: String,
    val sections: List<CardSection> = emptyList(),
    val tags: List<String> = emptyList(),
    /** Engineering branch / subject, e.g. "Electrical Engineering". Shown as a header. */
    val subject: String? = null,
    /** Specific topic, e.g. "Ohm's Law". Shown as a header. */
    val topic: String? = null,
    /** Direct URL of an image present in the source, downloaded into collection.media on add. */
    val imageUrl: String? = null,
    /** Structured image metadata: whether a visual is beneficial and how to make it. */
    val image: CardImage = CardImage.None,
) : Parcelable {
    companion object {
        private val idCounter =
            java.util.concurrent.atomic
                .AtomicLong(0)

        private fun nextId(): Long = idCounter.incrementAndGet()
    }
}

/**
 * Image metadata for a [GeneratedFlashcard]. When [required] is true the app generates an image
 * from [prompt] (via the configured IMAGE provider) and attaches it with [caption]/[alt].
 */
@Parcelize
data class CardImage(
    val required: Boolean = false,
    val type: String? = null,
    val prompt: String? = null,
    val alt: String? = null,
    val caption: String? = null,
) : Parcelable {
    companion object {
        val None = CardImage()
    }
}
