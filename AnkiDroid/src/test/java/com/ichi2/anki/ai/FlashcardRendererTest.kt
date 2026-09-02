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
}
