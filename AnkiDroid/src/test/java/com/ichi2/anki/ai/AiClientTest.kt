// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.RobolectricTest
import kotlinx.coroutines.runBlocking
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.equalTo
import org.json.JSONObject
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
    fun `empty completion with finish_reason length explains truncation`() {
        val body = """{"choices": [{"finish_reason": "length", "message": {"content": null}}]}"""
        val ex =
            assertThrows(AiException.Parse::class.java) { AiClient.parseCompletionMessage(body) }
        assertThat(ex.message!!, org.hamcrest.Matchers.containsString("token limit"))
    }

    @Test
    fun `empty completion with content null falls back to default hint`() {
        val body = """{"choices": [{"finish_reason": "stop", "message": {"content": null}}]}"""
        val ex =
            assertThrows(AiException.Parse::class.java) { AiClient.parseCompletionMessage(body) }
        assertThat(ex.message!!, org.hamcrest.Matchers.containsString("empty completion"))
    }

    @Test
    fun `refusal is surfaced as a parse error`() {
        val body = """{"choices": [{"finish_reason": "stop", "message": {"refusal": "unsafe content"}}]}"""
        val ex =
            assertThrows(AiException.Parse::class.java) { AiClient.parseCompletionMessage(body) }
        assertThat(ex.message!!, org.hamcrest.Matchers.containsString("refused"))
    }

    @Test
    fun `content returned as a JSON object is serialized to string`() {
        val body = """{"choices": [{"message": {"content": {"cards": [{"q": "a"}]}}}]}"""
        val parsed = AiClient.parseCompletionMessage(body)
        assertThat(parsed, equalTo("""{"cards":[{"q":"a"}]}"""))
    }

    @Test
    fun `Cloudflare catalog returns the complete unfiltered model list`() {
        val body =
            """
            {"result": [
              {"name": "@cf/meta/llama-3.3-70b-instruct-fp8-fast", "task": {"name": "Text Generation"}},
              {"name": "@cf/black-forest-labs/flux-1-schnell", "task": {"name": "Text-to-Image"}},
              {"name": "@cf/baai/bge-large-en-v1.5", "task": {"name": "Embeddings"}},
              {"name": "@cf/openai/gpt-oss-120b", "task": {"name": "Text Generation"}}
            ]}
            """.trimIndent()
        val names = AiClient().parseCatalogModels(body)
        assertThat(
            names,
            containsInAnyOrder(
                "@cf/meta/llama-3.3-70b-instruct-fp8-fast",
                "@cf/black-forest-labs/flux-1-schnell",
                "@cf/baai/bge-large-en-v1.5",
                "@cf/openai/gpt-oss-120b",
            ),
        )
    }

    @Test
    fun `Cloudflare catalog with no models throws`() {
        val body = """{"result": []}"""
        assertThrows(AiException.Parse::class.java) { AiClient().parseCatalogModels(body) }
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

    /**
     * A local OpenAI-compatible server capturing the request AiClient sends, and replying with
     * the given [responseBody] so tests can assert on both sides of the conversation.
     */
    private class CapturingServer(
        private val responseBody: String = """{"choices": [{"message": {"content": "ok"}}]}""",
    ) {
        val requests = mutableListOf<JSONObject>()
        val server =
            com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress(0), 0).apply {
                createContext("/v1/chat/completions") { exchange ->
                    requests.add(JSONObject(exchange.requestBody.readBytes().decodeToString()))
                    exchange.sendResponseHeaders(200, responseBody.toByteArray().size.toLong())
                    exchange.responseBody.use { it.write(responseBody.toByteArray()) }
                }
                start()
            }

        fun stop() = server.stop(0)
    }

    @Test
    fun `completion request sends a budget large enough for reasoning models`() =
        runBlocking {
            // glm-5.3-free on Token Router spent 3703 of 4096 tokens on hidden reasoning and
            // truncated the JSON (finish_reason=length); reasoning models need a much larger
            // budget than the visible content alone
            val server = CapturingServer()
            try {
                AiClient().chatCompletion(
                    AiProvider(id = "t", name = "T", baseUrl = "http://localhost:${server.server.address.port}/v1", apiKey = "k"),
                    "z-ai/glm-5.3-free",
                    "system",
                    "user",
                )
                assertThat(server.requests.single().getInt("max_tokens"), equalTo(AiClient.MAX_COMPLETION_TOKENS))
                assertThat(AiClient.MAX_COMPLETION_TOKENS >= 16384, equalTo(true))
            } finally {
                server.stop()
            }
        }

    @Test
    fun `read timeout is long enough for slow reasoning models`() {
        // a full generation with glm-5.3-free measured over 4 minutes until the final token;
        // the previous 120s read timeout aborted such requests mid-flight
        assertThat(AiClient.READ_TIMEOUT_SECONDS >= 480, equalTo(true))
    }
}
