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

                        override fun onRetryImage(card: GeneratedFlashcard) {
                            viewModel.toggleRetrySelection(card.id, true)
                            viewModel.retrySelectedImages()
                        }

                        override fun onRetrySelectionChanged(
                            card: GeneratedFlashcard,
                            isSelected: Boolean,
                        ) {
                            viewModel.toggleRetrySelection(card.id, isSelected)
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

        // re-render the review list as async image generation completes per card
        viewModel.imageStates
            .flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
            .onEach { renderReviewCards(binding) }
            .launchIn(viewLifecycleOwner.lifecycleScope)
        viewModel.retrySelection
            .flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
            .onEach { renderReviewCards(binding) }
            .launchIn(viewLifecycleOwner.lifecycleScope)
        viewModel.selectedProviderModel
            .flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
            .onEach { renderProviderModel(binding) }
            .launchIn(viewLifecycleOwner.lifecycleScope)
        viewModel.selectedImageProviderModel
            .flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
            .onEach { renderImageProviderModel(binding) }
            .launchIn(viewLifecycleOwner.lifecycleScope)
        viewModel.isImageGenerationEnabled
            .flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
            .onEach { enabled -> renderImagesEnabled(binding, enabled) }
            .launchIn(viewLifecycleOwner.lifecycleScope)
    }

    override fun onResume() {
        super.onResume()
        // the selection or provider list may have changed while the AI settings were open
        viewModel.refreshSelectedProviderModel()
        viewModel.refreshImageSelection()
    }

    override fun onDestroyView() {
        reviewAdapter = null
        super.onDestroyView()
    }

    private fun setupInputStep(binding: FragmentFlashcardGenerationBinding) {
        binding.setupHint.setOnClickListener { ProviderConfigFragment.openFrom(requireContext()) }
        binding.selectProviderModel.setOnClickListener { showProviderModelSelection() }
        binding.selectImageProviderModel.setOnClickListener { showImageProviderModelSelection() }
        binding.generateImages.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setImagesEnabled(isChecked)
        }
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
                    ?.coerceIn(MIN_CARDS, MAX_CARDS) ?: FlashcardGenerator.COUNT_AUTO
            viewModel.generate(text, requested)
        }
    }

    private fun setupReviewStep(binding: FragmentFlashcardGenerationBinding) {
        binding.changeImages.setOnClickListener { showImageCardSelection() }
        binding.selectAll.setOnClickListener {
            viewModel.acceptAll()
            renderReviewCards(binding)
        }
        binding.deselectAll.setOnClickListener {
            viewModel.acceptNone()
            renderReviewCards(binding)
        }
        binding.retryFailed.setOnClickListener {
            viewModel.retrySelectedImages()
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

    /** Image selection is separate from accepting cards for addition to a deck. */
    private fun showImageCardSelection() {
        val cards = viewModel.currentCards()
        val selected = BooleanArray(cards.size)
        val dialog =
            androidx.appcompat.app.AlertDialog
                .Builder(requireContext())
                .setTitle(R.string.ai_select_images)
                .setMultiChoiceItems(cards.map { it.front }.toTypedArray(), selected) { _, index, checked ->
                    selected[index] = checked
                }.setPositiveButton(R.string.ai_provider_label, null)
                .setNegativeButton(R.string.dialog_cancel, null)
                .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val ids = cards.filterIndexed { index, _ -> selected[index] }.map { it.id }.toSet()
                if (ids.isEmpty()) {
                    showSnackbar(R.string.ai_select_images)
                } else {
                    dialog.dismiss()
                    showImageProviderSelection(ids)
                }
            }
        }
        dialog.show()
    }

    private fun showImageProviderSelection(cardIds: Set<Long>) {
        val providers = AiProviderStore.getInstance(requireContext().applicationContext).getProviders()
        if (providers.isEmpty()) {
            showSnackbar(R.string.ai_providers_none_configured)
            return
        }
        androidx.appcompat.app.AlertDialog
            .Builder(requireContext())
            .setTitle(R.string.ai_provider_label)
            .setItems(providers.map { it.name }.toTypedArray()) { _, index ->
                val provider = providers[index]
                if (provider.modelIds.isEmpty()) {
                    showSnackbar(R.string.ai_image_no_models)
                    return@setItems
                }
                androidx.appcompat.app.AlertDialog
                    .Builder(requireContext())
                    .setTitle(R.string.ai_model_label)
                    .setItems(provider.modelIds.toTypedArray()) { _, modelIndex ->
                        viewModel.regenerateImages(cardIds, provider.id, provider.modelIds[modelIndex])
                    }.setNegativeButton(R.string.dialog_cancel, null)
                    .show()
            }.setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    /** Lets the user pick the provider and model used for flashcard generation on this screen. */
    private fun showProviderModelSelection() {
        showProviderModelPicker { providerId, modelId -> viewModel.selectProviderModel(providerId, modelId) }
    }

    /** Lets the user pick the provider and model used for image generation on this screen. */
    private fun showImageProviderModelSelection() {
        showProviderModelPicker { providerId, modelId -> viewModel.selectImageProviderModel(providerId, modelId) }
    }

    /** Two-step picker: choose a provider, then one of its models; [onPicked] receives the choice. */
    private fun showProviderModelPicker(onPicked: (providerId: String, modelId: String) -> Unit) {
        val providers = AiProviderStore.getInstance(requireContext().applicationContext).getProviders()
        if (providers.isEmpty()) {
            showSnackbar(R.string.ai_providers_none_configured)
            return
        }
        androidx.appcompat.app.AlertDialog
            .Builder(requireContext())
            .setTitle(R.string.ai_provider_label)
            .setItems(providers.map { it.name }.toTypedArray()) { _, index ->
                val provider = providers[index]
                if (provider.modelIds.isEmpty()) {
                    showSnackbar(R.string.ai_image_no_models)
                    return@setItems
                }
                androidx.appcompat.app.AlertDialog
                    .Builder(requireContext())
                    .setTitle(R.string.ai_model_label)
                    .setItems(provider.modelIds.toTypedArray()) { _, modelIndex ->
                        onPicked(provider.id, provider.modelIds[modelIndex])
                    }.setNegativeButton(R.string.dialog_cancel, null)
                    .show()
            }.setNegativeButton(R.string.dialog_cancel, null)
            .show()
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
        reviewAdapter?.submitCards(
            viewModel.currentCards(),
            viewModel.acceptedIds.value,
            viewModel.imageStates.value,
            viewModel.retrySelection.value,
        )
        updateAddButton(binding)
        updateRetryButton(binding)
    }

    /** Shows the current flashcard provider/model on the picker button. */
    private fun renderProviderModel(binding: FragmentFlashcardGenerationBinding) {
        val selection = viewModel.selectedProviderModel.value
        binding.selectProviderModel.text =
            if (selection == null) {
                getString(R.string.ai_model_none_selected)
            } else {
                getString(R.string.ai_provider_model_summary, selection.providerName, selection.modelId)
            }
    }

    /** Shows the current image provider/model on the picker button, or the off label. */
    private fun renderImageProviderModel(binding: FragmentFlashcardGenerationBinding) {
        val selection = viewModel.selectedImageProviderModel.value
        binding.selectImageProviderModel.text =
            if (selection == null) {
                getString(R.string.ai_images_off)
            } else {
                getString(R.string.ai_provider_model_summary, selection.providerName, selection.modelId)
            }
    }

    /** Reflects the image switch state and greys out the image picker when images are off. */
    private fun renderImagesEnabled(
        binding: FragmentFlashcardGenerationBinding,
        enabled: Boolean,
    ) {
        // suppress the listener feedback loop when the state change did not come from the user
        binding.generateImages.setOnCheckedChangeListener(null)
        binding.generateImages.isChecked = enabled
        binding.generateImages.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setImagesEnabled(isChecked)
        }
        binding.selectImageProviderModel.isEnabled = enabled
    }

    /** Shows the batch Try Again button only while at least one failed image is selected. */
    private fun updateRetryButton(binding: FragmentFlashcardGenerationBinding) {
        val hasSelectedFailures =
            viewModel.retrySelection.value.any { id -> viewModel.imageStates.value[id] is CardImageState.Failed }
        binding.retryFailed.isVisible = hasSelectedFailures
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

        /** Creates the launch intent for [SingleFragmentActivity]. */
        fun getIntent(context: Context): Intent = SingleFragmentActivity.getIntent(context, FlashcardGenerationFragment::class)
    }
}
