// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ai

import timber.log.Timber

/**
 * Generates flashcards from user-provided learning material using a configured provider.
 *
 * Open so tests can provide a fake implementation.
 */
open class FlashcardGenerator(
    private val client: AiClient,
) {
    /**
     * Asks [provider]/[modelId] to produce flashcards for [material].
     *
     * @param count the requested number of flashcards
     * @return the generated flashcards; empty if the model returned no usable cards
     * @throws AiException if the request fails
     */
    open suspend fun generate(
        provider: AiProvider,
        modelId: String,
        material: String,
        count: Int,
    ): List<GeneratedFlashcard> {
        Timber.i("generating %d flashcards with %s/%s", count, provider.name, modelId)
        val response =
            client.chatCompletion(
                provider = provider,
                modelId = modelId,
                systemPrompt = SYSTEM_PROMPT,
                userPrompt = buildUserPrompt(material, count),
            )
        val cards = FlashcardParser.parse(response)
        if (cards.isEmpty()) {
            Timber.w("no flashcards could be parsed from the response")
        }
        return cards
    }

    companion object {
        private const val SYSTEM_PROMPT =
            "You are a flashcard generation assistant for Anki, the spaced repetition " +
                "flashcard program. The user provides a concept, topic or learning material. " +
                "You analyze it and produce high-quality flashcards that actively test " +
                "recall of its key facts and concepts. Each card must be atomic: test exactly " +
                "one fact. Do not number the cards. Do not use line breaks inside a field."

        private fun buildUserPrompt(
            material: String,
            count: Int,
        ): String =
            "Generate exactly $count flashcards from the following material.\n" +
                "Respond with JSON only, in this exact format:\n" +
                "{\"cards\": [{\"front\": \"question or cue\", \"back\": \"answer\"}]}\n\n" +
                "Material:\n$material"
    }
}
