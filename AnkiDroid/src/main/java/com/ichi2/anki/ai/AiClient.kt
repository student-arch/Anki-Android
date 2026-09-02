// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ai

import kotlinx.coroutines.Dispatchers
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
        val body = execute("listing models", provider, request)
        return parseModelIds(body)
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
                val content =
                    choices
                        .getJSONObject(0)
                        .optJSONObject("message")
                        ?.optString("content")
                        .orEmpty()
                if (content.isEmpty() || content == "null") {
                    throw AiException.Parse("the provider returned an empty completion")
                }
                content
            } catch (e: JSONException) {
                Timber.w(e, "failed to parse completion response")
                throw AiException.Parse("could not parse the provider's response")
            }
    }
}
