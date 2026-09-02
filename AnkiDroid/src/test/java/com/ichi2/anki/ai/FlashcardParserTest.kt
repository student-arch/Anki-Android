// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.RobolectricTest
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.empty
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.junit.Test
import org.junit.runner.RunWith

/** Tests for parsing flashcards out of raw AI responses. */
@RunWith(AndroidJUnit4::class)
class FlashcardParserTest : RobolectricTest() {
    @Test
    fun `parses a plain JSON object`() {
        val text =
            """{"cards": [{"front": "What is 2+2?", "back": "4"}, """ +
                """{"front": "Capital of France?", "back": "Paris"}]}"""
        val cards = FlashcardParser.parse(text)
        assertThat(cards, hasSize(2))
        assertThat(cards[0].front, equalTo("What is 2+2?"))
        assertThat(cards[0].back, equalTo("4"))
        assertThat(cards[1].front, equalTo("Capital of France?"))
        assertThat(cards[1].back, equalTo("Paris"))
    }

    @Test
    fun `parses JSON in a code fence`() {
        val text =
            """
            Here are your flashcards:
            ```json
            {"cards": [{"front": "F1", "back": "B1"}]}
            ```
            """.trimIndent()
        val cards = FlashcardParser.parse(text)
        assertThat(cards, hasSize(1))
        assertThat(cards[0].front, equalTo("F1"))
        assertThat(cards[0].back, equalTo("B1"))
    }

    @Test
    fun `parses a top level JSON array`() {
        val cards = FlashcardParser.parse("""[{"front": "F", "back": "B"}]""")
        assertThat(cards, hasSize(1))
        assertThat(cards[0].front, equalTo("F"))
    }

    @Test
    fun `drops cards with empty fields`() {
        val text =
            """
            {"cards": [
              {"front": "", "back": "B"},
              {"front": "F", "back": ""},
              {"front": "F2", "back": "B2"}
            ]}
            """.trimIndent()
        val cards = FlashcardParser.parse(text)
        assertThat(cards, hasSize(1))
        assertThat(cards[0].front, equalTo("F2"))
    }

    @Test
    fun `returns empty for garbage input`() {
        assertThat(FlashcardParser.parse("The model apologizes but has no cards today."), empty())
        assertThat(FlashcardParser.parse(""), empty())
        assertThat(FlashcardParser.parse("""{"cards": "not an array"}"""), empty())
    }

    @Test
    fun `trims whitespace in fields`() {
        val cards = FlashcardParser.parse("""{"cards": [{"front": "  F  ", "back": "\nB\n"}]}""")
        assertThat(cards, hasSize(1))
        assertThat(cards[0].front, equalTo("F"))
        assertThat(cards[0].back, equalTo("B"))
    }

    @Test
    fun `generated cards have unique ids`() {
        val cards = FlashcardParser.parse("""{"cards": [{"front": "A", "back": "B"}, {"front": "C", "back": "D"}]}""")
        assertThat(cards.map { it.id }.toSet(), hasSize(cards.size))
    }

    @Test
    fun `extracts object embedded in prose`() {
        val cards =
            FlashcardParser.parse(
                """Sure! {"cards": [{"front": "Q", "back": "A"}]} Hope that helps!""",
            )
        assertThat(cards.map { it.front }, containsInAnyOrder("Q"))
    }
}
