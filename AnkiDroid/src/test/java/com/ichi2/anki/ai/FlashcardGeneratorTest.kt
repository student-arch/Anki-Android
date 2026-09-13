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

    /**
     * A client whose streaming path emits cards one by one, as a real OpenAI-compatible
     * provider does once each card object completes on the wire. Not inner (anonymous
     * subclasses below), so the card JSON is built locally instead of via the test's helper.
     */
    private open class StreamingClient : AiClient() {
        override suspend fun chatCompletionStream(
            provider: AiProvider,
            modelId: String,
            systemPrompt: String,
            userPrompt: String,
            jsonMode: Boolean,
            onDelta: suspend (String) -> Unit,
        ): String {
            val cards =
                listOf(
                    "What is the first valid question about the topic?",
                    "What is the second valid question about the topic?",
                    "What is the third valid question about the topic?",
                )
            var accumulated = ""
            cards.forEach { front ->
                // each snapshot ends right after a complete card object
                accumulated = """{"cards": [{"question": "$front", "answer": "The answer explains it in full detail."}]}"""
                onDelta(accumulated)
            }
            return accumulated
        }
    }

    @Test
    fun `streaming emits each card as soon as it completes`() =
        runTest {
            val client = StreamingClient()
            val received = mutableListOf<GeneratedFlashcard>()
            FlashcardGenerator(client).generateStream(provider, "m", "material", 3) { card ->
                received.add(card)
            }

            // every card is emitted exactly once, in order, before the call returns
            assertThat(received.map { it.front }, hasSize(3))
            assertThat(
                received.map { it.front },
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
    fun `streaming applies the quality gate and deduplication incrementally`() =
        runTest {
            val client =
                object : AiClient() {
                    override suspend fun chatCompletionStream(
                        provider: AiProvider,
                        modelId: String,
                        systemPrompt: String,
                        userPrompt: String,
                        jsonMode: Boolean,
                        onDelta: suspend (String) -> Unit,
                    ): String {
                        // a duplicate front and a meta question that must both be filtered out
                        val bad = """{"cards": [{"question": "Duplicate front", "answer": "ok answer here"}, {"question": "What does the material say about X?", "answer": "fine answer here too"}]}"""
                        onDelta("""{"cards": [{"question": "Duplicate front", "answer": "ok answer here"}]}""")
                        onDelta(bad)
                        return bad
                    }
                }
            val received = mutableListOf<GeneratedFlashcard>()
            FlashcardGenerator(client).generateStream(provider, "m", "material", 5) { card ->
                received.add(card)
            }

            // the duplicate is emitted once; the meta question never; nothing else
            assertThat(received.size, equalTo(1))
            assertThat(received[0].front, equalTo("Duplicate front"))
        }

    @Test
    fun `streaming falls back to the batch response when the provider cannot stream`() =
        runTest {
            // providers without SSE support return the whole body at once (or reject
            // `stream`): the accumulated text still yields all its cards via the callback
            val client =
                object : AiClient() {
                    override suspend fun chatCompletionStream(
                        provider: AiProvider,
                        modelId: String,
                        systemPrompt: String,
                        userPrompt: String,
                        jsonMode: Boolean,
                        onDelta: suspend (String) -> Unit,
                    ): String = cardsJson("What is a valid first question about the topic?")
                }
            val received = mutableListOf<GeneratedFlashcard>()
            FlashcardGenerator(client).generateStream(provider, "m", "material", 1) { card ->
                received.add(card)
            }
            assertThat(received.size, equalTo(1))
        }
}
