// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ai

import android.os.Parcelable
import androidx.annotation.StringRes
import com.ichi2.anki.R
import kotlinx.parcelize.Parcelize

/**
 * The generation tasks for which a provider and a model can be configured.
 *
 * Each task can use an independent provider + model pair, or share the same one.
 */
enum class AiTaskType(
    @StringRes val labelRes: Int,
) {
    FLASHCARD(R.string.ai_task_flashcards),
    IMAGE(R.string.ai_task_images),
}

/**
 * A user-configured AI provider (any OpenAI-compatible HTTP API).
 *
 * @param id stable unique identifier
 * @param name user-facing display name
 * @param baseUrl root of the provider's OpenAI-compatible API, e.g. `https://api.openai.com/v1`
 * @param apiKey the provider API key (secret, never included in logs or crash reports)
 * @param modelIds model identifiers offered by the provider, as returned by its `/models` endpoint
 */
@Parcelize
data class AiProvider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val modelIds: List<String> = emptyList(),
) : Parcelable {
    companion object {
        fun newId(): String =
            java.util.UUID
                .randomUUID()
                .toString()
    }
}
