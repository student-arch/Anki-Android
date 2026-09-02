// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ai

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber

/**
 * Parses the flashcards contained in an AI response.
 *
 * The expected format is `{"cards": [{"front": "...", "back": "..."}]}`, but the parser is
 * defensive: code fences, surrounding prose and a top-level JSON array are all tolerated.
 */
object FlashcardParser {
    private const val MAX_CARDS = 100

    /**
     * @return the flashcards found in [text], with whitespace trimmed and cards missing
     * a front or back dropped. Never throws.
     */
    fun parse(text: String): List<GeneratedFlashcard> =
        try {
            // object form: {"cards": [...]}
            val objectCards = extractJsonObject(text)?.optJSONArray("cards")
            // bare array form: [{...}, {...}]
            val arrayCards = if (objectCards == null) extractJsonArray(text) else null
            parseCardsArray(objectCards ?: arrayCards)
        } catch (e: JSONException) {
            Timber.w(e, "failed to parse flashcards from AI response")
            emptyList()
        }

    private fun parseCardsArray(array: JSONArray?): List<GeneratedFlashcard> {
        if (array == null) return emptyList()
        return List(array.length()) { index -> array.optJSONObject(index) }
            .filterNotNull()
            .mapNotNull { card ->
                val front = normalize(card.optString("front"))
                val back = normalize(card.optString("back"))
                if (front.isNullOrEmpty() || back.isNullOrEmpty()) null else GeneratedFlashcard(front = front, back = back)
            }.take(MAX_CARDS)
    }

    /** Treats a missing field and a JSON `null` value (which org.json renders as `"null"`) alike. */
    private fun normalize(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed == "null") return null
        return trimmed
    }

    /** Extracts the outermost JSON object from [text], tolerating code fences and prose. */
    fun extractJsonObject(text: String): JSONObject? =
        substringBetween(text, '{', '}')?.let {
            try {
                JSONObject(it)
            } catch (e: JSONException) {
                Timber.w(e, "response is not a JSON object")
                null
            }
        }

    private fun extractJsonArray(text: String): JSONArray? =
        substringBetween(text, '[', ']')?.let {
            try {
                JSONArray(it)
            } catch (e: JSONException) {
                Timber.w(e, "response is not a JSON array")
                null
            }
        }

    private fun substringBetween(
        text: String,
        start: Char,
        end: Char,
    ): String? {
        val startIndex = text.indexOf(start)
        if (startIndex < 0) return null
        val endIndex = text.lastIndexOf(end)
        if (endIndex <= startIndex) return null
        return text.substring(startIndex, endIndex + 1)
    }
}
