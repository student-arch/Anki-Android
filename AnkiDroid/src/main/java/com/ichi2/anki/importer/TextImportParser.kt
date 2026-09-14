// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.importer

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * A single flashcard parsed from pasted text: a front and a back, both non-blank.
 */
@Parcelize
data class ParsedTextCard(
    val front: String,
    val back: String,
) : Parcelable

/**
 * Parses pasted or imported plain text into flashcards, preserving formulas and Unicode
 * characters exactly as typed (no escaping or reformatting of the content).
 *
 * Supported structures, one card per line or per blank-line-separated block:
 * * a separator between the front and the back: a tab, a semicolon, or `" - "` —
 *   `What is 2+2?; 4`
 * * `Question:`/`Answer:` headers (also `Q:`, `Front:`/`Back:`), one pair per block:
 *   `Question: What is x?\nAnswer: y`
 * * the same headers with the answer in the next block, separated by a blank line:
 *   `Front: What is the Schrödinger equation?\n\nBack: iℏ ∂|ψ⟩/∂t = Ĥ|ψ⟩`
 *
 * Lines that fit neither structure are skipped, so prose or notes around the cards
 * never becomes a broken card.
 */
object TextImportParser {
    private val cardSeparator = Regex("""\t|;|\s-\s""")
    private val frontLabel = Regex("""^\s*(?:Question|Q|Front)\s*[:.]?\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val backLabel = Regex("""^\s*(?:Answer|A|Back)\s*[:.]?\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val labelOnly = Regex("""^\s*(?:Question|Answer|Front|Back|Q|A)\s*:\s*$""", RegexOption.IGNORE_CASE)

    /**
     * @return the cards found in [text], never null. Lines that contain no separator
     * (and are not part of a Question/Answer structure) are skipped.
     */
    fun parse(text: String): List<ParsedTextCard> {
        val blocks = text.replace("\r\n", "\n").split(Regex("""\n\s*\n"""))
        val cards = mutableListOf<ParsedTextCard>()
        var i = 0
        while (i < blocks.size) {
            val parsed = parseBlock(blocks[i])
            if (parsed != null) {
                cards.addAll(parsed)
                i++
                continue
            }
            // a lone labeled front ("Front: ...") may continue into the next block:
            // "Front: A\n\nBack: B" is one card even though the labels are separated
            val continuation = pairFrontWithNextBlock(blocks, i)
            if (continuation != null) {
                cards.add(continuation.card)
                i = continuation.nextIndex
            } else {
                i++
            }
        }
        return cards
    }

    /** The result of pairing a lone labeled front with the next block: the card and where to resume. */
    private data class LabelContinuation(
        val card: ParsedTextCard,
        val nextIndex: Int,
    )

    /**
     * When [blocks][i] ends with a labeled front without a back, and the next block starts
     * with the matching back label, joins them into one card.
     * @return the joined card and the index of the first unprocessed block, or null when
     * the block is not such a continuation.
     */
    private fun pairFrontWithNextBlock(
        blocks: List<String>,
        i: Int,
    ): LabelContinuation? {
        if (i + 1 >= blocks.size) return null
        val blockLines = blocks[i].lines().filter { it.isNotBlank() }
        if (blockLines.size != 1) return null
        val frontMatch = frontLabel.matchEntire(blockLines[0]) ?: return null
        val nextLines = blocks[i + 1].lines().filter { it.isNotBlank() }
        if (nextLines.isEmpty()) return null
        val backMatch = backLabel.matchEntire(nextLines[0]) ?: return null
        val front = frontMatch.groupValues[1].trim()
        val back =
            nextLines
                .drop(1)
                .joinToString("\n")
                .trim()
                .ifEmpty { backMatch.groupValues[1].trim() }
        if (front.isEmpty() || back.isEmpty()) return null
        return LabelContinuation(ParsedTextCard(front = front, back = back), i + 2)
    }

    /**
     * @return the cards found in one blank-line-separated [block], or null when the block
     * is a lone labeled front that may continue into the next block.
     */
    private fun parseBlock(block: String): List<ParsedTextCard>? {
        val lines = block.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()

        // label-structured: "Question: ..." / "Answer: ..." (one pair per block)
        val front =
            lines.firstNotNullOfOrNull { line ->
                frontLabel
                    .matchEntire(line)
                    ?.groupValues
                    ?.get(1)
                    ?.takeIf(String::isNotBlank)
            }
        val back =
            lines.firstNotNullOfOrNull { line ->
                backLabel
                    .matchEntire(line)
                    ?.groupValues
                    ?.get(1)
                    ?.takeIf(String::isNotBlank)
            }
        if (front != null && back != null) return listOf(ParsedTextCard(front = front, back = back))

        // a lone labeled front may continue into the next block ("Front: A\n\nBack: B"):
        // signal this to the caller instead of falling through to separator parsing
        if (front != null && back == null && lines.all { frontLabel.matches(it) || it.isBlank() }) return null

        // separator-structured: one card per line that contains a separator
        return lines.mapNotNull(::parseLine)
    }

    /** @return the card in [line], or null when [line] contains no separator. */
    private fun parseLine(line: String): ParsedTextCard? {
        val match = cardSeparator.find(line) ?: return null
        val front = line.substring(0, match.range.first).trim()
        val back = line.substring(match.range.last + 1).trim()
        if (front.isEmpty() || back.isEmpty()) return null
        // a lone "Question:"/"Answer:" label line is not a card front/back on its own
        if (labelOnly.matches(front) || labelOnly.matches(back)) return null
        return ParsedTextCard(front = front, back = back)
    }
}
