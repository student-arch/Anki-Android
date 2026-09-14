// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ai

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber

/**
 * Parses the flashcards contained in an AI response.
 *
 * The expected format is the engineering schema `{"cards": [{"question": "...", "answer": "...",
 * "<section>": "...", "image": {"required": true, "prompt": "...", ...}]}` where each card carries
 * the optional sections defined in [SECTION_KEYS], `subject`/`topic`/`tags` metadata and a
 * structured `image` object. The parser is defensive: code fences, surrounding prose, a top-level
 * JSON array, array-valued sections and the legacy `{"front": ..., "back": ...}` / string-`image`
 * schemas are all tolerated.
 */
object FlashcardParser {
    private const val MAX_CARDS = 100

    /** Content sections of the engineering format, in canonical display order. */
    private val SECTION_KEYS =
        linkedMapOf(
            "definition" to "Definition",
            "key_points" to "Key Points",
            "formula" to "Formula",
            "variables" to "Variables & Units",
            "units" to "Units",
            "given" to "Given",
            "solution" to "Solution",
            "final_answer" to "Final Answer",
            "example" to "Example",
            "explanation" to "Explanation",
            "syntax" to "Syntax",
            "code" to "Code",
            "code_explanation" to "Code Explanation",
            "output" to "Output",
            "algorithm" to "Algorithm",
            "time_complexity" to "Time Complexity",
            "space_complexity" to "Space Complexity",
            "use_cases" to "Use Cases",
            "advantages" to "Advantages",
            "disadvantages" to "Disadvantages",
            "comparison" to "Comparison",
            "common_mistake" to "Common Mistake",
            "common_mistakes" to "Common Mistakes",
            "practical_scenario" to "Practical Scenario",
            "related_concepts" to "Related Concepts",
            "diagram" to "Diagram",
            "interview_questions" to "Interview Questions",
            "source" to "Source",
        )

    /**
     * @return the flashcards found in [text], with whitespace trimmed and cards missing
     * a question or answer dropped. Never throws. Weak models sometimes emit truncated or
     * malformed JSON, so if the structured parse yields nothing we salvage every individually
     * parseable card object from the raw text.
     */
    fun parse(text: String): List<GeneratedFlashcard> =
        try {
            // object form: {"cards": [...]}
            val objectCards = extractJsonObject(text)?.optJSONArray("cards")
            // bare array form: [{...}, {...}]
            val arrayCards = if (objectCards == null) extractJsonArray(text) else null
            val parsed = parseCardsArray(objectCards ?: arrayCards)
            if (parsed.isNotEmpty()) parsed else salvageCards(text)
        } catch (e: JSONException) {
            Timber.w(e, "failed to parse flashcards from AI response")
            salvageCards(text)
        }

    /**
     * Last-resort recovery for truncated/malformed model output: scan the raw text for
     * balanced `{...}` card objects (string-aware), parse each independently and keep the
     * well-formed ones. A truncated final card is dropped without losing the earlier ones.
     */
    private fun salvageCards(text: String): List<GeneratedFlashcard> {
        val cards = mutableListOf<GeneratedFlashcard>()
        var i = 0
        while (i < text.length && cards.size < MAX_CARDS) {
            if (text[i] == '{') {
                val end = matchBrace(text, i)
                if (end > i) {
                    runCatching { JSONObject(text.substring(i, end + 1)) }
                        .getOrNull()
                        ?.let { obj -> parseCard(obj)?.let { cards += it } }
                    i = end + 1
                    continue
                }
            }
            i++
        }
        return cards
    }

    /** @return the index of the `}` matching the `{` at [start] (string/escape aware), or -1. */
    private fun matchBrace(
        text: String,
        start: Int,
    ): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (j in start until text.length) {
            val c = text[j]
            when {
                inString ->
                    when {
                        escaped -> escaped = false
                        c == '\\' -> escaped = true
                        c == '"' -> inString = false
                    }
                c == '"' -> inString = true
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return j
                }
            }
        }
        return -1
    }

    private fun parseCardsArray(array: JSONArray?): List<GeneratedFlashcard> {
        if (array == null) return emptyList()
        return List(array.length()) { index -> array.optJSONObject(index) }
            .filterNotNull()
            .mapNotNull { card -> parseCard(card) }
            .take(MAX_CARDS)
    }

    /** @return the card built from [card], or null when it lacks a question or answer. */
    private fun parseCard(card: JSONObject): GeneratedFlashcard? {
        val front = normalize(card.optString("question").ifEmpty { card.optString("front") })
        val back = normalize(card.optString("answer").ifEmpty { card.optString("back") })
        if (front.isNullOrEmpty() || back.isNullOrEmpty()) return null
        return GeneratedFlashcard(
            front = front,
            back = back,
            sections = parseSections(card),
            tags = parseTags(card),
            subject = normalize(card.optString("subject")),
            topic = normalize(card.optString("topic")),
            imageUrl = parseImageUrl(card),
            image = parseImage(card),
        )
    }

    /** @return the card's content sections in canonical order, skipping empty/absent keys. */
    private fun parseSections(card: JSONObject): List<CardSection> {
        val language = normalize(card.optString("code_language"))
        return SECTION_KEYS.mapNotNull { (key, label) ->
            val content = textOf(card, key, bullet = key == "key_points") ?: return@mapNotNull null
            val displayLabel = if (key == "code" && language != null) "$label ($language)" else label
            CardSection(label = displayLabel, content = content)
        }
    }

    /**
     * @return the value of [key] as display text, joining JSON arrays line by line (optionally
     * bulleted), or null when absent/empty. Structured objects are ignored here (handled elsewhere).
     */
    private fun textOf(
        card: JSONObject,
        key: String,
        bullet: Boolean = false,
    ): String? =
        when (val value = card.opt(key)) {
            is JSONArray -> {
                val items = List(value.length()) { value.optString(it) }.map { it.trim() }.filter { it.isNotEmpty() && it != "null" }
                if (items.isEmpty()) null else items.joinToString("\n") { if (bullet) "\u2022 $it" else it }
            }
            is JSONObject -> null
            else -> normalize(card.optString(key))
        }

    /**
     * @return the card's structured image metadata. Accepts the `image` object
     * `{required,type,prompt,alt,caption}` and, as a fallback, a flat `image_prompt` string
     * (some models ignore the nested shape). A required image without a prompt is not required.
     */
    private fun parseImage(card: JSONObject): CardImage {
        (card.opt("image") as? JSONObject)?.let { obj ->
            val prompt = normalize(obj.optString("prompt"))
            return CardImage(
                required = obj.optBoolean("required", false) && prompt != null,
                type = normalize(obj.optString("type")),
                prompt = prompt,
                alt = normalize(obj.optString("alt")),
                caption = normalize(obj.optString("caption")),
            )
        }
        // fallback: a flat image_prompt string means the model wants an image generated
        val flatPrompt = normalize(card.optString("image_prompt"))
        return if (flatPrompt != null) {
            CardImage(required = true, type = "engineering_diagram", prompt = flatPrompt, alt = null, caption = null)
        } else {
            CardImage.None
        }
    }

    /**
     * @return the card's direct image URL, from a legacy string `image` value (a bare URL, a
     * Markdown image `![alt](url)` or an HTML `<img src="url">`), or null when absent/not a URL.
     */
    private fun parseImageUrl(card: JSONObject): String? {
        val raw = card.opt("image") as? String ?: return null
        val markdown = Regex("!\\[[^\\]]*]\\(([^)\\s]+)").find(raw)?.groupValues?.get(1)
        val html = Regex("<img[^>]+src=\"([^\"]+)\"").find(raw)?.groupValues?.get(1)
        val candidate = (markdown ?: html ?: raw).trim()
        return if (candidate.startsWith("http://") || candidate.startsWith("https://")) candidate else null
    }

    /** @return the card's tags: the `tags` value plus `difficulty`, `subject` and `topic`. */
    private fun parseTags(card: JSONObject): List<String> {
        val declared =
            when (val tags = card.opt("tags")) {
                is JSONArray -> List(tags.length()) { tags.optString(it) }
                is String -> tags.split(",")
                else -> emptyList()
            }
        val metadata = listOf("difficulty", "subject", "topic").mapNotNull { key -> normalize(card.optString(key)) }
        return (declared + metadata)
            .map { it.trim().replace(Regex("\\s+"), "-") }
            .filter { it.isNotEmpty() }
            .distinct()
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
