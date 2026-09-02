// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.RobolectricTest
import kotlinx.coroutines.test.runTest
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
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
            transform = { """{"question": "$it", "answer": "A"}""" },
        )

    @Test
    fun `retries until the requested number of cards is reached`() =
        runTest {
            val client = ScriptedClient(listOf(cardsJson("Q1"), cardsJson("Q2", "Q3")))
            val cards = FlashcardGenerator(client).generate(provider, "m", "material", 3)
            assertThat(cards, hasSize(3))
            assertThat(cards.map { it.front }, equalTo(listOf("Q1", "Q2", "Q3")))
            assertThat(client.calls, equalTo(2))
        }

    @Test
    fun `duplicate questions across attempts are deduplicated`() =
        runTest {
            val client = ScriptedClient(listOf(cardsJson("Q1"), cardsJson("Q1", "Q2")))
            val cards = FlashcardGenerator(client).generate(provider, "m", "material", 3)
            assertThat(cards.map { it.front }, equalTo(listOf("Q1", "Q2")))
        }

    @Test
    fun `single attempt when the first response already satisfies the count`() =
        runTest {
            val client = ScriptedClient(listOf(cardsJson("Q1", "Q2")))
            val cards = FlashcardGenerator(client).generate(provider, "m", "material", 2)
            assertThat(cards, hasSize(2))
            assertThat(client.calls, equalTo(1))
        }

    @Test
    fun `auto count makes a single attempt and returns everything parsed`() =
        runTest {
            val client = ScriptedClient(listOf(cardsJson("Q1", "Q2", "Q3")))
            val cards = FlashcardGenerator(client).generate(provider, "m", "material", FlashcardGenerator.COUNT_AUTO)
            assertThat(cards, hasSize(3))
            assertThat(client.calls, equalTo(1))
        }
}
