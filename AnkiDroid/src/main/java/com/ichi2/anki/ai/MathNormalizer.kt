// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ai

import timber.log.Timber

/**
 * Canonical internal representation of content: normal text, inline math, display/block
 * math or code. The renderer consumes these segments so the UI never has to guess what
 * is mathematics.
 */
sealed class Segment {
    /** Normal text (prose). */
    data class Text(
        val text: String,
    ) : Segment()

    /** Inline mathematics flowing with surrounding text; [tex] excludes delimiters. */
    data class InlineMath(
        val tex: String,
    ) : Segment()

    /** Display/block mathematics; [tex] excludes the delimiters. */
    data class DisplayMath(
        val tex: String,
    ) : Segment()
}

/**
 * Normalizes every common AI-emitted math format into the canonical MathJax delimiters
 * `\( ... \)` (inline) and `\[ ... \]` (display), and maps Unicode math inside math spans
 * to TeX, so the viewer's MathJax reliably typesets all of it.
 *
 * Formats understood (in math spans, never code):
 * - `$...$` and `$$...$$` (Markdown math; `$$` always display, single `$` inline)
 * - `\(...\)` and `\[...\]` (canonical, left as-is)
 * - Unicode math inside the spans (`x²` -> `x^2`, `√` -> `\sqrt`, `π` -> `\pi`, ...)
 *
 * Deliberately NOT converted (false-positive protection for educational prose):
 * - text that merely contains `$` (money), e.g. "costs $5"
 * - Unicode math outside math spans ("x² + 5x = 0" stays readable text)
 * - code content (passed through untouched via [normalize]`(isCode = true)`)
 */
object MathNormalizer {
    /** Unicode -> TeX replacements applied INSIDE math spans only. */
    private val unicodeToTex: Map<String, String> =
        buildMap {
            // Greek letters (lowercase then uppercase). The trailing space keeps
            // \alpha separate from following letters and is stripped by the
            // whitespace-collapsing pass in [texFrom].
            put("α", "\\alpha ")
            put("β", "\\beta ")
            put("γ", "\\gamma ")
            put("δ", "\\delta ")
            put("ε", "\\epsilon ")
            put("ζ", "\\zeta ")
            put("η", "\\eta ")
            put("θ", "\\theta ")
            put("ι", "\\iota ")
            put("κ", "\\kappa ")
            put("λ", "\\lambda ")
            put("μ", "\\mu ")
            put("ν", "\\nu ")
            put("ξ", "\\xi ")
            put("π", "\\pi ")
            put("ρ", "\\rho ")
            put("σ", "\\sigma ")
            put("τ", "\\tau ")
            put("υ", "\\upsilon ")
            put("φ", "\\phi ")
            put("χ", "\\chi ")
            put("ψ", "\\psi ")
            put("ω", "\\omega ")
            put("Γ", "\\Gamma ")
            put("Δ", "\\Delta ")
            put("Θ", "\\Theta ")
            put("Λ", "\\Lambda ")
            put("Ξ", "\\Xi ")
            put("Π", "\\Pi ")
            put("Σ", "\\sum ")
            put("Φ", "\\Phi ")
            put("Ψ", "\\Psi ")
            put("Ω", "\\Omega ")
            // Operators and relations
            put("×", "\\times ")
            put("÷", "\\div ")
            put("±", "\\pm ")
            put("∓", "\\mp ")
            put("≤", "\\leq ")
            put("≥", "\\geq ")
            put("≠", "\\neq ")
            put("≈", "\\approx ")
            put("≡", "\\equiv ")
            put("∝", "\\propto ")
            put("∞", "\\infty ")
            put("→", "\\to ")
            put("←", "\\leftarrow ")
            put("↔", "\\leftrightarrow ")
            put("⇒", "\\Rightarrow ")
            put("⇐", "\\Leftarrow ")
            put("⇔", "\\Leftrightarrow ")
            put("∈", "\\in ")
            put("∉", "\\notin ")
            put("⊂", "\\subset ")
            put("⊃", "\\supset ")
            put("⊆", "\\subseteq ")
            put("∪", "\\cup ")
            put("∩", "\\cap ")
            put("∅", "\\emptyset ")
            put("∀", "\\forall ")
            put("∃", "\\exists ")
            put("∑", "\\sum ")
            put("∏", "\\prod ")
            put("∫", "\\int ")
            put("∮", "\\oint ")
            put("√", "\\sqrt ")
            put("∂", "\\partial ")
            put("∇", "\\nabla ")
            put("ℏ", "\\hbar ")
            put("·", "\\cdot ")
            put("∘", "\\circ ")
            put("⊕", "\\oplus ")
            put("⊗", "\\otimes ")
            put("∠", "\\angle ")
            put("°", "^{\\circ}")
            put("′", "'")
            put("″", "''")
            // Fraction slash
            put("∕", "/")
            // Bold/italic letterlike symbols frequently used as real letters
            put("ℝ", "\\mathbb{R}")
            put("ℕ", "\\mathbb{N}")
            put("ℤ", "\\mathbb{Z}")
            put("ℚ", "\\mathbb{Q}")
            put("ℂ", "\\mathbb{C}")
        }

    /** Superscript/subscript Unicode digits and letters, mapped to ASCII equivalents. */
    private val superscripts: Map<Char, String> =
        mapOf(
            '⁰' to "0",
            '¹' to "1",
            '²' to "2",
            '³' to "3",
            '⁴' to "4",
            '⁵' to "5",
            '⁶' to "6",
            '⁷' to "7",
            '⁸' to "8",
            '⁹' to "9",
            '⁺' to "+",
            '⁻' to "-",
            'ⁿ' to "n",
            'ˣ' to "x",
        )
    private val subscripts: Map<Char, String> =
        mapOf(
            '₀' to "0",
            '₁' to "1",
            '₂' to "2",
            '₃' to "3",
            '₄' to "4",
            '₅' to "5",
            '₆' to "6",
            '₇' to "7",
            '₈' to "8",
            '₉' to "9",
            '₊' to "+",
            '₋' to "-",
            '₌' to "=",
            'ᵢ' to "i",
            'ⱼ' to "j",
            'ₙ' to "n",
            'ₖ' to "k",
            'ₘ' to "m",
        )

    /**
     * Rewrites [text]'s math to the canonical delimiters, mapping Unicode math to TeX.
     *
     * @param isCode returns code content verbatim (nothing is ever wrapped there).
     * @param allowBare when false, bare undelimited LaTeX lines are not wrapped — used for
     * literal code/diagram sections where `^` or `_` mean code, not math.
     */
    fun normalize(
        text: String,
        isCode: Boolean = false,
        allowBare: Boolean = true,
    ): String {
        if (isCode || text.isEmpty()) return text
        return try {
            val spans = findSpans(text)
            buildString {
                var index = 0
                for (span in spans) {
                    append(piece(text.substring(index, span.start), allowBare))
                    append(span.replacement)
                    index = span.end
                }
                append(piece(text.substring(index), allowBare))
            }
        } catch (e: Exception) {
            // malformed input must never crash the flashcard screen; degrade to raw text
            Timber.w(e, "math normalization failed; falling back to raw text")
            text
        }
    }

    private fun piece(
        text: String,
        allowBare: Boolean,
    ): String = if (allowBare) wrapBareMathLines(text) else text

    /**
     * Whether [text] contains mathematics in any recognized format.
     *
     * @param allowBareLines when false (literal code sections) only explicitly delimited
     * spans count, so `^` inside code never turns a `<pre>` block into a math paragraph.
     */
    fun containsMath(
        text: String,
        allowBareLines: Boolean = true,
    ): Boolean =
        findSpans(text).isNotEmpty() ||
            (allowBareLines && text.lineSequence().any { isBareMathLine(it) })

    /**
     * Splits [text] into canonical [Segment]s (text, inline math, display math), first
     * normalizing `$`/`$$` formats. Code content becomes a single [Segment.Text].
     */
    fun segments(
        text: String,
        isCode: Boolean = false,
        allowBare: Boolean = true,
    ): List<Segment> {
        if (isCode || text.isEmpty()) return listOf(Segment.Text(text))
        val normalized = normalize(text, isCode = false, allowBare = allowBare)
        val spans = findSpans(normalized)
        if (spans.isEmpty()) return listOf(Segment.Text(normalized))
        val out = mutableListOf<Segment>()
        var index = 0
        for (span in spans) {
            if (span.start > index) out += Segment.Text(normalized.substring(index, span.start))
            out += span.segment
            index = span.end
        }
        if (index < normalized.length) out += Segment.Text(normalized.substring(index))
        return out
    }

    // ------------------------------------------------------------------ bare LaTeX

    /** Math markers that appear in undelimited LaTeX: `x^`, `x_{`, `\command`. */
    private val bareMathIndicator = Regex("""\^[{_A-Za-z0-9(]|_[{_A-Za-z0-9(]|\\[A-Za-z]{2,}""")

    /**
     * Wraps every [isBareMathLine] line of the given text piece in canonical inline math,
     * leaving other lines untouched. Text between delimited spans never carries delimiters.
     */
    private fun wrapBareMathLines(piece: String): String =
        if (piece.isEmpty()) {
            piece
        } else {
            piece.split('\n').joinToString("\n") { line ->
                val trimmed = line.trim()
                if (isBareMathLine(trimmed)) "\\(" + trimmed + "\\)" else line
            }
        }

    /**
     * Whether [line] is undelimited LaTeX that MathJax would otherwise show raw:
     * - carries a math marker (`^`, `_` or `\command`), and
     * - contains no prose: at most one word of 4+ consecutive letters outside brace groups
     *   and outside command names (so `(\text{Solar Time})` or `\alpha` do not count).
     * Deliberately conservative: "where R is resistance in ohms" stays text.
     */
    internal fun isBareMathLine(line: String): Boolean {
        val t = line.trim()
        if (t.isEmpty() || t.length > 200) return false
        if (!bareMathIndicator.containsMatchIn(t)) return false
        var depth = 0
        var prose = 0
        var i = 0
        while (i < t.length) {
            val c = t[i]
            when {
                c == '\\' -> {
                    i++
                    while (i < t.length && t[i].isLetter()) i++
                }
                c == '{' -> {
                    depth++
                    i++
                }
                c == '}' -> {
                    depth = (depth - 1).coerceAtLeast(0)
                    i++
                }
                c.isLetter() && depth == 0 -> {
                    var j = i
                    while (j < t.length && t[j].isLetter()) j++
                    if (j - i >= 4) {
                        prose++
                        if (prose >= 2) return false
                    }
                    i = j
                }
                else -> i++
            }
        }
        return true
    }

    // ------------------------------------------------------------------ span scanning

    private const val INLINE_OPEN = "\\("
    private const val INLINE_CLOSE = "\\)"
    private const val DISPLAY_OPEN = "\\["
    private const val DISPLAY_CLOSE = "\\]"

    private fun inline(tex: String): String = INLINE_OPEN + tex + INLINE_CLOSE

    private fun display(tex: String): String = DISPLAY_OPEN + tex + DISPLAY_CLOSE

    /** A math span found in text: offsets ([start], [end] exclusive), the canonical
     *  replacement text and the [Segment] it represents. */
    private sealed interface Span {
        val start: Int
        val end: Int
        val replacement: String
        val segment: Segment

        /** An already-canonical span left byte-identical in the output. */
        class Verbatim(
            override val start: Int,
            override val end: Int,
            override val replacement: String,
            override val segment: Segment,
        ) : Span

        /** A span rewritten to canonical delimiters (and Unicode mapped to TeX). */
        class Rewritten(
            override val start: Int,
            override val end: Int,
            tex: String,
            display: Boolean,
        ) : Span {
            override val replacement: String = if (display) display(tex) else inline(tex)
            override val segment: Segment =
                if (display) Segment.DisplayMath(tex) else Segment.InlineMath(tex)
        }
    }

    /**
     * Scans [text] for math spans in priority order: `\(...\)`, `\[...\]` (canonical,
     * trusted; Unicode inside them is still mapped to TeX when needed), then `$$...$$`
     * (display), then `$...$` (inline). Returns non-overlapping spans left-to-right.
     *
     * Heuristics for `$` spans (to avoid false positives on money/prose):
     * - the opening `$` must not be followed by whitespace, and the closing one must not
     *   be preceded by whitespace (Kramdown-style rules),
     * - the span must not cross a line break (single-`$` inline only),
     * - an unpaired `$` is treated as text (money/typo) and scanning continues after it.
     */
    private fun findSpans(text: String): List<Span> {
        val spans = mutableListOf<Span>()
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith(DISPLAY_OPEN, i) -> {
                    val close = text.indexOf(DISPLAY_CLOSE, i + 2)
                    if (close > i + 1) {
                        val body = text.substring(i + 2, close)
                        spans +=
                            canonicalSpan(i, close + 2, body, display = true, original = text.substring(i, close + 2))
                        i = close + 2
                        continue
                    }
                }
                text.startsWith(INLINE_OPEN, i) -> {
                    val close = text.indexOf(INLINE_CLOSE, i + 2)
                    if (close > i + 1) {
                        val body = text.substring(i + 2, close)
                        spans +=
                            canonicalSpan(i, close + 2, body, display = false, original = text.substring(i, close + 2))
                        i = close + 2
                        continue
                    }
                }
                text.startsWith("$$", i) -> {
                    val close = text.indexOf("$$", i + 2)
                    if (close > i + 1) {
                        val body = text.substring(i + 2, close).trim()
                        if (body.isNotEmpty()) spans += Span.Rewritten(i, close + 2, body, display = true)
                        i = close + 2
                        continue
                    }
                }
                text[i] == '$' -> {
                    val close = findInlineDollarClose(text, i + 1)
                    if (close > i) {
                        spans += Span.Rewritten(i, close + 1, text.substring(i + 1, close), display = false)
                        i = close + 1
                        continue
                    }
                    // unpaired: treat as text (money or typo) and keep scanning after it
                }
            }
            i++
        }
        return spans
    }

    /**
     * Builds a span for an already-canonical `\(...\)`/`\[...\]` delimiter pair: when the
     * body needs no Unicode->TeX mapping it is kept verbatim (spacing preserved exactly);
     * otherwise only the mapped body is rewritten.
     */
    private fun canonicalSpan(
        start: Int,
        end: Int,
        body: String,
        display: Boolean,
        original: String,
    ): Span {
        val mapped = texFrom(body)
        return if (mapped == null) {
            Span.Verbatim(start, end, original, if (display) Segment.DisplayMath(body) else Segment.InlineMath(body))
        } else {
            Span.Rewritten(start, end, mapped, display)
        }
    }

    /**
     * Finds the closing `$` for an inline `$...$` span starting right after the opening
     * one at [from]. Returns -1 when the `$` does not open plausible math:
     * - opening followed by whitespace, or closing preceded by whitespace,
     * - a `$$` (belongs to display scanning), or a line break inside the span,
     * - digits-only spans like "$5" (money) unless the content contains a math character.
     */
    private fun findInlineDollarClose(
        text: String,
        from: Int,
    ): Int {
        if (from >= text.length) return -1
        val next = text[from]
        if (next.isWhitespace() || next == '$') return -1
        var j = from
        while (j < text.length) {
            val c = text[j]
            when {
                c == '\n' -> return -1 // inline math never crosses lines
                c == '$' -> {
                    if (text[j - 1].isWhitespace()) return -1
                    return if (j > from) j else -1
                }
            }
            j++
        }
        return -1
    }

    /** Maps Unicode math inside a span body to TeX, or null when nothing changed. */
    private fun texFrom(raw: String): String? {
        var s = raw
        // Unicode -> TeX commands; the map values' trailing space keeps \alpha separate
        // from a following letter and is trimmed below when not needed
        for ((u, t) in unicodeToTex) s = s.replace(u, t)
        // superscripts / subscripts: runs of superscript chars become ^{...}
        s = convertScripts(s, superscripts, '^')
        s = convertScripts(s, subscripts, '_')
        if (s == raw) return null
        // trim the padding space unless the next char is a letter ( \alpha b vs \alpha2 )
        s = Regex("""\\([a-zA-Z]+) (?=[^a-zA-Z]|$)""").replace(s) { m -> m.value.trimEnd() }
        // \sqrt needs an argument: convert sqrt followed by a parenthesized expression
        s = Regex("""\\sqrt\s*\(([^()]*)\)""").replace(s) { m -> "\\sqrt" + '{' + m.groupValues[1] + '}' }
        // single-char scripts collapse to the bare form (x^2, not x^{2})
        s = Regex("""\^\{([^{}])\}""").replace(s) { "^" + it.groupValues[1] }
        s = Regex("""_\{([^{}])\}""").replace(s) { "_" + it.groupValues[1] }
        return s.trim()
    }

    /** Converts runs of Unicode super/subscript characters to `^{...}` / `_{...}`. */
    private fun convertScripts(
        s: String,
        map: Map<Char, String>,
        marker: Char,
    ): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c in map) {
                val run = StringBuilder()
                while (i < s.length && s[i] in map) {
                    run.append(map[s[i]])
                    i++
                }
                // a script binds directly to the preceding command: drop its padding space
                if (marker == '_' && out.isNotEmpty() && out[out.length - 1] == ' ') {
                    val cmdStart = out.lastIndexOf('\\')
                    if (cmdStart >= 0 && out.substring(cmdStart + 1).all { it.isLetter() }) out.setLength(out.length - 1)
                }
                out
                    .append(marker)
                    .append('{')
                    .append(run)
                    .append('}')
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}
