// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ai

/**
 * Errors surfaced to the user by the AI flashcard generation feature.
 *
 * The [message] is user-facing; it must never include the provider API key.
 */
sealed class AiException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** The provider configuration is invalid (e.g. malformed base URL). */
    class Configuration(
        message: String,
        cause: Throwable? = null,
    ) : AiException(message, cause)

    /** The request could not reach the provider. */
    class Network(
        message: String,
        cause: Throwable? = null,
    ) : AiException(message, cause)

    /** The provider rejected the request (HTTP 4xx, e.g. an invalid API key). */
    class BadRequest(
        message: String,
    ) : AiException(message)

    /** The provider failed server-side (HTTP 5xx). */
    class Server(
        message: String,
    ) : AiException(message)

    /** The provider's response could not be parsed. */
    class Parse(
        message: String,
        cause: Throwable? = null,
    ) : AiException(message, cause)
}
