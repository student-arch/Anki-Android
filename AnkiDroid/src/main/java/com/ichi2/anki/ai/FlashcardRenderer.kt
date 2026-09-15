// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ai

/**
 * Renders [GeneratedFlashcard]s for display: the Anki note's front and back fields
 * (HTML) and the plain-text preview shown in the review list.
 *
 * Content flows through [MathNormalizer.segments] first, so each piece is known to be
 * text, inline math, display math or code — the renderer never guesses:
 * * [Segment.Text] is HTML-escaped (with `**bold**` markdown converted),
 * * [Segment.InlineMath]/[Segment.DisplayMath] is emitted verbatim with its canonical
 *   `\( ... \)`/`\[ ... \]` delimiters so the reviewer's bundled MathJax (mhchem,
 *   braket, physics and all TeX compiled in) typesets it — escaping inside delimiters
 *   would break the TeX (an `&` in a matrix, `<` in prose),
 * * code sections are rendered in `<pre>` (MathJax skips pre/code content, so any
 *   section that contains math renders in normal flow instead).
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
     * Labels whose content is literal code or ASCII art: `^`, `_` and `\` there are part of
     * the program/diagram, never undelimited math. Formula-style sections are NOT in this
     * set — bare LaTeX in them is math that MathJax must typeset (never inside `<pre>`).
     */
    private val LITERAL_CODE_LABELS = setOf("Syntax", "Code", "Output", "Diagram")

    /** @return the HTML for the Anki note's FRONT field built from [card]. */
    fun ankiFront(card: GeneratedFlashcard): String = renderProse(card.front)

    /** @return the HTML for the Anki note's back field built from [card] (without the image). */
    fun ankiBack(card: GeneratedFlashcard): String {
        val header = subjectHeader(card)
        val answer = renderProse(card.back)
        if (card.sections.isEmpty()) return header + answer
        val sections =
            card.sections.joinToString(separator = "") { section ->
                val label = section.label.substringBefore(" (")
                val isCode = label in CODE_LABELS
                val literalCode = label in LITERAL_CODE_LABELS
                val hasMath = MathNormalizer.containsMath(section.content, allowBareLines = !literalCode)
                if (isCode && !hasMath) {
                    "<div><b>${htmlEscape(section.label)}</b><pre>${htmlEscape(section.content)}</pre></div>"
                } else {
                    "<div><b>${htmlEscape(section.label)}</b><br>${renderProse(section.content, allowBare = !literalCode)}</div>"
                }
            }
        return header + answer + sections
    }

    /**
     * Renders prose that may contain math in any AI-emitted format: the text is
     * normalized (dollar/Unicode/bare-LaTeX formats -> canonical delimiters), split into
     * segments, each [Segment.Text] HTML-escaped (with `**bold**` -> `<b>`) and each math
     * segment emitted verbatim. Newlines in the text parts become `<br>`.
     */
    private fun renderProse(
        text: String,
        allowBare: Boolean = true,
    ): String =
        MathNormalizer
            .segments(text, allowBare = allowBare)
            .joinToString(separator = "") { segment ->
                when (segment) {
                    is Segment.Text -> boldToHtml(htmlEscape(segment.text)).replace("\n", "<br>")
                    is Segment.InlineMath -> inlineHtml(segment)
                    is Segment.DisplayMath -> displayHtml(segment)
                }
            }

    /**
     * Display math keeps its `\[ ... \]` delimiters (MathJax typesets those as a proper
     * centered block with display style), wrapped in a spacing div so it never shares
     * a line with surrounding prose.
     */
    private fun displayHtml(segment: Segment.DisplayMath): String =
        "<div style=\"text-align:center;margin:0.4em 0;\">" + DISPLAY_OPEN + segment.tex + DISPLAY_CLOSE + "</div>"

    private fun inlineHtml(segment: Segment.InlineMath): String = INLINE_OPEN + segment.tex + INLINE_CLOSE

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
        parts += stripBold(MathNormalizer.normalize(card.back))
        card.sections.forEach {
            val literal = isLiteralCodeSection(it.label)
            parts +=
                "${it.label}\n" +
                stripBold(
                    MathNormalizer.normalize(it.content, isCode = literal, allowBare = !literal),
                )
        }
        card.imageUrl?.let { parts += "Image\n$it" }
        if (card.image.required) parts += "Image\n${card.image.alt ?: card.image.prompt ?: "(generated illustration)"}"
        return parts.joinToString(separator = "\n\n")
    }

    private fun isLiteralCodeSection(label: String): Boolean = label.substringBefore(" (") in LITERAL_CODE_LABELS

    private fun htmlEscape(text: String): String =
        text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    private const val INLINE_OPEN = "\\("

    private const val INLINE_CLOSE = "\\)"

    private const val DISPLAY_OPEN = "\\["

    private const val DISPLAY_CLOSE = "\\]"
}
