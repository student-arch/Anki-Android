// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ai

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.junit.Test

/**
 * Tests for [MathNormalizer]: every common AI-emitted math format is normalized to the
 * canonical MathJax delimiters, and ordinary text is never misclassified as math.
 */
class MathNormalizerTest {
    // ---------------------------------------------------------------- $ and $$ delimiters

    @Test
    fun `single dollar inline math is normalized`() {
        assertThat(MathNormalizer.normalize("Solve ${'$'}x^2 + 5x + 6 = 0${'$'} first."), equalTo("""Solve \(x^2 + 5x + 6 = 0\) first."""))
    }

    @Test
    fun `double dollar display math is normalized`() {
        assertThat(MathNormalizer.normalize("${'$'}${'$'}x^2 + 5x + 6 = 0${'$'}${'$'}"), equalTo("""\[x^2 + 5x + 6 = 0\]"""))
    }

    @Test
    fun `dollar math across lines stays display`() {
        assertThat(
            MathNormalizer.normalize(
                "${'$'}${'$'}\nx = \\frac{-b \\pm \\sqrt{b^2 - 4ac}}{2a}\n${'$'}${'$'}",
            ),
            equalTo("""\[x = \frac{-b \pm \sqrt{b^2 - 4ac}}{2a}\]"""),
        )
    }

    @Test
    fun `paren delimiters are canonical already`() {
        assertThat(MathNormalizer.normalize("""a \( x^2 \) b"""), equalTo("""a \( x^2 \) b"""))
    }

    @Test
    fun `bracket delimiters are canonical already`() {
        assertThat(MathNormalizer.normalize("""a \[ x^2 \] b"""), equalTo("""a \[ x^2 \] b"""))
    }

    // ---------------------------------------------------------------- mixed content

    @Test
    fun `mixed inline and display math in one card`() {
        val input = """To solve \(ax^2 + bx + c = 0\), use:"""
        assertThat(MathNormalizer.normalize(input), equalTo(input))
    }

    @Test
    fun `multiple equations on one line`() {
        assertThat(
            MathNormalizer.normalize("If ${'$'}a=1${'$'} and ${'$'}b=2${'$'}, then ${'$'}c=3${'$'}."),
            equalTo("""If \(a=1\) and \(b=2\), then \(c=3\)."""),
        )
    }

    @Test
    fun `display math between text lines`() {
        val input = "The formula:\n\\[ E = mc^2 \\]\nEnergy follows."
        assertThat(MathNormalizer.normalize(input), equalTo(input))
    }

    @Test
    fun `math in the middle of unicode prose`() {
        assertThat(
            MathNormalizer.normalize("Area = ${'$'}\\pi r^2${'$'} for a circle."),
            equalTo("""Area = \(\pi r^2\) for a circle."""),
        )
    }

    // ---------------------------------------------------------------- no false positives

    @Test
    fun `ordinary text with money dollars is not math`() {
        val text = "The book costs ${'$'}5 and the pen costs ${'$'}10 today."
        assertThat(MathNormalizer.normalize(text), equalTo(text))
    }

    @Test
    fun `single isolated dollar stays text`() {
        assertThat(MathNormalizer.normalize("100${'$'} down"), equalTo("100${'$'} down"))
    }

    @Test
    fun `variable x in prose is not math`() {
        val text = "The variable x is multiplied by 2."
        assertThat(MathNormalizer.normalize(text), equalTo(text))
    }

    @Test
    fun `code content is left untouched`() {
        val code = "price = 5 + ${'$'}var${'$'} if x == 1"
        assertThat(MathNormalizer.normalize(code, isCode = true), equalTo(code))
    }

    // ---------------------------------------------------------------- unicode math

    @Test
    fun `unicode superscripts inside math become tex`() {
        assertThat(
            MathNormalizer.normalize("""\(x² + 5x + 6 = 0\)"""),
            equalTo("""\(x^2 + 5x + 6 = 0\)"""),
        )
    }

    @Test
    fun `unicode fraction slash inside math becomes frac`() {
        assertThat(MathNormalizer.normalize("""\(a∕b\)"""), equalTo("""\(a/b\)"""))
    }

    @Test
    fun `unicode symbols inside math become tex commands`() {
        assertThat(MathNormalizer.normalize("""\(A = π r²\)"""), equalTo("""\(A = \pi r^2\)"""))
    }

    @Test
    fun `unicode square root becomes sqrt`() {
        assertThat(MathNormalizer.normalize("""\(x = √(b²-4ac)\)"""), equalTo("""\(x = \sqrt{b^2-4ac}\)"""))
    }

    @Test
    fun `unicode greek letters become tex commands`() {
        assertThat(MathNormalizer.normalize("""\(α + β = γ\)"""), equalTo("""\(\alpha + \beta = \gamma\)"""))
    }

    @Test
    fun `unicode multiplication and division`() {
        assertThat(MathNormalizer.normalize("""\(F = m × a\)"""), equalTo("""\(F = m \times a\)"""))
        assertThat(MathNormalizer.normalize("""\(E ÷ 2\)"""), equalTo("""\(E \div 2\)"""))
    }

    @Test
    fun `unicode integrals and sums`() {
        assertThat(MathNormalizer.normalize("""\(∫₀^∞ e⁻ˣ dx = 1\)"""), equalTo("""\(\int_0^\infty e^{-x} dx = 1\)"""))
        assertThat(MathNormalizer.normalize("""\(Σᵢ₌₁ⁿ i = n(n+1)/2\)"""), equalTo("""\(\sum_{i=1}^n i = n(n+1)/2\)"""))
    }

    @Test
    fun `unicode plain characters in prose are untouched`() {
        assertThat(MathNormalizer.normalize("Café menu: 2 × 3 = 6"), equalTo("Café menu: 2 × 3 = 6"))
    }

    @Test
    fun `unicode math outside math spans stays visible`() {
        // Unicode math without any delimiters is NOT wrapped in math spans (ambiguity
        // risk); it remains readable text.
        assertThat(MathNormalizer.normalize("x² + 5x + 6 = 0"), equalTo("x² + 5x + 6 = 0"))
    }

    // ---------------------------------------------------------------- malformed input

    @Test
    fun `unclosed dollar falls back to text`() {
        assertThat(MathNormalizer.normalize("costs ${'$'}${'$'}5 today"), equalTo("costs ${'$'}${'$'}5 today"))
    }

    @Test
    fun `unbalanced closing dollars fall back to text`() {
        val text = "It costs 5${'$'} and 6${'$'}"
        assertThat(MathNormalizer.normalize(text), equalTo(text))
    }

    @Test
    fun `empty math span is dropped to text`() {
        assertThat(MathNormalizer.normalize("An empty ${'$'}${'$'} math"), equalTo("An empty ${'$'}${'$'} math"))
    }

    @Test
    fun `normalize never throws on any input`() {
        val inputs =
            listOf(
                "",
                "$",
                "$$",
                "$$$",
                "$$$$",
                "\\(",
                "\\[",
                "\\(\\)",
                "text \\( broken \\]",
                "a $$ b $ c $$ d",
                "\\\\",
                "\\ce{",
                "unclosed ${'$'}x^2",
                "${'$'}x^2${'$'} ${'$'} ${'$'}y${'$'} ${'$'}${'$'}z${'$'}${'$'} ${'$'}${'$'}${'$'}",
                "\\(\\(nested \\)\\)",
                "${'$'}" + "very long ".repeat(2000) + "${'$'}",
            )
        inputs.forEach { input ->
            // never throws; result may be text or math but always present
            MathNormalizer.normalize(input)
            MathNormalizer.segments(input)
            MathNormalizer.containsMath(input)
        }
    }

    @Test
    fun `very long equation normalizes without stack overflow`() {
        val long = "x = " + "1+".repeat(500) + "1"
        val normalized = MathNormalizer.normalize("${'$'}${'$'}$long${'$'}${'$'}")
        assertThat(normalized, containsString("""\[x = 1+1"""))
    }

    @Test
    fun `deeply nested braces stay intact`() {
        val tex = """\frac{\sqrt{\frac{a}{b}}}{\sqrt[3]{c^{2}}"""
        assertThat(MathNormalizer.normalize("""\($tex\)"""), equalTo("""\($tex\)"""))
    }

    @Test
    fun `dollar math inside canonical math is not double processed`() {
        // \( ... $ ... \) — the canonical span wins; the $ is literal inside it
        assertThat(MathNormalizer.normalize("""\(\text{costs $5}\)"""), equalTo("""\(\text{costs $5}\)"""))
    }

    // ---------------------------------------------------------------- bare LaTeX (no delimiters)

    @Test
    fun `bare latex line is wrapped into canonical inline math`() {
        val line = "h = 15^\\circ \\times (\\text{Solar Time} - 12\\text{h})"
        assertThat(MathNormalizer.normalize(line), equalTo("\\(" + line + "\\)"))
    }

    @Test
    fun `bare greek command line is wrapped`() {
        assertThat(MathNormalizer.normalize("\\tau = RC"), equalTo("""\(\tau = RC\)"""))
    }

    @Test
    fun `plain equation without commands is not wrapped`() {
        val text = "V = IR"
        assertThat(MathNormalizer.normalize(text), equalTo(text))
    }

    @Test
    fun `prose containing a command is not wrapped`() {
        val text = "Use the \\frac{a}{b} rule for fractions here"
        assertThat(MathNormalizer.normalize(text), equalTo(text))
    }

    @Test
    fun `only the bare math lines of a multi-line section are wrapped`() {
        val content = "Some explanation text here\nh = 15^\\circ \\times t"
        val normalized = MathNormalizer.normalize(content)
        assertThat(normalized, equalTo("Some explanation text here\n\\(h = 15^\\circ \\times t\\)"))
    }

    @Test
    fun `normalize is idempotent for bare math`() {
        val line = "\\tau = RC"
        val once = MathNormalizer.normalize(line)
        assertThat(MathNormalizer.normalize(once), equalTo(once))
    }

    @Test
    fun `bare math never appears in code`() {
        val code = "result = x^2 + \\alpha"
        assertThat(MathNormalizer.normalize(code, isCode = true), equalTo(code))
        assertThat(MathNormalizer.containsMath("x = y^2", allowBareLines = false), equalTo(false))
        assertThat(MathNormalizer.containsMath("x = y^2"), equalTo(true))
    }

    // ---------------------------------------------------------------- containsMath

    @Test
    fun `containsMath detects all formats`() {
        assertThat(MathNormalizer.containsMath("""\( x \)"""), equalTo(true))
        assertThat(MathNormalizer.containsMath("""\[ x \]"""), equalTo(true))
        assertThat(MathNormalizer.containsMath("${'$'}x${'$'}"), equalTo(true))
        assertThat(MathNormalizer.containsMath("${'$'}${'$'}x${'$'}${'$'}"), equalTo(true))
        assertThat(MathNormalizer.containsMath("plain text"), equalTo(false))
        assertThat(MathNormalizer.containsMath("costs ${'$'}5"), equalTo(false))
    }

    // ---------------------------------------------------------------- segments

    @Test
    fun `segments split text inline and display math`() {
        val segments = MathNormalizer.segments("""Text \(a+b\) more \[x=y\] end""")
        assertThat(
            segments,
            equalTo(
                listOf(
                    Segment.Text("Text "),
                    Segment.InlineMath("a+b"),
                    Segment.Text(" more "),
                    Segment.DisplayMath("x=y"),
                    Segment.Text(" end"),
                ),
            ),
        )
    }

    @Test
    fun `segments of plain text is one text segment`() {
        assertThat(MathNormalizer.segments("hello"), equalTo(listOf(Segment.Text("hello"))))
    }

    @Test
    fun `segments normalizes dollar formats first`() {
        val segments = MathNormalizer.segments("Solve ${'$'}x^2${'$'} now")
        assertThat(
            segments,
            equalTo(
                listOf(Segment.Text("Solve "), Segment.InlineMath("x^2"), Segment.Text(" now")),
            ),
        )
    }
}
