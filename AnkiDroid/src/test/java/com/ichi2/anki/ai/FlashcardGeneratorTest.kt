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

    /** [fronts] numbered questions whose fronts are unique and pass the quality gate. */
    private fun numberedFronts(range: IntRange): Array<String> =
        range.map { "What is valid question number $it about the topic?" }.toTypedArray()

    @Test
    fun `exact requested count is reached across deficit top-up requests`() =
        runTest {
            // the first response covers only 7 of the 13 requested cards; each follow-up
            // must ask for just the deficit until the exact number is delivered
            val client =
                ScriptedClient(
                    listOf(
                        cardsJson(*numberedFronts(1..7)),
                        cardsJson(*numberedFronts(8..10)),
                        cardsJson(*numberedFronts(11..13)),
                    ),
                )
            val cards = FlashcardGenerator(client).generate(provider, "m", "material", 13)
            assertThat(cards, hasSize(13))
            assertThat(cards.map { it.front }, equalTo(numberedFronts(1..13).toList()))
            assertThat(client.calls, equalTo(3))
        }

    @Test
    fun `prompt demands the exact number of cards`() =
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
                        return cardsJson(*numberedFronts(1..13))
                    }
                }
            FlashcardGenerator(client).generate(provider, "m", "material", 13)

            assertThat(capturedPrompt.orEmpty(), containsString("EXACTLY 13 flashcards"))
        }

    @Test
    fun `deficit request asks only for the missing cards and excludes existing questions`() =
        runTest {
            val prompts = mutableListOf<String>()
            val client =
                object : AiClient() {
                    override suspend fun chatCompletion(
                        provider: AiProvider,
                        modelId: String,
                        systemPrompt: String,
                        userPrompt: String,
                        jsonMode: Boolean,
                    ): String {
                        prompts.add(userPrompt)
                        return if (prompts.size == 1) {
                            cardsJson(*numberedFronts(1..7))
                        } else {
                            cardsJson(*numberedFronts(8..13))
                        }
                    }
                }
            val cards = FlashcardGenerator(client).generate(provider, "m", "material", 13)

            assertThat(cards, hasSize(13))
            assertThat(prompts.size, equalTo(2))
            // the follow-up requests only the 6 missing cards
            assertThat(prompts[1], containsString("EXACTLY 6 additional flashcards"))
            // and lists the questions already generated so none are repeated
            assertThat(prompts[1], containsString("What is valid question number 3 about the topic?"))
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
                        cardsJson("What is the third valid question about the topic?"),
                    ),
                )
            val cards = FlashcardGenerator(client).generate(provider, "m", "material", 3)
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
    fun `prompt instructs the model to wrap math in MathJax delimiters`() =
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
            FlashcardGenerator(client).generate(provider, "m", "material", 1)

            val prompt = capturedPrompt.orEmpty()
            assertThat(prompt, containsString("""\( ... \)"""))
            assertThat(prompt, containsString("""\[ ... \]"""))
            assertThat(prompt, containsString("""\ce{H2O}"""))
            assertThat(prompt, containsString("""\ket{\psi}"""))
            assertThat(prompt, containsString("""\braket{\phi}{\psi}"""))
        }

    /**
     * A bare topic (as the input hint invites: "describe a topic, e.g. Key concepts of
     * photosynthesis") contains no learnable content, so the strict grounding rules make
     * models return an empty card list: with nothing grounded to extract, "never invent"
     * forbids any card. Topic-style input must switch the prompt to topical mode, where
     * the model may draw on its own knowledge of the subject.
     */
    @Test
    fun `topic input relaxes the material-only grounding rules`() =
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
            FlashcardGenerator(client).generate(provider, "m", "Qubit concept", 1)

            val prompt = capturedPrompt.orEmpty()
            assertThat(prompt, containsString("topic"))
            // the strict rule text must not be sent for topic input
            assertThat(prompt.contains("do not use external knowledge"), equalTo(false))
        }

    @Test
    fun `full material keeps the material-only grounding rules`() =
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
            FlashcardGenerator(client).generate(
                provider,
                "m",
                "Ohm's law states that V = IR. Voltage equals current times resistance.",
                1,
            )

            val prompt = capturedPrompt.orEmpty()
            assertThat(prompt, containsString("do not use external knowledge"))
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

    /** Fails the first N calls with a transient error, then succeeds — like a busy gateway. */
    private inner class FlakyThenWorkingClient(
        private val failures: List<Exception>,
    ) : AiClient() {
        var calls = 0

        override suspend fun chatCompletion(
            provider: AiProvider,
            modelId: String,
            systemPrompt: String,
            userPrompt: String,
            jsonMode: Boolean,
        ): String {
            calls++
            failures.getOrNull(calls - 1)?.let { throw it }
            return cardsJson("What is a valid first question about the topic?")
        }
    }

    @Test
    fun `transient server overload is retried and succeeds`() =
        runTest {
            val client =
                FlakyThenWorkingClient(
                    listOf(AiException.Server("HTTP 503: gateway overloaded")),
                )
            val cards = FlashcardGenerator(client).generate(provider, "m", "material", 1)
            assertThat(cards, hasSize(1))
            assertThat(client.calls, equalTo(2))
        }

    @Test
    fun `transient network abort is retried and succeeds`() =
        runTest {
            val client =
                FlakyThenWorkingClient(
                    listOf(
                        AiException.Network("Software caused connection abort"),
                        AiException.Server("HTTP 503: cache-aware admission rejected a request"),
                    ),
                )
            val cards = FlashcardGenerator(client).generate(provider, "m", "material", 1)
            assertThat(cards, hasSize(1))
            assertThat(client.calls, equalTo(3))
        }

    @Test
    fun `bad request is not retried`() =
        runTest {
            val client =
                FlakyThenWorkingClient(
                    listOf(AiException.BadRequest("HTTP 400: invalid model")),
                )
            try {
                FlashcardGenerator(client).generate(provider, "m", "material", 1)
                throw AssertionError("expected BadRequest to propagate")
            } catch (e: AiException.BadRequest) {
                assertThat(client.calls, equalTo(1))
            }
        }

    @Test
    fun `persistent overload surfaces as an error after exhausting retries`() =
        runTest {
            val client =
                FlakyThenWorkingClient(
                    List(4) { AiException.Server("HTTP 503: gateway overloaded") },
                )
            try {
                FlashcardGenerator(client).generate(provider, "m", "material", 1)
                throw AssertionError("expected Server error to propagate")
            } catch (e: AiException.Server) {
                assertThat(client.calls, equalTo(4))
            }
        }

    @Test
    fun `duplicate fronts across responses are deduplicated`() =
        runTest {
            val client =
                object : AiClient() {
                    private var calls = 0

                    override suspend fun chatCompletion(
                        provider: AiProvider,
                        modelId: String,
                        systemPrompt: String,
                        userPrompt: String,
                        jsonMode: Boolean,
                    ): String {
                        calls++
                        // first response: one unique card; second: the same card again plus a new one
                        return if (calls == 1) {
                            cardsJson("What is the first valid question about the topic?")
                        } else {
                            cardsJson(
                                "What is the first valid question about the topic?",
                                "What is the second valid question about the topic?",
                            )
                        }
                    }
                }
            val cards = FlashcardGenerator(client).generate(provider, "m", "material", 2)
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
    fun `quality gate drops meta and fragment cards from a response`() =
        runTest {
            val client =
                object : AiClient() {
                    override suspend fun chatCompletion(
                        provider: AiProvider,
                        modelId: String,
                        systemPrompt: String,
                        userPrompt: String,
                        jsonMode: Boolean,
                    ): String =
                        """{"cards": [
                            {"question": "What is the first valid question about the topic?", "answer": "The answer explains it in full detail."},
                            {"question": "Duplicate front", "answer": "ok answer here"},
                            {"question": "What does the material say about X?", "answer": "fine answer here too"}
                        ]}"""
                }
            val cards = FlashcardGenerator(client).generate(provider, "m", "material", 3)
            // the duplicate is kept once; the meta question never; the count is not reached,
            // so the generator keeps requesting until MAX_REQUESTS stops the loop
            assertThat(cards.map { it.front }, equalTo(listOf("What is the first valid question about the topic?", "Duplicate front")))
        }
}
