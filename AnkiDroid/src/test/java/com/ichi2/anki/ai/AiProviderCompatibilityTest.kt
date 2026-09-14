// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.RobolectricTest
import kotlinx.coroutines.runBlocking
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.greaterThanOrEqualTo
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end compatibility test of the AI flashcard pipeline against a real
 * OpenAI-compatible provider: a local HTTP server replies with the response that the
 * Token Router `z-ai/glm-5.3-free` model actually returned for the app's generation
 * prompt (captured from a live request), and the test drives the genuine client
 * (HTTP request including the token budget), parser, quality gate and renderer.
 */
@RunWith(AndroidJUnit4::class)
class AiProviderCompatibilityTest : RobolectricTest() {
    /** The JSON `z-ai/glm-5.3-free` produced for the full generation prompt (live capture). */
    private val glmResponse =
        """{"cards": [{"subject": "Electrical Engineering", "topic": "Ohm's Law", """ +
            """"question": "What is Ohm's Law, and what does each symbol mean?", """ +
            """"answer": "Ohm's Law states that voltage equals current times resistance: \\( V = IR \\). """ +
            """Here \\( V \\) is voltage, \\( I \\) is current, and \\( R \\) is resistance.", """ +
            """"key_points": ["\\( V \\) is the voltage", "\\( I \\) is the current", "\\( R \\) is the resistance"], """ +
            """"formula": "\\( V = IR \\)", "difficulty": "Easy", "tags": ["circuits", "ohms-law", "basics"]}, """ +
            """{"subject": "Electrical Engineering", "topic": "RC circuits", """ +
            """"question": "What is the time constant of an RC circuit, and how is it calculated?", """ +
            """"answer": "The time constant is \\( \\tau = RC \\), where \\( R \\) is the resistance in ohms """ +
            """and \\( C \\) is the capacitance in farads.", """ +
            """"formula": "\\( \\tau = RC \\)", "difficulty": "Medium", "tags": ["rc-circuit", "transient-response"]}]}"""

    /**
     * A minimal OpenAI-compatible `/v1/chat/completions` server: records the request and
     * responds with [glmResponse] wrapped in the standard completion envelope.
     */
    private class ProviderServer(
        private val content: String,
    ) {
        var lastRequest: JSONObject? = null
            private set
        val server =
            com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress(0), 0).apply {
                createContext("/v1/chat/completions") { exchange ->
                    lastRequest = JSONObject(exchange.requestBody.readBytes().decodeToString())
                    val body =
                        JSONObject()
                            .put(
                                "choices",
                                JSONArray().put(
                                    JSONObject()
                                        .put(
                                            "message",
                                            JSONObject().put("role", "assistant").put("content", content),
                                        ).put("finish_reason", "stop"),
                                ),
                            ).toString()
                    exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
                    exchange.responseBody.use { it.write(body.toByteArray()) }
                }
                start()
            }

        fun stop() = server.stop(0)
    }

    private fun providerFor(server: ProviderServer) =
        AiProvider(
            id = "tokenrouter",
            name = "Token Router",
            baseUrl = "http://localhost:${server.server.address.port}/v1",
            apiKey = "test-key",
            modelIds = listOf("z-ai/glm-5.3-free"),
        )

    @Test
    fun `glm flashcards flow from OpenAI-compatible provider through parser gate and renderer`() =
        runBlocking {
            val server = ProviderServer(glmResponse)
            try {
                val provider = providerFor(server)
                // the real client: HTTP, JSON payload, token budget and response parsing
                val cards = FlashcardGenerator(AiClient()).generate(provider, "z-ai/glm-5.3-free", MATERIAL, FlashcardGenerator.COUNT_AUTO)

                // the request the app sent must be OpenAI-compatible and carry a reasoning-safe budget
                val request = server.lastRequest!!
                assertThat(request.getString("model"), equalTo("z-ai/glm-5.3-free"))
                assertThat(request.getJSONArray("messages").length(), greaterThanOrEqualTo(2))
                assertThat(request.getInt("max_tokens"), equalTo(AiClient.MAX_COMPLETION_TOKENS))

                // both well-formed glm cards survive the quality gate
                assertThat(cards.size, equalTo(2))
                val first = cards[0]
                assertThat(first.front, containsString("Ohm's Law"))
                assertThat(first.subject, equalTo("Electrical Engineering"))
                assertThat(first.tags.joinToString(","), containsString("ohms-law"))

                // rendered HTML keeps MathJax delimiters intact for the viewer's MathJax,
                // and formula sections containing math render in normal flow (not <pre>:
                // MathJax skips <pre> content), as the failure HTML above shows
                val html = FlashcardRenderer.ankiBack(first)
                assertThat(html, containsString("""\( V = IR \)"""))
                assertThat(html, containsString("<b>Formula</b><br>"))
            } finally {
                server.stop()
            }
        }

    @Test
    fun `truncated reasoning-model output keeps the salvageable cards`() =
        runBlocking {
            // glm-5.3-free with a too-small budget truncated the JSON mid-card
            // (finish_reason=length after ~3700 hidden reasoning tokens): the first cards
            // were still complete objects and must be salvaged instead of losing everything
            val truncated =
                glmResponse.substringBefore(""""tags": ["rc-circuit"""") + "\"tags\": [\"rc-circuit\""
            val server = ProviderServer(truncated)
            try {
                val cards =
                    FlashcardGenerator(
                        AiClient(),
                    ).generate(providerFor(server), "z-ai/glm-5.3-free", MATERIAL, FlashcardGenerator.COUNT_AUTO)
                assertThat(cards.size, greaterThanOrEqualTo(1))
                assertThat(cards[0].front, containsString("Ohm's Law"))
            } finally {
                server.stop()
            }
        }

    private companion object {
        private const val MATERIAL =
            "Ohm's law: V = IR. The time constant of an RC circuit is tau = RC, " +
                "where R is resistance in ohms and C is capacitance in farads."
    }
}
