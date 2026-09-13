// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.importer

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.RobolectricTest
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TextImportParserTest : RobolectricTest() {
    @Test
    fun `separated lines parse to cards`() {
        val cards = TextImportParser.parse("What is 2+2?; 4\nCapital of France - Paris")
        assertThat(cards, hasSize(2))
        assertThat(cards[0].front, equalTo("What is 2+2?"))
        assertThat(cards[0].back, equalTo("4"))
        assertThat(cards[1].front, equalTo("Capital of France"))
        assertThat(cards[1].back, equalTo("Paris"))
    }

    @Test
    fun `question answer headers are detected`() {
        val cards =
            TextImportParser.parse(
                """Question: What is \( x^2 \)?
Answer: \( x \cdot x \)""",
            )
        assertThat(cards, hasSize(1))
        assertThat(cards[0].front, equalTo("""What is \( x^2 \)?"""))
        assertThat(cards[0].back, equalTo("""\( x \cdot x \)"""))
    }

    @Test
    fun `formulas and unicode are preserved exactly`() {
        val input = """Schrödinger equation; \( i\hbar\frac{\partial}{\partial t}|\psi\rangle = \hat{H}|\psi\rangle \)"""
        val cards = TextImportParser.parse(input)
        assertThat(cards[0].back, equalTo(input.substringAfter("; ")))
        assertThat(cards[0].front, equalTo("Schrödinger equation"))
    }

    @Test
    fun `tab separated cards parse`() {
        val cards = TextImportParser.parse("front1\tback1\nfront2\tback2")
        assertThat(cards, hasSize(2))
        assertThat(cards[0].back, equalTo("back1"))
        assertThat(cards[1].back, equalTo("back2"))
    }

    @Test
    fun `blank-line separated blocks parse`() {
        val cards =
            TextImportParser.parse(
                """
                What is water?; H₂O

                What is CO₂?; carbon dioxide
                """.trimIndent(),
            )
        assertThat(cards, hasSize(2))
        assertThat(cards[0].back, equalTo("H₂O"))
    }

    @Test
    fun `lines without a separator are skipped`() {
        val cards = TextImportParser.parse("just a line\nanother line without separator")
        assertThat(cards, hasSize(0))
    }

    @Test
    fun `label-only lines are not cards`() {
        val cards = TextImportParser.parse("Question:\nAnswer:")
        assertThat(cards, hasSize(0))
    }

    @Test
    fun `front back headers are detected`() {
        val cards = TextImportParser.parse("Front: side one\nBack: side two")
        assertThat(cards, hasSize(1))
        assertThat(cards[0].front, equalTo("side one"))
        assertThat(cards[0].back, equalTo("side two"))
    }

    @Test
    fun `empty text produces no cards`() {
        assertThat(TextImportParser.parse(""), hasSize(0))
        assertThat(TextImportParser.parse("\n\n"), hasSize(0))
    }

    @Test
    fun `crlf line endings are handled`() {
        val cards = TextImportParser.parse("a; b\r\nc; d")
        assertThat(cards, hasSize(2))
        assertThat(cards[1].back, equalTo("d"))
    }

    @Test
    fun `empty side is not a card`() {
        val cards = TextImportParser.parse("only front;")
        assertThat(cards, hasSize(0))
    }

    @Test
    fun `hyphen inside words is not a separator`() {
        val cards = TextImportParser.parse("well-known; famous")
        assertThat(cards, hasSize(1))
        assertThat(cards[0].front, equalTo("well-known"))
    }

    @Test
    fun `front and back separated by a blank line parse to one card`() {
        val cards =
            TextImportParser.parse(
                """
                Front: What is the Schrödinger equation?

                Back: iℏ ∂|ψ⟩/∂t = Ĥ|ψ⟩
                """.trimIndent(),
            )
        assertThat(cards, hasSize(1))
        assertThat(cards[0].front, equalTo("What is the Schrödinger equation?"))
        assertThat(cards[0].back, equalTo("iℏ ∂|ψ⟩/∂t = Ĥ|ψ⟩"))
    }

    @Test
    fun `question and answer separated by a blank line parse to one card`() {
        val cards = TextImportParser.parse("Question: What is E = mc²?\n\nAnswer: Mass and energy equivalence")
        assertThat(cards, hasSize(1))
        assertThat(cards[0].front, equalTo("What is E = mc²?"))
        assertThat(cards[0].back, equalTo("Mass and energy equivalence"))
    }

    @Test
    fun `multiple label pairs separated by blank lines parse`() {
        val cards =
            TextImportParser.parse(
                """
                Front: First?

                Back: First answer

                Front: Second?

                Back: Second answer
                """.trimIndent(),
            )
        assertThat(cards, hasSize(2))
        assertThat(cards[0].front, equalTo("First?"))
        assertThat(cards[0].back, equalTo("First answer"))
        assertThat(cards[1].front, equalTo("Second?"))
        assertThat(cards[1].back, equalTo("Second answer"))
    }

    @Test
    fun `labeled front containing a separator keeps its content when back follows`() {
        val cards = TextImportParser.parse("Front: H₂O - water\n\nBack: A molecule")
        assertThat(cards, hasSize(1))
        assertThat(cards[0].front, equalTo("H₂O - water"))
        assertThat(cards[0].back, equalTo("A molecule"))
    }

    @Test
    fun `front does not pair with a back after an intervening block`() {
        val cards = TextImportParser.parse("Front: A\n\nWhat is 2+2?; 4\n\nBack: B")
        assertThat(cards, hasSize(1))
        assertThat(cards[0].front, equalTo("What is 2+2?"))
        assertThat(cards[0].back, equalTo("4"))
    }

    @Test
    fun `chemistry ion notation is preserved`() {
        val cards = TextImportParser.parse("Na⁺; sodium ion\nCl⁻; chloride ion")
        assertThat(cards, hasSize(2))
        assertThat(cards[0].front, equalTo("Na⁺"))
        assertThat(cards[1].front, equalTo("Cl⁻"))
    }
}
