// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ai

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.R
import com.ichi2.anki.libanki.DeckId
import com.ichi2.anki.libanki.Note
import com.ichi2.anki.model.SelectableDeck
import com.ichi2.anki.observability.undoableOp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * State machine for the flashcard generation screen:
 * input -> generating -> reviewing -> adding -> done.
 */
sealed class GenerationUiState {
    /** The user is entering material and generation options. */
    data object Input : GenerationUiState()

    /** A generation request is in flight. */
    data object Generating : GenerationUiState()

    /** Generation produced [cards]; the user reviews, edits and accepts them. */
    data class Reviewing(
        val cards: List<GeneratedFlashcard>,
    ) : GenerationUiState()

    /** Accepted cards are being written to the collection. */
    data object Adding : GenerationUiState()

    /** [addedCount] cards were added to [deckName]. */
    data class Done(
        val addedCount: Int,
        val deckName: String,
    ) : GenerationUiState()
}

/** An error that aborts the flow and returns the user to the input step. */
data class GenerationError(
    val message: String,
)

/**
 * ViewModel for the flashcard generation screen: generates flashcards from user-provided
 * material via the configured AI provider, and adds the accepted ones to a deck.
 */
class FlashcardGenerationViewModel
    @JvmOverloads
    constructor(
        application: Application,
        private val generator: FlashcardGenerator = FlashcardGenerator(AiClient()),
    ) : AndroidViewModel(application) {
        private val providerStore: AiProviderStore = AiProviderStore.getInstance(application)

        private val _uiState = MutableStateFlow<GenerationUiState>(GenerationUiState.Input)
        val uiState: StateFlow<GenerationUiState> = _uiState

        private val _error = MutableStateFlow<GenerationError?>(null)
        val error: StateFlow<GenerationError?> = _error

        private val _acceptedIds = MutableStateFlow<Set<Long>>(emptySet())
        val acceptedIds: StateFlow<Set<Long>> = _acceptedIds

        /** The deck chosen for adding the accepted cards (when using an existing deck). */
        var selectedDeck: SelectableDeck.Deck? = null

        /** Whether a flashcard provider/model pair is configured; false shows the setup hint. */
        val isConfigured: Boolean
            get() = providerStore.getSelection(AiTaskType.FLASHCARD) != null

        /** @return the cards currently under review, or empty if not in the review step. */
        fun currentCards(): List<GeneratedFlashcard> = (_uiState.value as? GenerationUiState.Reviewing)?.cards.orEmpty()

        /** @return the accepted cards, in display order. */
        fun acceptedCards(): List<GeneratedFlashcard> = currentCards().filter { it.id in _acceptedIds.value }

        fun toggleAccepted(
            cardId: Long,
            isAccepted: Boolean,
        ) {
            _acceptedIds.value =
                if (isAccepted) _acceptedIds.value + cardId else _acceptedIds.value - cardId
        }

        fun acceptAll() {
            _acceptedIds.value = currentCards().map { it.id }.toSet()
        }

        fun acceptNone() {
            _acceptedIds.value = emptySet()
        }

        /**
         * Requests flashcard generation for [material].
         *
         * On success the state becomes [GenerationUiState.Reviewing]; on failure an [error] is set
         * and the state returns to [GenerationUiState.Input].
         */
        fun generate(
            material: String,
            count: Int,
        ) {
            val selection = providerStore.getSelection(AiTaskType.FLASHCARD)
            val provider = providerStore.getProvider(selection?.providerId)
            if (selection == null || provider == null) {
                _error.value = GenerationError(getApplication<Application>().getString(R.string.ai_error_not_configured))
                return
            }
            _uiState.value = GenerationUiState.Generating
            viewModelScope.launch {
                try {
                    // AiClient performs its I/O on Dispatchers.IO internally
                    val cards =
                        generator.generate(
                            provider = provider,
                            modelId = selection.modelId,
                            material = material,
                            count = count,
                        )
                    if (cards.isEmpty()) {
                        _error.value = GenerationError(getApplication<Application>().getString(R.string.ai_error_no_cards))
                        _uiState.value = GenerationUiState.Input
                    } else {
                        _acceptedIds.value = emptySet()
                        _uiState.value = GenerationUiState.Reviewing(cards)
                    }
                } catch (e: AiException) {
                    Timber.w(e, "flashcard generation failed")
                    _error.value = GenerationError(e.message ?: getApplication<Application>().getString(R.string.ai_error_generic))
                    _uiState.value = GenerationUiState.Input
                }
            }
        }

        /** Returns to the input step, discarding the generated cards. */
        fun backToInput() {
            _acceptedIds.value = emptySet()
            _uiState.value = GenerationUiState.Input
        }

        /** Replaces the cards under review (e.g. after a rejection), dropping rejected selections. */
        fun updateCards(cards: List<GeneratedFlashcard>) {
            val keptIds = cards.map { it.id }.toSet()
            _acceptedIds.value = _acceptedIds.value intersect keptIds
            _uiState.value = GenerationUiState.Reviewing(cards)
        }

        /** Applies an edit to the card with [cardId], keeping it in the same position. */
        fun updateCard(
            cardId: Long,
            front: String,
            back: String,
        ) {
            val current = _uiState.value as? GenerationUiState.Reviewing ?: return
            _uiState.value =
                GenerationUiState.Reviewing(
                    current.cards.map { card ->
                        if (card.id == cardId) card.copy(front = front, back = back) else card
                    },
                )
        }

        fun clearError() {
            _error.value = null
        }

        /**
         * Adds the cards the user accepted to deck [deckId] (creating a deck named [newDeckName]
         * first when [createDeck] is true), as a single undoable operation.
         *
         * Only accepted cards are added; each card becomes one note (front -> back).
         */
        fun addAcceptedCards(
            accepted: List<GeneratedFlashcard>,
            deckId: DeckId?,
            createDeck: Boolean,
            newDeckName: String?,
        ) {
            val cardsToAdd = accepted.filter { it.front.isNotBlank() && it.back.isNotBlank() }
            if (cardsToAdd.isEmpty()) return
            _uiState.value = GenerationUiState.Adding
            viewModelScope.launch {
                try {
                    val targetDeckId =
                        when {
                            createDeck && !newDeckName.isNullOrBlank() -> withCol { decks.id(newDeckName.trim()) }
                            deckId != null -> deckId
                            else -> withCol { decks.selected() }
                        }
                    var addedCount = 0
                    val notes = buildNotes(cardsToAdd)
                    undoableOp {
                        val results = notes.map { addNote(it, targetDeckId) }
                        addedCount = results.sumOf { it.count }
                        // last expression must be an OpChanges subtype for ChangeManager
                        results.last()
                    }
                    _uiState.value =
                        GenerationUiState.Done(addedCount = addedCount, deckName = withCol { decks.name(targetDeckId) })
                } catch (e: Exception) {
                    Timber.e(e, "failed to add generated cards")
                    _error.value =
                        GenerationError(getApplication<Application>().getString(R.string.ai_error_add_failed))
                    _uiState.value = GenerationUiState.Reviewing(accepted)
                }
            }
        }

        private suspend fun buildNotes(cards: List<GeneratedFlashcard>): List<Note> =
            withCol {
                val notetype =
                    notetypes.byName(BASIC_NOTETYPE)
                        ?: notetypes.all().firstOrNull { it.isStd && it.fieldsNames.size >= 2 }
                        ?: notetypes.all().first()
                cards.map { card ->
                    val note = newNote(notetype)
                    note.setField(0, card.front)
                    if (note.fields.size > 1) {
                        note.setField(1, card.back)
                    }
                    note
                }
            }

        companion object {
            private const val BASIC_NOTETYPE = "Basic"
        }
    }
