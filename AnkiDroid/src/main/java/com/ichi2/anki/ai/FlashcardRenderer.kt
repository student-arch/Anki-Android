// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ai

/**
 * Renders [GeneratedFlashcard]s for display: the Anki note's back field (HTML) and the
 * plain-text preview shown in the review list.
 *
 * Sections are appended below the answer as labeled blocks; code-like sections are
 * rendered in `<pre>` so layout, indentation and line breaks are preserved.
 *
 * Math the model emits inside MathJax delimiters (`\(...\)`, `\[...\]`) must reach the
 * card HTML untouched: the reviewer typesets it with the bundled MathJax (chemistry
 * `\ce{...}`, quantum `\ket{...}`/`\braket{...}` and all TeX are compiled in). Two
 * things would break that:
 * * HTML escaping inside the delimiters (an `&` in a matrix becomes `&amp;`, `<` in
 *   text with `<x` becomes a broken tag), so math spans are escaped as a unit with
 *   their delimiters intact;
 * * MathJax skips `<pre>`/`<code>` content (`skipHtmlTags`), so formula sections
 *   containing delimiters render in normal flow instead.
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

    /**
     * Matches a complete math span the model may emit: inline `\(...\)` or block `\[...\]`,
     * including any nested braces (balanced-count, not regex-exact, to stay forgiving).
     */
    private val mathSpan = Regex("""\\[(\[]((?:[^\\\[\]]|\\.)*)\\[)\]]""")

    /** @return the HTML for the Anki note's back field built from [card] (without the image). */
    fun ankiBack(card: GeneratedFlashcard): String {
        val header = subjectHeader(card)
        val answer = renderMathAware(card.back).replace("\n", "<br>")
        if (card.sections.isEmpty()) return header + answer
        val sections =
            card.sections.joinToString(separator = "") { section ->
                val isCode = section.label.substringBefore(" (") in CODE_LABELS
                val hasMath = mathSpan.containsMatchIn(section.content)
                if (isCode && !hasMath) {
                    "<div><b>${htmlEscape(section.label)}</b><pre>${htmlEscape(section.content)}</pre></div>"
                } else {
                    "<div><b>${htmlEscape(section.label)}</b><br>${renderMathAware(section.content).replace("\n", "<br>")}</div>"
                }
            }
        return header + answer + sections
    }

    /**
     * HTML-escapes [text] for the card, leaving math spans (`\(...\)`, `\[...\]`) untouched
     * so MathJax receives the TeX exactly as the model wrote it. Text between/around the
     * spans is escaped normally.
     */
    private fun renderMathAware(text: String): String {
        val bolded = boldToHtml(htmlEscapeOutsideMath(text))
        return bolded
    }

    /** Escapes [text] except inside math spans, which are kept verbatim. */
    private fun htmlEscapeOutsideMath(text: String): String =
        buildString {
            var index = 0
            while (index < text.length) {
                val match = mathSpan.find(text, index)
                if (match == null) {
                    append(htmlEscape(text.substring(index)))
                    break
                }
                append(htmlEscape(text.substring(index, match.range.first)))
                append(match.value)
                index = match.range.last + 1
            }
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
