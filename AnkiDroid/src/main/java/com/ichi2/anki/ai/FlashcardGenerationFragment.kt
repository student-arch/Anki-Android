// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.R
import com.ichi2.anki.SingleFragmentActivity
import com.ichi2.anki.databinding.DialogEditGeneratedCardBinding
import com.ichi2.anki.databinding.FragmentFlashcardGenerationBinding
import com.ichi2.anki.dialogs.DeckSelectionDialog
import com.ichi2.anki.model.SelectableDeck
import com.ichi2.anki.snackbar.showSnackbar
import com.ichi2.anki.utils.ext.getParcelableCompat
import com.ichi2.utils.customView
import com.ichi2.utils.negativeButton
import com.ichi2.utils.positiveButton
import com.ichi2.utils.show
import com.ichi2.utils.title
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Screen that generates flashcards from user-provided material using the configured AI provider,
 * lets the user review, edit, accept or reject each card, then adds the accepted cards to an
 * existing or newly created deck.
 *
 * The screen has three steps: input (material + card count), review, and done. Selection state
 * lives in [FlashcardGenerationViewModel] so it survives configuration changes.
 */
class FlashcardGenerationFragment : Fragment(R.layout.fragment_flashcard_generation) {
    private val viewModel: FlashcardGenerationViewModel by viewModels()

    private var reviewAdapter: GeneratedCardsAdapter? = null

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        val binding = FragmentFlashcardGenerationBinding.bind(view)
        reviewAdapter =
            GeneratedCardsAdapter().apply {
                setCallbacks(
                    object : GeneratedCardsAdapter.Callbacks {
                        override fun onSelectionChanged(
                            card: GeneratedFlashcard,
                            isSelected: Boolean,
                        ) {
                            viewModel.toggleAccepted(card.id, isSelected)
                            updateAddButton(binding)
                        }

                        override fun onEdit(card: GeneratedFlashcard) {
                            showEditCardDialog(card)
                        }

                        override fun onReject(card: GeneratedFlashcard) {
                            val remaining = viewModel.currentCards().filter { it.id != card.id }
                            if (remaining.isEmpty()) {
                                viewModel.backToInput()
                            } else {
                                viewModel.updateCards(remaining)
                            }
                        }
                    },
                )
            }
        binding.cards.layoutManager = LinearLayoutManager(requireContext())
        binding.cards.adapter = reviewAdapter

        binding.toolbar.setNavigationOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        setupInputStep(binding)
        setupReviewStep(binding)
        setupDoneStep(binding)
        setupDeckSelectionListener()

        viewModel.uiState
            .flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
            .onEach { state -> render(binding, state) }
            .launchIn(viewLifecycleOwner.lifecycleScope)

        viewModel.error
            .flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
            .onEach { error ->
                if (error != null) {
                    showSnackbar(error.message)
                    viewModel.clearError()
                }
            }.launchIn(viewLifecycleOwner.lifecycleScope)
    }

    override fun onDestroyView() {
        reviewAdapter = null
        super.onDestroyView()
    }

    private fun setupInputStep(binding: FragmentFlashcardGenerationBinding) {
        binding.setupHint.setOnClickListener { ProviderConfigFragment.openFrom(requireContext()) }
        binding.generate.setOnClickListener {
            val text =
                binding.material.text
                    .toString()
                    .trim()
            if (text.isEmpty()) {
                showSnackbar(R.string.ai_error_empty_input)
                return@setOnClickListener
            }
            val requested =
                binding.count.text
                    .toString()
                    .toIntOrNull()
                    ?.coerceIn(MIN_CARDS, MAX_CARDS) ?: DEFAULT_CARD_COUNT
            viewModel.generate(text, requested)
        }
    }

    private fun setupReviewStep(binding: FragmentFlashcardGenerationBinding) {
        binding.selectAll.setOnClickListener {
            viewModel.acceptAll()
            renderReviewCards(binding)
        }
        binding.deselectAll.setOnClickListener {
            viewModel.acceptNone()
            renderReviewCards(binding)
        }
        binding.deckTargetGroup.setOnCheckedChangeListener { _, checkedId ->
            val isNewDeck = checkedId == R.id.new_deck_option
            binding.newDeckName.isVisible = isNewDeck
            binding.chooseDeck.isVisible = !isNewDeck
            binding.existingDeckLabel.isVisible = !isNewDeck
        }
        binding.chooseDeck.setOnClickListener {
            lifecycleScope.launch {
                val decks = withCol { decks.allNamesAndIds().map { SelectableDeck.Deck(it) } }
                DeckSelectionDialog
                    .newInstance(decks = decks, allowAll = false, allowFiltered = false)
                    .show(childFragmentManager, DeckSelectionDialog.TAG)
            }
        }
        binding.addCards.setOnClickListener { addAcceptedCards(binding) }
    }

    private fun addAcceptedCards(binding: FragmentFlashcardGenerationBinding) {
        val accepted = viewModel.acceptedCards()
        if (accepted.isEmpty()) {
            showSnackbar(R.string.ai_error_no_cards_selected)
            return
        }
        if (binding.newDeckOption.isChecked) {
            val name =
                binding.newDeckName.text
                    .toString()
                    .trim()
            if (name.isEmpty()) {
                showSnackbar(R.string.ai_error_deck_name_required)
                return
            }
            viewModel.addAcceptedCards(accepted, deckId = null, createDeck = true, newDeckName = name)
        } else {
            val deckId = viewModel.selectedDeck?.deckId
            if (deckId == null) {
                showSnackbar(R.string.ai_no_deck_selected)
                return
            }
            viewModel.addAcceptedCards(accepted, deckId = deckId, createDeck = false, newDeckName = null)
        }
    }

    private fun setupDoneStep(binding: FragmentFlashcardGenerationBinding) {
        binding.done.setOnClickListener { viewModel.backToInput() }
    }

    private fun setupDeckSelectionListener() {
        childFragmentManager.setFragmentResultListener(DeckSelectionDialog.REQUEST_SELECT_DECK, this) { _, bundle ->
            val deck = bundle.getParcelableCompat<SelectableDeck?>(DeckSelectionDialog.ARG_SELECTED_DECK)
            if (deck is SelectableDeck.Deck) {
                viewModel.selectedDeck = deck
                view?.findViewById<TextView>(R.id.existing_deck_label)?.text = deck.name
            }
        }
    }

    /** Updates the visible step for [state]. */
    private fun render(
        binding: FragmentFlashcardGenerationBinding,
        state: GenerationUiState,
    ) {
        binding.inputStep.isVisible = state is GenerationUiState.Input
        binding.reviewStep.isVisible = state is GenerationUiState.Reviewing
        binding.doneStep.isVisible = state is GenerationUiState.Done
        binding.progress.isVisible = state is GenerationUiState.Generating || state is GenerationUiState.Adding
        binding.generatingProgress.isVisible = state is GenerationUiState.Generating
        binding.addingProgress.isVisible = state is GenerationUiState.Adding
        binding.progressText.text =
            getString(
                if (state is GenerationUiState.Adding) R.string.ai_adding else R.string.ai_generating,
            )

        when (state) {
            is GenerationUiState.Input -> binding.setupHint.isVisible = !viewModel.isConfigured
            is GenerationUiState.Reviewing -> renderReviewCards(binding)
            is GenerationUiState.Done ->
                binding.doneSummary.text = getString(R.string.ai_done_summary, state.addedCount, state.deckName)
            else -> Unit
        }
    }

    private fun renderReviewCards(binding: FragmentFlashcardGenerationBinding) {
        reviewAdapter?.submitCards(viewModel.currentCards(), viewModel.acceptedIds.value)
        updateAddButton(binding)
    }

    private fun updateAddButton(binding: FragmentFlashcardGenerationBinding) {
        val count = viewModel.acceptedCards().size
        binding.addCards.text = resources.getQuantityString(R.plurals.ai_add_cards, count, count)
        binding.addCards.isEnabled = count > 0
    }

    /** Shows a dialog editing the front/back of [card]; saves back into the review list. */
    private fun showEditCardDialog(card: GeneratedFlashcard) {
        val dialogBinding = DialogEditGeneratedCardBinding.inflate(layoutInflater)
        dialogBinding.front.setText(card.front)
        dialogBinding.back.setText(card.back)
        androidx.appcompat.app.AlertDialog
            .Builder(requireContext())
            .show {
                title(R.string.ai_edit_card_title)
                customView(view = dialogBinding.root)
                positiveButton(R.string.save) {
                    val front =
                        dialogBinding.front.text
                            .toString()
                            .trim()
                    val back =
                        dialogBinding.back.text
                            .toString()
                            .trim()
                    if (front.isEmpty() || back.isEmpty()) {
                        showSnackbar(R.string.ai_error_card_fields_required)
                        return@positiveButton
                    }
                    viewModel.updateCard(card.id, front, back)
                }
                negativeButton(R.string.dialog_cancel)
            }
    }

    companion object {
        const val MIN_CARDS = 1
        const val MAX_CARDS = 50
        const val DEFAULT_CARD_COUNT = 10

        /** Creates the launch intent for [SingleFragmentActivity]. */
        fun getIntent(context: Context): Intent = SingleFragmentActivity.getIntent(context, FlashcardGenerationFragment::class)
    }
}
