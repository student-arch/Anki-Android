// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ai

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.not
import org.junit.Test

/** Tests for rendering generated cards into Anki HTML and review previews. */
class FlashcardRendererTest {
    @Test
    fun `card without sections renders plain back`() {
        val card = GeneratedFlashcard(front = "Q", back = "A")
        assertThat(FlashcardRenderer.ankiBack(card), equalTo("A"))
        assertThat(FlashcardRenderer.reviewText(card), equalTo("A"))
    }

    @Test
    fun `prose sections render as labeled divs`() {
        val card =
            GeneratedFlashcard(
                front = "Q",
                back = "A",
                sections =
                    listOf(
                        CardSection("Explanation", "line one\nline two"),
                        CardSection("Difficulty note", "hard"),
                    ),
            )
        val html = FlashcardRenderer.ankiBack(card)
        assertThat(html, containsString("A"))
        assertThat(html, containsString("<b>Explanation</b><br>line one<br>line two"))
        assertThat(html, containsString("<div><b>Difficulty note</b>"))
    }

    @Test
    fun `code sections render in pre`() {
        val card =
            GeneratedFlashcard(
                front = "Q",
                back = "A",
                sections = listOf(CardSection("Code (python)", "def f():\n    return 1")),
            )
        assertThat(FlashcardRenderer.ankiBack(card), containsString("<b>Code (python)</b><pre>def f():\n    return 1</pre>"))
    }

    @Test
    fun `html in section content is escaped`() {
        val card =
            GeneratedFlashcard(
                front = "Q",
                back = "A",
                sections = listOf(CardSection("Output", "<hello> & \"world\"")),
            )
        val html = FlashcardRenderer.ankiBack(card)
        assertThat(html, containsString("&lt;hello&gt; &amp; &quot;world&quot;"))
    }

    @Test
    fun `review text lists sections as label plus content`() {
        val card =
            GeneratedFlashcard(
                front = "Q",
                back = "A",
                sections = listOf(CardSection("Key Points", "short"), CardSection("Source", "book")),
            )
        assertThat(FlashcardRenderer.reviewText(card), equalTo("A\n\nKey Points\nshort\n\nSource\nbook"))
    }

    @Test
    fun `review text lists image url last`() {
        val card =
            GeneratedFlashcard(
                front = "Q",
                back = "A",
                imageUrl = "https://example.com/cpu.png",
            )
        assertThat(FlashcardRenderer.reviewText(card), equalTo("A\n\nImage\nhttps://example.com/cpu.png"))
    }

    @Test
    fun `subject and topic render as a header line`() {
        val card =
            GeneratedFlashcard(
                front = "Q",
                back = "A",
                subject = "Electrical Engineering",
                topic = "Ohm's Law",
            )
        assertThat(FlashcardRenderer.ankiBack(card), containsString("Electrical Engineering \u00b7 Ohm's Law"))
        assertThat(FlashcardRenderer.reviewText(card), equalTo("Electrical Engineering \u00b7 Ohm's Law\n\nA"))
    }

    @Test
    fun `formula and units render in pre`() {
        val card =
            GeneratedFlashcard(
                front = "Q",
                back = "A",
                sections = listOf(CardSection("Formula", "V = IR"), CardSection("Units", "R = Ohm")),
            )
        val html = FlashcardRenderer.ankiBack(card)
        assertThat(html, containsString("<b>Formula</b><pre>V = IR</pre>"))
        assertThat(html, containsString("<b>Units</b><pre>R = Ohm</pre>"))
    }

    @Test
    fun `mathjax delimiters in the answer are preserved unescaped`() {
        val card =
            GeneratedFlashcard(
                front = "Q",
                back = """The roots are \( x = \frac{-b \pm \sqrt{b^2-4ac}}{2a} \) exactly.""",
            )
        val html = FlashcardRenderer.ankiBack(card)
        assertThat(html, containsString("""\( x = \frac{-b \pm \sqrt{b^2-4ac}}{2a} \)"""))
    }

    @Test
    fun `ampersand inside mathjax is not html escaped`() {
        val card =
            GeneratedFlashcard(
                front = "Q",
                back = """\( \begin{pmatrix} a & b \\ c & d \end{pmatrix} \)""",
            )
        val html = FlashcardRenderer.ankiBack(card)
        assertThat(html, containsString("""\( \begin{pmatrix} a & b \\ c & d \end{pmatrix} \)"""))
    }

    @Test
    fun `display math in a formula section is not wrapped in pre`() {
        val card =
            GeneratedFlashcard(
                front = "Q",
                back = "A",
                sections = listOf(CardSection("Formula", """\( E = mc^2 \)""")),
            )
        val html = FlashcardRenderer.ankiBack(card)
        // MathJax skips <pre> content entirely, so formulas must render in normal flow
        assertThat(html, containsString("""<b>Formula</b><br>\( E = mc^2 \)"""))
    }

    @Test
    fun `plain formula sections without mathjax still render in pre`() {
        val card =
            GeneratedFlashcard(
                front = "Q",
                back = "A",
                sections = listOf(CardSection("Given", "V = 12 V\nR = 4 Ohm")),
            )
        val html = FlashcardRenderer.ankiBack(card)
        assertThat(html, containsString("<b>Given</b><pre>V = 12 V\nR = 4 Ohm</pre>"))
    }

    @Test
    fun `text around math delimiters is still escaped`() {
        val card =
            GeneratedFlashcard(
                front = "Q",
                back = """a <tag> \( x \) & outside""",
            )
        val html = FlashcardRenderer.ankiBack(card)
        assertThat(html, containsString("&lt;tag&gt;"))
        assertThat(html, containsString("""&amp; outside"""))
        assertThat(html, containsString("""\( x \)"""))
    }

    @Test
    fun `chem and quantum notation in the answer survive rendering`() {
        val card =
            GeneratedFlashcard(
                front = "Q",
                back = """\( \ce{2H2 + O2 -> 2H2O} \) and \( \ket{\psi} = \alpha\ket{0} + \beta\ket{1} \)""",
            )
        val html = FlashcardRenderer.ankiBack(card)
        assertThat(html, containsString("""\( \ce{2H2 + O2 -> 2H2O} \)"""))
        assertThat(html, containsString("""\( \ket{\psi} = \alpha\ket{0} + \beta\ket{1} \)"""))
    }

    @Test
    fun `caption html uses the image caption`() {
        val card =
            GeneratedFlashcard(
                front = "Q",
                back = "A",
                image = CardImage(required = true, prompt = "p", caption = "Series circuit"),
            )
        assertThat(FlashcardRenderer.captionHtml(card), containsString("Series circuit"))
    }

    // ---------------------------------------------------------------- math formats

    @Test
    fun `dollar inline math in the back is normalized to canonical delimiters`() {
        val card = GeneratedFlashcard(front = "Q", back = "Solve ${'$'}x^2 + 5x + 6 = 0${'$'} for x.")
        val html = FlashcardRenderer.ankiBack(card)
        assertThat(html, containsString("""\(x^2 + 5x + 6 = 0\)"""))
        assertThat(html, not(containsString("$")))
    }

    @Test
    fun `dollar display math in the back is normalized`() {
        val card = GeneratedFlashcard(front = "Q", back = "Use:${'$'}${'$'}x = \\frac{-b}{2a}${'$'}${'$'}")
        val html = FlashcardRenderer.ankiBack(card)
        assertThat(html, containsString("""\[x = \frac{-b}{2a}\]"""))
    }

    @Test
    fun `unicode math inside math spans is mapped to tex`() {
        val card = GeneratedFlashcard(front = "Q", back = """Area is \(A = π r²\).""")
        val html = FlashcardRenderer.ankiBack(card)
        assertThat(html, containsString("""\(A = \pi r^2\)"""))
    }

    @Test
    fun `unicode chemistry subscripts in ce stay untouched`() {
        // mhchem handles its own subscripts: \ce{H2O} must reach MathJax verbatim
        val card = GeneratedFlashcard(front = "Q", back = """\( \ce{2H2 + O2 -> 2H2O} \)""")
        assertThat(FlashcardRenderer.ankiBack(card), containsString("""\( \ce{2H2 + O2 -> 2H2O} \)"""))
    }

    @Test
    fun `money dollars in the back stay plain text`() {
        val card = GeneratedFlashcard(front = "Q", back = "It costs ${'$'}5 or ${'$'}10.")
        val html = FlashcardRenderer.ankiBack(card)
        assertThat(html, containsString("${'$'}5 or ${'$'}10"))
        assertThat(html, not(containsString("""\(""")))
    }

    @Test
    fun `display math renders as a block not inline`() {
        val card = GeneratedFlashcard(front = "Q", back = """Answer:\[ E = mc^2 \]done""")
        val html = FlashcardRenderer.ankiBack(card)
        // the display span survives as-is (canonical), not rewrapped or merged
        assertThat(html, containsString("""\[ E = mc^2 \]"""))
    }

    @Test
    fun `math in a formula section never lands in pre`() {
        val card =
            GeneratedFlashcard(
                front = "Q",
                back = "A",
                sections = listOf(CardSection("Formula", """I = ${'$'}V/R${'$'}""")),
            )
        val html = FlashcardRenderer.ankiBack(card)
        assertThat(html, containsString("<b>Formula</b><br>"))
        assertThat(html, containsString("""\(V/R\)"""))
        assertThat(html, not(containsString("<pre>")))
    }

    @Test
    fun `plain formula sections without any math still render in pre`() {
        val card =
            GeneratedFlashcard(
                front = "Q",
                back = "A",
                sections = listOf(CardSection("Given", "V = 12 V\nR = 4 Ohm")),
            )
        assertThat(FlashcardRenderer.ankiBack(card), containsString("<b>Given</b><pre>V = 12 V\nR = 4 Ohm</pre>"))
    }

    @Test
    fun `math spanning brackets inside math is not split by the old regex`() {
        // the old single-regex matcher broke on \left[ ... \right]; segments cannot
        val card = GeneratedFlashcard(front = "Q", back = """\( \left[ 0, \infty \right) \)""")
        assertThat(FlashcardRenderer.ankiBack(card), containsString("""\( \left[ 0, \infty \right) \)"""))
    }

    @Test
    fun `front field also gets math normalized`() {
        val card = GeneratedFlashcard(front = "Solve ${'$'}x^2 = 4${'$'}", back = "A")
        assertThat(FlashcardRenderer.ankiFront(card), containsString("""\(x^2 = 4\)"""))
    }

    @Test
    fun `front field keeps money dollars untouched`() {
        val card = GeneratedFlashcard(front = "What costs ${'$'}5?", back = "A")
        val front = FlashcardRenderer.ankiFront(card)
        assertThat(front, containsString("${'$'}5"))
        assertThat(front, not(containsString("""\(""")))
    }

    @Test
    fun `front bold markdown becomes html bold`() {
        val card = GeneratedFlashcard(front = "Define **Ohm's Law**", back = "A")
        assertThat(FlashcardRenderer.ankiFront(card), containsString("<b>Ohm's Law</b>"))
    }

    @Test
    fun `front html-looking text is escaped`() {
        val card = GeneratedFlashcard(front = "a <b>bold</b> attempt", back = "A")
        assertThat(FlashcardRenderer.ankiFront(card), containsString("&lt;b&gt;"))
    }

    @Test
    fun `review text shows normalized math without dollar delimiters`() {
        val card = GeneratedFlashcard(front = "Q", back = "Solve ${'$'}x^2${'$'} now")
        val text = FlashcardRenderer.reviewText(card)
        assertThat(text, containsString("""\(x^2\)"""))
    }

    // ---------------------------------------------------------------- gray-box (pre) regression

    @Test
    fun `bare latex formula section renders as math not raw text in pre`() {
        // the reported bug: undelimited LaTeX landed in <pre>, which MathJax skips
        val line = "h = 15^\\circ \\times (\\text{Solar Time} - 12\\text{h})"
        val card =
            GeneratedFlashcard(
                front = "Q",
                back = "A",
                sections = listOf(CardSection("Formula", line)),
            )
        val html = FlashcardRenderer.ankiBack(card)
        assertThat(html, containsString("<b>Formula</b><br>\\(" + line + "\\)"))
        assertThat(html, not(containsString("<pre>")))
    }

    @Test
    fun `code section with caret stays pre`() {
        val card =
            GeneratedFlashcard(
                front = "Q",
                back = "A",
                sections = listOf(CardSection("Code (python)", "result = x^2 + 1")),
            )
        assertThat(FlashcardRenderer.ankiBack(card), containsString("<pre>result = x^2 + 1</pre>"))
    }

    @Test
    fun `schema-light definition card keeps formula rendering intact`() {
        // adaptive generation may emit cards with only question/answer/definition; the
        // formula pipeline must be untouched by that: inline math still canonical, no <pre>
        val card =
            GeneratedFlashcard(
                front = "Define the time constant \\( \\tau \\) of an RC circuit",
                back = "The time constant is \\( \\tau = RC \\): the time to reach ~63% of full charge.",
                sections = listOf(CardSection("Definition", "Product of resistance and capacitance.")),
            )
        val html = FlashcardRenderer.ankiFront(card) + FlashcardRenderer.ankiBack(card)
        assertThat(html, containsString("""\( \tau = RC \)"""))
        assertThat(html, not(containsString("<pre>")))
    }

    @Test
    fun `exam question card renders answer formulas and keywords through the existing pipeline`() {
        // a question-paper card: derivation answer with display math + exam keywords.
        // Math formatting must be untouched by the exam-question feature.
        val card =
            GeneratedFlashcard(
                front = "Derive the EMF equation of a transformer (8 marks)",
                back =
                    """
                    From Faraday's law, e = N d\u03a6/dt with \u03a6 = \u03a6m sin\u03c9t.
                    \[
                    E_{rms} = 4.44 f N \Phi_m
                    \]
                    """.trimIndent(),
                sections =
                    listOf(
                        CardSection("Exam Keywords", "\u2022 maximum flux\n\u2022 form factor 4.44"),
                    ),
            )
        val html = FlashcardRenderer.ankiFront(card) + FlashcardRenderer.ankiBack(card)
        assertThat(
            html,
            containsString(
                """\[
E_{rms} = 4.44 f N \Phi_m
\]""",
            ),
        )
        assertThat(html, containsString("<b>Exam Keywords</b><br>\u2022 maximum flux<br>\u2022 form factor 4.44"))
        assertThat(html, not(containsString("<pre>")))
    }

    @Test
    fun `review text shows a required image description`() {
        val card =
            GeneratedFlashcard(
                front = "Q",
                back = "A",
                image = CardImage(required = true, prompt = "draw it", alt = "A circuit diagram"),
            )
        assertThat(FlashcardRenderer.reviewText(card), equalTo("A\n\nImage\nA circuit diagram"))
    }

    @Test
    fun `markdown bold becomes html bold in the note and is stripped in preview`() {
        val card =
            GeneratedFlashcard(
                front = "Q",
                back = "A **step-up transformer** increases voltage.",
                sections = listOf(CardSection("Key Points", "\u2022 **more** turns")),
            )
        assertThat(FlashcardRenderer.ankiBack(card), containsString("<b>step-up transformer</b>"))
        assertThat(FlashcardRenderer.ankiBack(card), containsString("<b>more</b>"))
        assertThat(FlashcardRenderer.reviewText(card), containsString("A step-up transformer increases voltage."))
        assertThat(FlashcardRenderer.reviewText(card).contains("**"), equalTo(false))
    }
}
