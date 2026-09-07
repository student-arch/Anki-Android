// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ai

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
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
    fun `caption html uses the image caption`() {
        val card =
            GeneratedFlashcard(
                front = "Q",
                back = "A",
                image = CardImage(required = true, prompt = "p", caption = "Series circuit"),
            )
        assertThat(FlashcardRenderer.captionHtml(card), containsString("Series circuit"))
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
