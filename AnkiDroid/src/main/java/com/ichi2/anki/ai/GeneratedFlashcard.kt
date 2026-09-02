// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ai

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/** A flashcard proposed by the AI, before the user accepts or rejects it. */
@Parcelize
data class GeneratedFlashcard(
    val id: Long,
    val front: String,
    val back: String,
) : Parcelable {
    constructor(front: String, back: String) : this(id = nextId(), front = front, back = back)

    companion object {
        private val idCounter =
            java.util.concurrent.atomic
                .AtomicLong(0)

        private fun nextId(): Long = idCounter.incrementAndGet()
    }
}
