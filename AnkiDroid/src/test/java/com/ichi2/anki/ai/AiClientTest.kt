// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.RobolectricTest
import kotlinx.coroutines.runBlocking
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.equalTo
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/** Tests for parsing OpenAI-compatible API responses and normalizing base URLs. */
@RunWith(AndroidJUnit4::class)
class AiClientTest : RobolectricTest() {
    @Test
    fun `normalizeBaseUrl trims whitespace and trailing slashes`() {
        assertThat(AiClient.normalizeBaseUrl(" https://api.openai.com/v1/ "), equalTo("https://api.openai.com/v1"))
        assertThat(AiClient.normalizeBaseUrl("https://api.openai.com"), equalTo("https://api.openai.com"))
    }

    @Test
    fun `buildApiUrl appends path under the base path`() {
        assertThat(
            AiClient.buildApiUrl("https://api.openai.com/v1", "chat/completions")?.toString(),
            equalTo("https://api.openai.com/v1/chat/completions"),
        )
        assertThat(
            AiClient.buildApiUrl("https://api.openai.com/v1/", "models")?.toString(),
            equalTo("https://api.openai.com/v1/models"),
        )
        assertThat(
            AiClient.buildApiUrl("https://api.openai.com", "models")?.toString(),
            equalTo("https://api.openai.com/models"),
        )
        assertThat(AiClient.buildApiUrl("not a url", "models"), equalTo(null))
    }

    @Test
    fun `parses model ids`() {
        val body = """{"object": "list", "data": [{"id": "gpt-4o"}, {"id": "gpt-4o-mini"}]}"""
        assertThat(AiClient.parseModelIds(body), containsInAnyOrder("gpt-4o", "gpt-4o-mini"))
    }

    @Test
    fun `empty model list throws parse exception`() {
        assertThrows(AiException.Parse::class.java) { AiClient.parseModelIds("""{"data": []}""") }
    }

    @Test
    fun `invalid model list throws parse exception`() {
        assertThrows(AiException.Parse::class.java) { AiClient.parseModelIds("not json") }
    }

    @Test
    fun `parses completion message`() {
        val body = """{"choices": [{"message": {"role": "assistant", "content": "hello"}}]}"""
        assertThat(AiClient.parseCompletionMessage(body), equalTo("hello"))
    }

    @Test
    fun `missing choices throws parse exception`() {
        assertThrows(AiException.Parse::class.java) { AiClient.parseCompletionMessage("""{"choices": []}""") }
        assertThrows(AiException.Parse::class.java) { AiClient.parseCompletionMessage("""{"error": "x"}""") }
    }

    @Test
    fun `extracts provider error message`() {
        val body = """{"error": {"message": "Incorrect API key", "type": "auth"}}"""
        assertThat(AiClient.extractErrorMessage(body), equalTo("Incorrect API key"))
    }

    @Test
    fun `chatCompletion returns the assistant message content`() =
        runBlocking {
            val content = """{"cards": [{"front": "Q", "back": "A"}]}"""
            val envelope =
                org.json
                    .JSONObject()
                    .put(
                        "choices",
                        org.json.JSONArray().put(
                            org.json.JSONObject().put(
                                "message",
                                org.json
                                    .JSONObject()
                                    .put("role", "assistant")
                                    .put("content", content),
                            ),
                        ),
                    ).toString()
            val server =
                com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress(0), 0).apply {
                    createContext("/v1/chat/completions") { exchange ->
                        exchange.sendResponseHeaders(200, envelope.toByteArray().size.toLong())
                        exchange.responseBody.use { it.write(envelope.toByteArray()) }
                    }
                    start()
                }
            try {
                val provider = AiProvider(id = "test", name = "Test", baseUrl = "http://localhost:${server.address.port}/v1", apiKey = "k")
                val result = AiClient().chatCompletion(provider, "m", "system", "user")
                assertThat(result, equalTo(content))
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun `falls back to body when no error message`() {
        assertThat(AiClient.extractErrorMessage("gateway timeout"), equalTo("gateway timeout"))
    }
}
