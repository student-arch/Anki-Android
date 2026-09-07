// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ai

/**
 * Renders [GeneratedFlashcard]s for display: the Anki note's back field (HTML) and the
 * plain-text preview shown in the review list.
 *
 * Sections are appended below the answer as labeled blocks; formula/units/code-like sections are
 * rendered in `<pre>` so layout, indentation and line breaks are preserved.
 */
object FlashcardRenderer {
    /** Base labels rendered in `<pre>` (monospace, layout preserved) instead of prose markup. */
    private val CODE_LABELS =
        setOf(
            "Syntax",
            "Code",
            "Output",
            "Diagram",
            "Formula",
            "Variables & Units",
            "Units",
            "Given",
            "Solution",
            "Final Answer",
        )

    /** @return the HTML for the Anki note's back field built from [card] (without the image). */
    fun ankiBack(card: GeneratedFlashcard): String {
        val header = subjectHeader(card)
        val answer = boldToHtml(htmlEscape(card.back)).replace("\n", "<br>")
        if (card.sections.isEmpty()) return header + answer
        val sections =
            card.sections.joinToString(separator = "") { section ->
                val escaped = htmlEscape(section.content)
                if (section.label.substringBefore(" (") in CODE_LABELS) {
                    "<div><b>${htmlEscape(section.label)}</b><pre>$escaped</pre></div>"
                } else {
                    "<div><b>${htmlEscape(section.label)}</b><br>${boldToHtml(escaped).replace("\n", "<br>")}</div>"
                }
            }
        return header + answer + sections
    }

    /** Converts `**bold**` markdown (as emitted by the model) to `<b>` after HTML escaping. */
    private fun boldToHtml(escaped: String): String = Regex("\\*\\*(.+?)\\*\\*").replace(escaped) { "<b>${it.groupValues[1]}</b>" }

    /** Removes `**bold**` markers for the plain-text review preview. */
    private fun stripBold(text: String): String = text.replace("**", "")

    /** @return a small "SUBJECT · TOPIC" header line, or "" when neither is present. */
    private fun subjectHeader(card: GeneratedFlashcard): String {
        val parts = listOfNotNull(card.subject, card.topic)
        if (parts.isEmpty()) return ""
        return "<div style=\"opacity:0.7;font-size:0.85em;\">" + htmlEscape(parts.joinToString(" \u00b7 ")) + "</div>"
    }

    /** @return the caption HTML shown under a card's image, or "" when the card has no caption. */
    fun captionHtml(card: GeneratedFlashcard): String {
        val caption = card.image.caption ?: return ""
        return "<div style=\"text-align:center;font-size:0.85em;opacity:0.8;\">" + htmlEscape(caption) + "</div>"
    }

    /** @return the plain multi-line text shown in the review list for [card]. */
    fun reviewText(card: GeneratedFlashcard): String {
        val parts = mutableListOf<String>()
        listOfNotNull(card.subject, card.topic).takeIf { it.isNotEmpty() }?.let { parts += it.joinToString(" \u00b7 ") }
        parts += stripBold(card.back)
        card.sections.forEach { parts += "${it.label}\n${stripBold(it.content)}" }
        card.imageUrl?.let { parts += "Image\n$it" }
        if (card.image.required) parts += "Image\n${card.image.alt ?: card.image.prompt ?: "(generated illustration)"}"
        return parts.joinToString(separator = "\n\n")
    }

    private fun htmlEscape(text: String): String =
        text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
