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

    @Test
    fun `parses CSE sections in canonical order`() {
        val text =
            """
            {"cards": [{
              "question": "What is a HashMap?",
              "answer": "A key-value store with O(1) average lookup.",
              "explanation": "Uses hashing to bucket keys.",
              "time_complexity": "O(1) average",
              "space_complexity": "O(n)",
              "code": "map.get(key)",
              "code_language": "java",
              "comparison": "HashMap vs TreeMap",
              "source": "CLRS ch.11"
            }]}
            """.trimIndent()
        val cards = FlashcardParser.parse(text)
        assertThat(cards, hasSize(1))
        val card = cards[0]
        assertThat(card.front, equalTo("What is a HashMap?"))
        assertThat(card.back, equalTo("A key-value store with O(1) average lookup."))
        assertThat(
            card.sections.map { it.label },
            equalTo(listOf("Explanation", "Code (java)", "Time Complexity", "Space Complexity", "Comparison", "Source")),
        )
        assertThat(card.sections.first { it.label == "Explanation" }.content, equalTo("Uses hashing to bucket keys."))
    }

    @Test
    fun `empty and null sections are skipped`() {
        val text =
            """
            {"cards": [{
              "question": "Q",
              "answer": "A",
              "definition": "",
              "example": null,
              "explanation": "Why."
            }]}
            """.trimIndent()
        val cards = FlashcardParser.parse(text)
        assertThat(cards[0].sections.map { it.label }, equalTo(listOf("Explanation")))
    }

    @Test
    fun `metadata becomes tags`() {
        val text =
            """
            {"cards": [{
              "question": "Q",
              "answer": "A",
              "difficulty": "Medium",
              "subject": "DSA",
              "topic": "Hash Map",
              "tags": ["hashing", "arrays"]
            }]}
            """.trimIndent()
        val cards = FlashcardParser.parse(text)
        assertThat(cards[0].tags, containsInAnyOrder("hashing", "arrays", "Medium", "DSA", "Hash-Map"))
    }

    @Test
    fun `tags as comma separated string are split`() {
        val text = """{"cards": [{"question": "Q", "answer": "A", "tags": "one, two"}]}"""
        val cards = FlashcardParser.parse(text)
        assertThat(cards[0].tags, equalTo(listOf("one", "two")))
    }

    @Test
    fun `legacy front back schema is still parsed`() {
        val cards = FlashcardParser.parse("""{"cards": [{"front": "F", "back": "B"}]}""")
        assertThat(cards[0].front, equalTo("F"))
        assertThat(cards[0].back, equalTo("B"))
        assertThat(cards[0].sections, empty())
    }

    @Test
    fun `unknown keys are ignored`() {
        val cards = FlashcardParser.parse("""{"cards": [{"question": "Q", "answer": "A", "mood": "happy"}]}""")
        assertThat(cards[0].sections, empty())
        assertThat(cards[0].tags, empty())
    }

    @Test
    fun `multiline section content is preserved`() {
        val text = """{"cards": [{"question": "Q", "answer": "A", "code": "fun f() {\n    return 1\n}"}]}"""
        val cards = FlashcardParser.parse(text)
        assertThat(cards[0].sections.single().content, equalTo("fun f() {\n    return 1\n}"))
    }

    @Test
    fun `bare image url is parsed`() {
        val text = """{"cards": [{"question": "Q", "answer": "A", "image": "https://example.com/cpu.png"}]}"""
        val cards = FlashcardParser.parse(text)
        assertThat(cards[0].imageUrl, equalTo("https://example.com/cpu.png"))
    }

    @Test
    fun `markdown image syntax is converted to a plain url`() {
        val text = """{"cards": [{"question": "Q", "answer": "A", "image": "![CPU](https://example.com/cpu.png)"}]}"""
        val cards = FlashcardParser.parse(text)
        assertThat(cards[0].imageUrl, equalTo("https://example.com/cpu.png"))
    }

    @Test
    fun `html img tag is converted to a plain url`() {
        val text = """{"cards": [{"question": "Q", "answer": "A", "image": "<img src=\"https://example.com/cpu.png\">"}]}"""
        val cards = FlashcardParser.parse(text)
        assertThat(cards[0].imageUrl, equalTo("https://example.com/cpu.png"))
    }

    @Test
    fun `non url image value is dropped`() {
        val text = """{"cards": [{"question": "Q", "answer": "A", "image": "a diagram of a CPU"}]}"""
        val cards = FlashcardParser.parse(text)
        assertThat(cards[0].imageUrl, equalTo(null))
    }

    @Test
    fun `structured image object is parsed`() {
        val text =
            """
            {"cards": [{
              "question": "Q",
              "answer": "A",
              "image": {
                "required": true,
                "type": "circuit",
                "prompt": "Create a clean educational diagram of a series circuit.",
                "alt": "A series circuit with a battery and two resistors",
                "caption": "Series circuit"
              }
            }]}
            """.trimIndent()
        val image = FlashcardParser.parse(text)[0].image
        assertThat(image.required, equalTo(true))
        assertThat(image.type, equalTo("circuit"))
        assertThat(image.prompt, equalTo("Create a clean educational diagram of a series circuit."))
        assertThat(image.alt, equalTo("A series circuit with a battery and two resistors"))
        assertThat(image.caption, equalTo("Series circuit"))
    }

    @Test
    fun `image not required is parsed as none`() {
        val text = """{"cards": [{"question": "Q", "answer": "A", "image": {"required": false}}]}"""
        assertThat(FlashcardParser.parse(text)[0].image.required, equalTo(false))
    }

    @Test
    fun `required image without a prompt is treated as not required`() {
        val text = """{"cards": [{"question": "Q", "answer": "A", "image": {"required": true}}]}"""
        assertThat(FlashcardParser.parse(text)[0].image.required, equalTo(false))
    }

    @Test
    fun `key points array becomes a bulleted section`() {
        val text = """{"cards": [{"question": "Q", "answer": "A", "key_points": ["Voltage: V", "Current: I"]}]}"""
        val section = FlashcardParser.parse(text)[0].sections.single()
        assertThat(section.label, equalTo("Key Points"))
        assertThat(section.content, equalTo("\u2022 Voltage: V\n\u2022 Current: I"))
    }

    @Test
    fun `exam keywords array becomes a bulleted section after source`() {
        val text =
            """
            {"cards": [{
              "question": "Derive the EMF equation of a transformer (8 marks)",
              "answer": "E = 4.44 f N \u03a6m, from Faraday's law with \u03a6 = \u03a6m sin\u03c9t.",
              "source": "Dec 2024 Q3",
              "exam_keywords": ["Faraday's law", "maximum flux", "form factor 4.44"]
            }]}
            """.trimIndent()
        val card = FlashcardParser.parse(text)[0]
        val labels = card.sections.map { it.label }
        assertThat(labels, equalTo(listOf("Source", "Exam Keywords")))
        assertThat(
            card.sections.last().content,
            equalTo("\u2022 Faraday's law\n\u2022 maximum flux\n\u2022 form factor 4.44"),
        )
    }

    @Test
    fun `numerical problem sections are parsed in order`() {
        val text =
            """
            {"cards": [{
              "question": "Current through a 10 ohm resistor at 20 V?",
              "answer": "2 A",
              "given": "V = 20 V\nR = 10 \u03a9",
              "formula": "I = V / R",
              "solution": "I = 20 / 10",
              "final_answer": "I = 2 A",
              "common_mistake": "Confusing \u03a9 with A"
            }]}
            """.trimIndent()
        val card = FlashcardParser.parse(text)[0]
        assertThat(
            card.sections.map { it.label },
            equalTo(listOf("Formula", "Given", "Solution", "Final Answer", "Common Mistake")),
        )
    }

    @Test
    fun `flat image_prompt string is accepted as a required image`() {
        val text = """{"cards": [{"question": "Q", "answer": "A", "image_prompt": "Draw a circuit."}]}"""
        val image = FlashcardParser.parse(text)[0].image
        assertThat(image.required, equalTo(true))
        assertThat(image.prompt, equalTo("Draw a circuit."))
    }

    @Test
    fun `salvages complete cards from truncated model output`() {
        // a broken/truncated array: first card is complete, second is cut off mid-object
        val text =
            """[{"question": "Q1", "answer": "A1"}, {"question": "Q2", "answer": "trunc"""
        val cards = FlashcardParser.parse(text)
        assertThat(cards.map { it.front }, containsInAnyOrder("Q1"))
    }

    @Test
    fun `subject and topic are parsed as fields`() {
        val text = """{"cards": [{"question": "Q", "answer": "A", "subject": "Mechanical Engineering", "topic": "FBD"}]}"""
        val card = FlashcardParser.parse(text)[0]
        assertThat(card.subject, equalTo("Mechanical Engineering"))
        assertThat(card.topic, equalTo("FBD"))
    }
}
