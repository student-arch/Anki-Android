// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.RobolectricTest
import kotlinx.coroutines.test.runTest
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.not
import org.junit.Test
import org.junit.runner.RunWith

/** Tests for [FlashcardGenerator], including the retry that enforces the requested card count. */
@RunWith(AndroidJUnit4::class)
class FlashcardGeneratorTest : RobolectricTest() {
    private val provider =
        AiProvider(
            id = "p",
            name = "P",
            baseUrl = "https://p.example.com/v1",
            apiKey = "k",
            modelIds = listOf("m"),
        )

    /** Returns canned assistant messages, one per call. */
    private class ScriptedClient(
        responses: List<String>,
    ) : AiClient() {
        private val queue = ArrayDeque(responses)
        var calls = 0

        override suspend fun chatCompletion(
            provider: AiProvider,
            modelId: String,
            systemPrompt: String,
            userPrompt: String,
            jsonMode: Boolean,
        ): String {
            calls++
            return queue.removeFirst()
        }
    }

    private fun cardsJson(vararg fronts: String): String =
        fronts.joinToString(
            prefix = """{"cards": [""",
            postfix = "]}",
            transform = { """{"question": "$it", "answer": "The answer explains $it in full detail."}""" },
        )

    @Test
    fun `retries until the requested number of cards is reached`() =
        runTest {
            val client =
                ScriptedClient(
                    listOf(
                        cardsJson("What is the first valid question about the topic?"),
                        cardsJson(
                            "What is the second valid question about the topic?",
                            "What is the third valid question about the topic?",
                        ),
                    ),
                )
            val cards = FlashcardGenerator(client).generate(provider, "m", "material", 3)
            assertThat(cards, hasSize(3))
            assertThat(
                cards.map { it.front },
                equalTo(
                    listOf(
                        "What is the first valid question about the topic?",
                        "What is the second valid question about the topic?",
                        "What is the third valid question about the topic?",
                    ),
                ),
            )
            assertThat(client.calls, equalTo(2))
        }

    @Test
    fun `duplicate questions across attempts are deduplicated`() =
        runTest {
            val client =
                ScriptedClient(
                    listOf(
                        cardsJson("What is the first valid question about the topic?"),
                        cardsJson(
                            "What is the first valid question about the topic?",
                            "What is the second valid question about the topic?",
                        ),
                    ),
                )
            val cards = FlashcardGenerator(client).generate(provider, "m", "material", 3)
            assertThat(
                cards.map { it.front },
                equalTo(
                    listOf(
                        "What is the first valid question about the topic?",
                        "What is the second valid question about the topic?",
                    ),
                ),
            )
        }

    @Test
    fun `single attempt when the first response already satisfies the count`() =
        runTest {
            val client =
                ScriptedClient(
                    listOf(
                        cardsJson(
                            "What is the first valid question about the topic?",
                            "What is the second valid question about the topic?",
                        ),
                    ),
                )
            val cards = FlashcardGenerator(client).generate(provider, "m", "material", 2)
            assertThat(cards, hasSize(2))
            assertThat(client.calls, equalTo(1))
        }

    @Test
    fun `auto count makes a single attempt and returns everything parsed`() =
        runTest {
            val client =
                ScriptedClient(
                    listOf(
                        cardsJson(
                            "What is the first valid question about the topic?",
                            "What is the second valid question about the topic?",
                            "What is the third valid question about the topic?",
                        ),
                    ),
                )
            val cards = FlashcardGenerator(client).generate(provider, "m", "material", FlashcardGenerator.COUNT_AUTO)
            assertThat(cards, hasSize(3))
            assertThat(client.calls, equalTo(1))
        }

    @Test
    fun `meta cards referencing the material are dropped`() =
        runTest {
            val client =
                ScriptedClient(
                    listOf(
                        """{"cards": [
                            {"question": "What is a transformer based on the material?", "answer": "A device that transfers energy between windings."},
                            {"question": "What does Ohm's law state about voltage and current in a conductor?", "answer": "Current through a conductor is proportional to voltage and inversely proportional to resistance."}
                        ]}""",
                    ),
                )
            val cards = FlashcardGenerator(client).generate(provider, "m", "material", FlashcardGenerator.COUNT_AUTO)
            assertThat(cards.map { it.front }, equalTo(listOf("What does Ohm's law state about voltage and current in a conductor?")))
        }

    @Test
    fun `fragment answers and duplicated question-answer cards are dropped`() =
        runTest {
            val client =
                ScriptedClient(
                    listOf(
                        """{"cards": [
                            {"question": "Is force a vector quantity in mechanics?", "answer": "Yes"},
                            {"question": "What is the formula for kinetic energy in classical mechanics?", "answer": "What is the formula for kinetic energy in classical mechanics?"},
                            {"question": "What does Newton's second law state about force and acceleration?", "answer": "Force equals mass times acceleration, so doubling mass halves the acceleration."}
                        ]}""",
                    ),
                )
            val cards = FlashcardGenerator(client).generate(provider, "m", "material", FlashcardGenerator.COUNT_AUTO)
            assertThat(cards.map { it.front }, equalTo(listOf("What does Newton's second law state about force and acceleration?")))
        }

    @Test
    fun `retry happens when the quality gate drops cards below the count`() =
        runTest {
            val client =
                ScriptedClient(
                    listOf(
                        cardsJson("What is a valid first question about the topic?"),
                        """{"cards": [
                            {"question": "What is a valid second question about the topic?", "answer": "A full and informative answer explaining the concept."},
                            {"question": "Based on the material, what is discussed?", "answer": "A full and informative answer explaining the concept."}
                        ]}""",
                    ),
                )
            val cards = FlashcardGenerator(client).generate(provider, "m", "material", 2)
            assertThat(cards, hasSize(2))
            assertThat(client.calls, equalTo(2))
        }

    @Test
    fun `prompt asks for image prompts when image generation is enabled`() =
        runTest {
            var capturedPrompt: String? = null
            val client =
                object : AiClient() {
                    override suspend fun chatCompletion(
                        provider: AiProvider,
                        modelId: String,
                        systemPrompt: String,
                        userPrompt: String,
                        jsonMode: Boolean,
                    ): String {
                        capturedPrompt = userPrompt
                        return cardsJson("What is a valid first question about the topic?")
                    }
                }
            FlashcardGenerator(client).generate(provider, "m", "material", 1, includeImages = true)

            assertThat(capturedPrompt, containsString("image_prompt"))
        }

    @Test
    fun `prompt omits image instructions when image generation is disabled`() =
        runTest {
            var capturedPrompt: String? = null
            val client =
                object : AiClient() {
                    override suspend fun chatCompletion(
                        provider: AiProvider,
                        modelId: String,
                        systemPrompt: String,
                        userPrompt: String,
                        jsonMode: Boolean,
                    ): String {
                        capturedPrompt = userPrompt
                        return cardsJson("What is a valid first question about the topic?")
                    }
                }
            FlashcardGenerator(client).generate(provider, "m", "material", 1, includeImages = false)

            assertThat(capturedPrompt, not(containsString("image_prompt")))
        }

    @Test
    fun `cards parsed with includeImages disabled carry no image metadata`() =
        runTest {
            val client =
                ScriptedClient(
                    listOf(
                        """{"cards": [
                            {"question": "What is a valid first question about the topic?", "answer": "A full and informative answer explaining the concept.", "image_prompt": "Draw a circuit."},
                            {"question": "What is a valid second question about the topic?", "answer": "Another full and informative answer explaining the concept.", "image": {"required": true, "prompt": "Draw a motor.", "caption": "Motor"}}
                        ]}""",
                    ),
                )
            val cards = FlashcardGenerator(client).generate(provider, "m", "material", 2, includeImages = false)
            assertThat(cards, hasSize(2))
            cards.forEach { card ->
                assertThat(card.image, equalTo(CardImage.None))
                assertThat(card.imageUrl, equalTo(null))
            }
        }
}
