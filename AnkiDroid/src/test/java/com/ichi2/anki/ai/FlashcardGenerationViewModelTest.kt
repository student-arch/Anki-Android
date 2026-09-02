// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ai

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.ai.FlashcardGenerationFragment.Companion.DEFAULT_CARD_COUNT
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.instanceOf
import org.hamcrest.Matchers.`is`
import org.hamcrest.Matchers.not
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertIs

/**
 * End-to-end tests of flashcard generation and of adding only the accepted cards to a deck.
 */
@RunWith(AndroidJUnit4::class)
class FlashcardGenerationViewModelTest : RobolectricTest() {
    private lateinit var viewModel: FlashcardGenerationViewModel

    /** Always returns [count] fixed cards, without any network access. */
    private class FakeGenerator : FlashcardGenerator(AiClient()) {
        override suspend fun generate(
            provider: AiProvider,
            modelId: String,
            material: String,
            count: Int,
        ): List<GeneratedFlashcard> = List(count) { index -> GeneratedFlashcard(front = "Front $index", back = "Back $index") }
    }

    @Before
    fun setUpViewModel() {
        configureProvider()
        viewModel = FlashcardGenerationViewModel(ApplicationProvider.getApplicationContext(), FakeGenerator())
    }

    @After
    fun tearDownStore() {
        AiProviderStore.resetForTests()
    }

    private fun configureProvider() {
        val store = AiProviderStore.getInstance(targetContext)
        runBlocking {
            store.setProviders(
                listOf(
                    AiProvider(
                        id = "test-provider",
                        name = "Test Provider",
                        baseUrl = "https://test.example.com/v1",
                        apiKey = "test-key",
                        modelIds = listOf("test-model"),
                    ),
                ),
            )
            store.setSelection(AiTaskType.FLASHCARD, "test-provider", "test-model")
        }
    }

    @Test
    fun `viewModel is created by the default factory used by the fragment`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val created = ViewModelProvider.AndroidViewModelFactory.getInstance(app).create(FlashcardGenerationViewModel::class.java)
        assertThat(created.isConfigured, `is`(true))
    }

    @Test
    fun `generate produces cards for review`() =
        runTest {
            viewModel.generate("photosynthesis", DEFAULT_CARD_COUNT)
            RobolectricTest.advanceRobolectricLooperUntil(condition = { viewModel.uiState.value is GenerationUiState.Reviewing })
            val state = viewModel.uiState.value
            assertIs<GenerationUiState.Reviewing>(state)
            assertThat(state.cards, hasSize(DEFAULT_CARD_COUNT))
            assertThat(viewModel.acceptedIds.value, hasSize(0))
        }

    @Test
    fun `generate without configuration reports an error`() =
        runTest {
            AiProviderStore.getInstance(targetContext).clearAll()
            val unconfiguredViewModel =
                FlashcardGenerationViewModel(ApplicationProvider.getApplicationContext(), FakeGenerator())
            unconfiguredViewModel.generate("photosynthesis", 5)
            assertThat(unconfiguredViewModel.uiState.value, instanceOf(GenerationUiState.Input::class.java))
            assertThat(unconfiguredViewModel.error.value, instanceOf(GenerationError::class.java))
        }

    @Test
    fun `accepted cards are added to a new deck`() =
        runTest {
            val cards =
                listOf(
                    GeneratedFlashcard(front = "Q1", back = "A1"),
                    GeneratedFlashcard(front = "Q2", back = "A2"),
                    GeneratedFlashcard(front = "Q3", back = "A3"),
                )
            viewModel.updateCards(cards)
            viewModel.toggleAccepted(cards[0].id, true)
            viewModel.toggleAccepted(cards[2].id, true)

            viewModel.addAcceptedCards(viewModel.acceptedCards(), deckId = null, createDeck = true, newDeckName = "AI Deck")
            RobolectricTest.advanceRobolectricLooperUntil(condition = { viewModel.uiState.value is GenerationUiState.Done })

            val state = viewModel.uiState.value
            assertIs<GenerationUiState.Done>(state)
            assertThat(state.addedCount, equalTo(2))
            assertThat(state.deckName, equalTo("AI Deck"))

            val noteIds = withCol { findNotes("deck:\"AI Deck\"") }
            assertThat(noteIds, hasSize(2))
            val fields = withCol { noteIds.map { nid -> getNote(nid).fields.toList() } }
            assertThat(fields, containsInAnyOrder(listOf("Q1", "A1"), listOf("Q3", "A3")))
        }

    @Test
    fun `accepted cards are added to an existing deck`() =
        runTest {
            val targetDeckId = withCol { decks.id("Target") }
            val cards = listOf(GeneratedFlashcard(front = "Q1", back = "A1"))
            viewModel.updateCards(cards)
            viewModel.toggleAccepted(cards[0].id, true)

            viewModel.addAcceptedCards(viewModel.acceptedCards(), deckId = targetDeckId, createDeck = false, newDeckName = null)
            RobolectricTest.advanceRobolectricLooperUntil(condition = { viewModel.uiState.value is GenerationUiState.Done })

            val noteIds = withCol { findNotes("deck:\"Target\"") }
            assertThat(noteIds, hasSize(1))
            val state = viewModel.uiState.value
            assertIs<GenerationUiState.Done>(state)
            assertThat(state.deckName, equalTo("Target"))
        }

    @Test
    fun `rejected cards are never added`() =
        runTest {
            val cards = listOf(GeneratedFlashcard(front = "Q1", back = "A1"))
            viewModel.updateCards(cards)
            // no card accepted

            viewModel.addAcceptedCards(viewModel.acceptedCards(), deckId = null, createDeck = true, newDeckName = "Empty")

            // nothing accepted -> nothing enqueued, state unchanged
            assertThat(viewModel.uiState.value, instanceOf(GenerationUiState.Reviewing::class.java))
            val deckNames = withCol { decks.allNamesAndIds().map { it.name } }
            assertThat(deckNames, not(`is`(containsInAnyOrder("Empty"))))
        }

    @Test
    fun `editing a card updates its content in place`() {
        val cards = listOf(GeneratedFlashcard(front = "Q1", back = "A1"))
        viewModel.updateCards(cards)
        viewModel.updateCard(cards[0].id, "Edited Q", "Edited A")
        val state = viewModel.uiState.value
        assertIs<GenerationUiState.Reviewing>(state)
        assertThat(state.cards[0].front, equalTo("Edited Q"))
        assertThat(state.cards[0].back, equalTo("Edited A"))
        assertThat(state.cards, hasSize(1))
    }

    @Test
    fun `select all and deselect all update the accepted set`() =
        runTest {
            val cards =
                listOf(
                    GeneratedFlashcard(front = "Q1", back = "A1"),
                    GeneratedFlashcard(front = "Q2", back = "A2"),
                )
            viewModel.updateCards(cards)
            viewModel.acceptAll()
            assertThat(viewModel.acceptedCards().size, equalTo(2))
            viewModel.acceptNone()
            assertThat(viewModel.acceptedCards(), hasSize(0))
        }
}
