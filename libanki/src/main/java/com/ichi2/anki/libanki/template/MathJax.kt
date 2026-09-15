/*
 *  Copyright (c) 2020 Arthur Milchior <arthur@milchior.fr>
 *
 *  This program is free software; you can redistribute it and/or modify it under
 *  the terms of the GNU General Public License as published by the Free Software
 *  Foundation; either version 3 of the License, or (at your option) any later
 *  version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY
 *  WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 *  PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with
 *  this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.ichi2.anki.libanki.template

object MathJax {
    // MathJax opening delimiters
    private val sMathJaxOpenings = arrayOf("\\(", "\\[")

    // MathJax closing delimiters
    private val sMathJaxClosings = arrayOf("\\)", "\\]")

    fun textContainsMathjax(txt: String): Boolean {
        // Do you have the first opening and then the first closing,
        // or the second opening and the second closing...?

        // This assumes that the openings and closings are the same length.
        var opening: String
        var closing: String
        for (i in sMathJaxOpenings.indices) {
            opening = sMathJaxOpenings[i]
            closing = sMathJaxClosings[i]

            // What if there are more than one thing?
            // Let's look for the first opening, and the last closing, and if they're in the right order,
            // we are good.
            val firstOpeningIndex = txt.indexOf(opening)
            val lastClosingIndex = txt.lastIndexOf(closing)
            if (firstOpeningIndex != -1 && lastClosingIndex != -1 && firstOpeningIndex < lastClosingIndex) {
                return true
            }
        }

        // Markdown-style $ / $$ math: a closing delimiter must exist after an opening
        // one (with the opening not followed and the closing not preceded by whitespace,
        // so money like "$5 and $10" does not trigger a MathJax load).
        return containsDollarMath(txt)
    }

    /**
     * @return true when [txt] contains a plausible `$...$` (inline) or `$$...$$`
     * (display) math span. Conservative by design: only a well-formed pair counts.
     */
    private fun containsDollarMath(txt: String): Boolean {
        var i = 0
        while (i < txt.length) {
            when {
                txt.startsWith("$$", i) -> {
                    val close = txt.indexOf("$$", i + 2)
                    if (close > i + 1) return true
                    i += 2
                }
                txt[i] == '$' -> {
                    val next = txt.getOrNull(i + 1)
                    if (next == null || next.isWhitespace() || next == '$') {
                        i++
                        continue
                    }
                    // find a closing $ on the same line, not preceded by whitespace
                    var j = i + 1
                    var found = false
                    while (j < txt.length && txt[j] != '\n') {
                        if (txt[j] == '$' && !txt[j - 1].isWhitespace()) {
                            found = true
                            break
                        }
                        j++
                    }
                    if (found) return true
                    i++
                }
                else -> i++
            }
        }
        return false
    }
}
