// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ai

/**
 * Renders [GeneratedFlashcard]s for display: the Anki note's back field (HTML) and the
 * plain-text preview shown in the review list.
 *
 * Sections are appended below the answer as labeled blocks; code-like sections are
 * rendered in `<pre>` so indentation and line breaks are preserved.
 */
object FlashcardRenderer {
    /** Base labels of sections rendered in `<pre>` instead of prose markup. */
    private val CODE_LABELS = setOf("Syntax", "Code", "Output", "Diagram")

    /** @return the HTML for the Anki note's back field built from [card]. */
    fun ankiBack(card: GeneratedFlashcard): String {
        if (card.sections.isEmpty()) return card.back
        val sections =
            card.sections.joinToString(separator = "") { section ->
                val content = htmlEscape(section.content)
                if (section.label.substringBefore(" (") in CODE_LABELS) {
                    "<div><b>${htmlEscape(section.label)}</b><pre>$content</pre></div>"
                } else {
                    "<div><b>${htmlEscape(section.label)}</b><br>${content.replace("\n", "<br>")}</div>"
                }
            }
        return card.back + sections
    }

    /** @return the plain multi-line text shown in the review list for [card]. */
    fun reviewText(card: GeneratedFlashcard): String {
        val parts = mutableListOf(card.back)
        card.sections.forEach { parts += "${it.label}\n${it.content}" }
        card.imageUrl?.let { parts += "Image\n$it" }
        return parts.joinToString(separator = "\n\n")
    }

    private fun htmlEscape(text: String): String =
        text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
