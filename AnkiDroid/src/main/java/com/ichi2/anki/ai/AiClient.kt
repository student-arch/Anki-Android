// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ai

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * A minimal client for OpenAI-compatible AI provider APIs (`/models`, `/chat/completions`).
 *
 * Most providers (OpenAI, OpenRouter, Groq, DeepSeek, Mistral, Together, LM Studio, Ollama, ...)
 * expose these endpoints, so supporting additional providers only requires the user to enter
 * a base URL and an API key.
 */
open class AiClient(
    private val okHttpClient: OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build(),
) {
    /**
     * Fetches the models offered by [provider], e.g. in response to a test of the configuration
     * or to populate the model selection UI.
     *
     * @throws AiException if the request fails or the response cannot be parsed
     */
    open suspend fun listModels(provider: AiProvider): List<String> {
        val url = requireValidUrl(provider, path = "models")
        val request =
            Request
                .Builder()
                .url(url)
                .header("Authorization", "Bearer ${provider.apiKey}")
                .get()
                .build()
        return try {
            parseModelIds(execute("listing models", provider, request))
        } catch (e: AiException) {
            // Cloudflare AI's OpenAI-compatible endpoint does not support GET /models (HTTP 405);
            // fall back to its model catalog endpoint, which lists every available model.
            val catalogUrl = cloudflareCatalogUrl(provider.baseUrl)
            if (catalogUrl == null) throw e
            Timber.i("GET /models unsupported (%s); using Cloudflare model catalog", e.message)
            val catalogRequest =
                Request
                    .Builder()
                    .url(catalogUrl)
                    .header("Authorization", "Bearer ${provider.apiKey}")
                    .get()
                    .build()
            parseCatalogModels(execute("listing models", provider, catalogRequest))
        }
    }

    /**
     * @return the Cloudflare model-catalog URL for [baseUrl] (e.g. `.../ai/v1` ->
     * `.../ai/models/search`), or null if [baseUrl] is not a Cloudflare AI endpoint.
     */
    private fun cloudflareCatalogUrl(baseUrl: String): HttpUrl? {
        val marker = "/ai/v1"
        val index = baseUrl.indexOf(marker)
        if (index < 0 || !baseUrl.contains("cloudflare.com")) return null
        val catalog = baseUrl.substring(0, index + "/ai".length) + "/models/search?per_page=1000"
        return catalog.toHttpUrlOrNull()
    }

    /** Parses Cloudflare's `/ai/models/search` response: `result[].name`. */
    internal fun parseCatalogModels(body: String): List<String> {
        val result =
            try {
                JSONObject(body).optJSONArray("result") ?: JSONArray()
            } catch (e: JSONException) {
                throw AiException.Parse("could not parse the provider's model list", e)
            }
        // The full provider catalog is offered unfiltered: the user picks any model for any
        // task, and a mismatched pick surfaces the provider's own error message.
        val names = ArrayList<String>(result.length())
        for (i in 0 until result.length()) {
            val name = result.optJSONObject(i)?.optString("name").orEmpty()
            if (name.isNotEmpty()) names.add(name)
        }
        if (names.isEmpty()) throw AiException.Parse("the provider returned no models")
        return names
    }

    /**
     * Performs a chat completion and returns the assistant message text.
     *
     * @param jsonMode when true, `response_format: json_object` is requested; if the provider
     * rejects it, the request is retried without it.
     * @throws AiException if the request fails or the response cannot be parsed
     */
    open suspend fun chatCompletion(
        provider: AiProvider,
        modelId: String,
        systemPrompt: String,
        userPrompt: String,
        jsonMode: Boolean = true,
    ): String {
        val url = requireValidUrl(provider, path = "chat/completions")
        val payload =
            JSONObject()
                .put("model", modelId)
                .put(
                    "messages",
                    JSONArray()
                        .put(JSONObject().put("role", "system").put("content", systemPrompt))
                        .put(JSONObject().put("role", "user").put("content", userPrompt)),
                ).put("temperature", 0.3)
        // Without an explicit budget, reasoning models (e.g. gpt-oss) spend the server default
        // on hidden reasoning and return null content with finish_reason=length.
        payload.put("max_tokens", MAX_COMPLETION_TOKENS)
        if (jsonMode) {
            payload.put("response_format", JSONObject().put("type", "json_object"))
        }
        val request =
            Request
                .Builder()
                .url(url)
                .header("Authorization", "Bearer ${provider.apiKey}")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
        return try {
            parseCompletionMessage(execute("generating", provider, request))
        } catch (e: AiException.BadRequest) {
            if (!jsonMode) throw e
            // Some OpenAI-compatible providers do not support `response_format`; retry without it
            Timber.i("json_mode rejected by provider, retrying without it")
            chatCompletion(provider, modelId, systemPrompt, userPrompt, jsonMode = false)
        }
    }

    /**
     * Downloads [imageUrl] and returns its bytes, for storing into collection.media.
     *
     * Only image content types are accepted, with a size cap to avoid huge downloads.
     *
     * @throws AiException if the request fails, the content type is not an image or the
     * response exceeds [MAX_IMAGE_BYTES]
     */
    open suspend fun downloadImage(imageUrl: String): ByteArray {
        val url = imageUrl.toHttpUrlOrNull() ?: throw AiException.Configuration("invalid image URL: $imageUrl")
        val request =
            Request
                .Builder()
                .url(url)
                .get()
                .build()
        return withContext(Dispatchers.IO) {
            val response: Response =
                try {
                    okHttpClient.newCall(request).execute()
                } catch (e: IOException) {
                    Timber.w(e, "image download failed for %s", imageUrl)
                    throw AiException.Network(e.message ?: "network error", e)
                }
            response.use {
                if (!it.isSuccessful) {
                    Timber.w("image download failed: HTTP %d for %s", it.code, imageUrl)
                    throw AiException.Server("HTTP ${it.code} downloading image")
                }
                val contentType = it.header("Content-Type").orEmpty()
                if (!contentType.startsWith("image/")) {
                    throw AiException.Parse("not an image (Content-Type: $contentType)")
                }
                val bytes = it.body.byteStream().use { stream -> stream.readBytes() }
                if (bytes.size > MAX_IMAGE_BYTES) {
                    throw AiException.Parse("image too large (${bytes.size} bytes)")
                }
                bytes
            }
        }
    }

    /**
     * Generates an image for [prompt] with [modelId] via the OpenAI-compatible
     * `images/generations` endpoint and @return the decoded image bytes.
     *
     * Prefers `response_format: b64_json`; providers that reject it are retried without it
     * (they return b64 by default or a URL, which is then downloaded).
     *
     * @throws AiException if the request fails or the response cannot be parsed
     */
    open suspend fun generateImage(
        provider: AiProvider,
        modelId: String,
        prompt: String,
    ): ByteArray {
        val fullPrompt = prompt + IMAGE_STYLE_SUFFIX
        // Cloudflare AI exposes image models via POST /ai/run/<model> (result.image = base64),
        // not the OpenAI /images/generations route.
        val runUrl = cloudflareRunUrl(provider.baseUrl, modelId)
        if (runUrl != null) {
            val request =
                Request
                    .Builder()
                    .url(runUrl)
                    .header("Authorization", "Bearer ${provider.apiKey}")
                    .post(JSONObject().put("prompt", fullPrompt).toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            return parseRunImage(execute("generating image", provider, request))
        }
        val url = requireValidUrl(provider, path = "images/generations")
        // No `response_format`: gpt-image returns b64_json by default and DALL·E returns a URL
        // (downloaded in parseImageData); sending response_format=b64_json makes gpt-image 400.
        val payload =
            JSONObject()
                .put("model", modelId)
                .put("prompt", fullPrompt)
                .put("n", 1)
                .put("size", "1024x1024")
        val request =
            Request
                .Builder()
                .url(url)
                .header("Authorization", "Bearer ${provider.apiKey}")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
        return parseImageData(execute("generating image", provider, request))
    }

    /**
     * @return the Cloudflare Workers AI run URL for [modelId] (e.g. `.../ai/v1` +
     * `@cf/.../flux-1-schnell` -> `.../ai/run/@cf/.../flux-1-schnell`), or null if [baseUrl]
     * is not a Cloudflare AI endpoint.
     */
    private fun cloudflareRunUrl(
        baseUrl: String,
        modelId: String,
    ): HttpUrl? {
        val marker = "/ai/v1"
        val index = baseUrl.indexOf(marker)
        if (index < 0 || !baseUrl.contains("cloudflare.com")) return null
        val run = baseUrl.substring(0, index + "/ai".length) + "/run/" + modelId
        return run.toHttpUrlOrNull()
    }

    /** Parses a Cloudflare `/ai/run` image response: `result.image` (base64). */
    private fun parseRunImage(body: String): ByteArray {
        val image =
            try {
                JSONObject(body).optJSONObject("result")?.optString("image").orEmpty()
            } catch (e: JSONException) {
                throw AiException.Parse("could not parse the image generation response", e)
            }
        if (image.isEmpty() || image == "null") throw AiException.Parse("the provider returned no image")
        return Base64.decode(image, Base64.DEFAULT)
    }

    /** Parses an `images/generations` response: `data[0]` is either `b64_json` or a `url`. */
    private fun parseImageData(body: String): ByteArray {
        val data =
            try {
                JSONObject(body).optJSONArray("data")
            } catch (e: JSONException) {
                throw AiException.Parse("could not parse the image generation response", e)
            }
        val first = data?.optJSONObject(0) ?: throw AiException.Parse("the provider returned no image")
        val b64 = first.optString("b64_json")
        return when {
            !b64.isNullOrEmpty() && b64 != "null" ->
                Base64.decode(b64, Base64.DEFAULT)
            else -> {
                val imageUrl = first.optString("url")
                if (imageUrl.isNullOrEmpty() || imageUrl == "null") {
                    throw AiException.Parse("the provider returned no image data")
                }
                runBlocking { downloadImage(imageUrl) }
            }
        }.also { bytes ->
            if (bytes.size > MAX_IMAGE_BYTES) throw AiException.Parse("image too large (${bytes.size} bytes)")
        }
    }

    private suspend fun execute(
        action: String,
        provider: AiProvider,
        request: Request,
    ): String =
        // OkHttp calls are blocking: always leave the caller's dispatcher (often Main) for I/O
        withContext(Dispatchers.IO) {
            val response: Response =
                try {
                    okHttpClient.newCall(request).execute()
                } catch (e: IOException) {
                    Timber.w(e, "AI request failed (%s) for %s", action, provider.name)
                    throw AiException.Network(e.message ?: "network error", e)
                }
            response.use {
                val body = it.body.string()
                if (!it.isSuccessful) {
                    Timber.w("AI request failed (%s): HTTP %d", action, it.code)
                    throw httpError(it.code, body)
                }
                body
            }
        }

    private fun requireValidUrl(
        provider: AiProvider,
        path: String,
    ): HttpUrl = buildApiUrl(provider.baseUrl, path) ?: throw AiException.Configuration("invalid base URL: ${provider.baseUrl}")

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val CONNECT_TIMEOUT_SECONDS = 20L
        private const val READ_TIMEOUT_SECONDS = 120L

        /** Generous completion budget: room for reasoning models' hidden reasoning + the answer. */
        private const val MAX_COMPLETION_TOKENS = 4096

        /**
         * Resolves [path] against [baseUrl], treating the base URL's last segment as a directory.
         *
         * `HttpUrl.resolve` alone would drop the base path's last segment (e.g. resolving
         * `chat/completions` against `https://api.openai.com/v1` yields `.../chat/completions`
         * without `/v1`), so a trailing slash is appended first when missing.
         *
         * @return the resolved URL, or null if [baseUrl] is not a valid HTTP(S) URL
         */
        fun buildApiUrl(
            baseUrl: String,
            path: String,
        ): HttpUrl? {
            val base = baseUrl.toHttpUrlOrNull() ?: return null
            val withSlash = if (base.encodedPath.endsWith("/")) base else base.newBuilder().addPathSegment("").build()
            return withSlash.resolve(path)
        }

        /** Normalizes a user-entered base URL: trims whitespace and trailing slashes. */
        fun normalizeBaseUrl(baseUrl: String): String = baseUrl.trim().trimEnd('/')

        private fun httpError(
            code: Int,
            body: String,
        ): AiException =
            when (code) {
                in 400..499 -> AiException.BadRequest("HTTP $code: ${extractErrorMessage(body)}")
                else -> AiException.Server("HTTP $code: ${extractErrorMessage(body)}")
            }

        /** Extracts the provider's error message, if the body contains one. */
        fun extractErrorMessage(body: String): String =
            try {
                val message = JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
                when {
                    message.isEmpty() || message == "null" -> body.take(MAX_ERROR_MESSAGE_LENGTH)
                    else -> message.take(MAX_ERROR_MESSAGE_LENGTH)
                }
            } catch (e: JSONException) {
                body.take(MAX_ERROR_MESSAGE_LENGTH)
            }

        private const val MAX_ERROR_MESSAGE_LENGTH = 200

        /** Maximum accepted size of a downloaded image, in bytes. */
        internal const val MAX_IMAGE_BYTES = 5 * 1024 * 1024

        /**
         * Appended to every image prompt so all generated images share one clear, consistent
         * educational look (square framing, centered subject, readable labels, no cropping).
         */
        private const val IMAGE_STYLE_SUFFIX =
            "\n\nStyle: clean flat vector educational diagram, centered single subject with " +
                "generous even margins so nothing is cut off, square 1:1 composition, plain " +
                "white background, high contrast, simple bold shapes, short legible text labels, " +
                "no photorealism, no clutter, no borders or watermarks."

        /** Parses the `data[].id` array of an OpenAI-compatible `/models` response. */
        fun parseModelIds(body: String): List<String> {
            val ids =
                try {
                    val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
                    List(data.length()) { index -> data.getJSONObject(index).optString("id") }.filter { it.isNotEmpty() }
                } catch (e: JSONException) {
                    Timber.w(e, "failed to parse models response")
                    throw AiException.Parse("could not parse the provider's model list")
                }
            if (ids.isEmpty()) {
                throw AiException.Parse("the provider returned no models")
            }
            return ids
        }

        /** Extracts the message content of an OpenAI-compatible `/chat/completions` response. */
        fun parseCompletionMessage(body: String): String =
            try {
                val choices = JSONObject(body).optJSONArray("choices")
                if (choices == null || choices.length() == 0) {
                    throw AiException.Parse("the provider returned no completion choices")
                }
                val choice = choices.getJSONObject(0)
                val message = choice.optJSONObject("message")
                val finishReason = choice.optString("finish_reason")
                val refusal = message?.optString("refusal").orEmpty()
                if (refusal.isNotEmpty() && refusal != "null") {
                    throw AiException.Parse("the model refused the request: $refusal")
                }
                val raw = message?.opt("content")
                val content =
                    when (raw) {
                        is String -> raw
                        // Some models (e.g. qwen2.5-coder) return structured content as a JSON
                        // object instead of a string; serialize it so downstream JSON parsing
                        // still receives valid JSON.
                        is JSONObject, is JSONArray -> raw.toString()
                        else -> raw?.toString().orEmpty()
                    }
                if (content.isEmpty() || content == "null") {
                    val hint =
                        when (finishReason) {
                            "length" -> "the output was truncated (token limit) — try a different model"
                            "content_filter", "safety" -> "the request was filtered by the provider — try different material"
                            "tool_calls" -> "the model returned a tool call instead of content — not a chat model"
                            "stop", "" -> "the model returned no content — it may not be a chat model, or refused silently"
                            else -> "finish_reason: $finishReason"
                        }
                    throw AiException.Parse("the provider returned an empty completion ($hint)")
                }
                content
            } catch (e: JSONException) {
                Timber.w(e, "failed to parse completion response")
                throw AiException.Parse("could not parse the provider's response")
            }
    }
}
