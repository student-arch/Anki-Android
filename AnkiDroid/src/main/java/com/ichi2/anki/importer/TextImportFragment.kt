// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.importer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.core.os.BundleCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.R
import com.ichi2.anki.SingleFragmentActivity
import com.ichi2.anki.databinding.DialogEditGeneratedCardBinding
import com.ichi2.anki.dialogs.DeckSelectionDialog
import com.ichi2.anki.model.SelectableDeck
import com.ichi2.anki.observability.undoableOp
import com.ichi2.anki.snackbar.showSnackbar
import com.ichi2.anki.utils.doOnApplyWindowInsets
import com.ichi2.anki.utils.ext.getParcelableCompat
import com.ichi2.utils.customView
import com.ichi2.utils.negativeButton
import com.ichi2.utils.positiveButton
import com.ichi2.utils.show
import com.ichi2.utils.title
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Screen for importing flashcards from pasted or typed text: the user enters the cards,
 * previews (and may edit) the parsed result, then adds them to an existing or a new deck.
 *
 * Cards are parsed by [TextImportParser], which preserves formulas and Unicode exactly
 * as entered. The screen extends the existing import options with a paste-text entry
 * and does not affect the file-based import flows.
 */
class TextImportFragment : Fragment() {
    private var inputText: TextInputEditText? = null
    private var previewList: RecyclerView? = null
    private var previewCard: MaterialCardView? = null
    private var deckLabel: TextView? = null
    private var parsedCards: MutableList<ParsedTextCard> = mutableListOf()
    private var selectedDeck: SelectableDeck.Deck? = null

    /** Test accessors for state which must survive fragment recreation. */
    @VisibleForTesting
    var parsedCardsForTest: MutableList<ParsedTextCard>
        get() = parsedCards
        set(value) {
            parsedCards = value
        }

    /** @see [parsedCardsForTest] */
    @VisibleForTesting
    var selectedDeckForTest: SelectableDeck.Deck?
        get() = selectedDeck
        set(value) {
            selectedDeck = value
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_text_import, container, false)

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
        toolbar.inflateMenu(R.menu.text_import)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_import_text_preview -> {
                    showPreview()
                    true
                }
                else -> false
            }
        }
        inputText = view.findViewById(R.id.import_text_input)
        previewList = view.findViewById(R.id.import_text_preview_list)
        previewCard = view.findViewById(R.id.import_text_preview_card)
        deckLabel = view.findViewById(R.id.import_text_deck_label)

        view.findViewById<MaterialButton>(R.id.import_text_choose_deck).setOnClickListener { chooseDeck() }
        view.findViewById<MaterialButton>(R.id.import_text_add).setOnClickListener { addCardsToDeck() }

        view.findViewById<View>(R.id.import_text_root).doOnApplyWindowInsets { v, insets, initial ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(
                initial.padding.left + bars.left,
                initial.padding.top + bars.top,
                initial.padding.right + bars.right,
                initial.padding.bottom,
            )
        }

        childFragmentManager.setFragmentResultListener(DeckSelectionDialog.REQUEST_SELECT_DECK, this) { _, bundle ->
            val deck = bundle.getParcelableCompat<SelectableDeck?>(DeckSelectionDialog.ARG_SELECTED_DECK)
            if (deck is SelectableDeck.Deck) {
                selectedDeck = deck
                deckLabel?.text = deck.name
                deckLabel?.isVisible = true
            }
        }

        // restore state retained across a configuration change
        savedInstanceState?.let { restoreState(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // the entered text is restored automatically: the EditText has an id
        outState.putParcelableArrayList(STATE_PARSED_CARDS, ArrayList(parsedCards))
        selectedDeck?.let { outState.putParcelable(STATE_SELECTED_DECK, it) }
    }

    /** Rebinds the preview and the deck label from [state] saved before recreation. */
    private fun restoreState(state: Bundle) {
        val cards =
            BundleCompat
                .getParcelableArrayList(state, STATE_PARSED_CARDS, ParsedTextCard::class.java)
                .orEmpty()
                .toMutableList()
        val deck = state.getParcelableCompat<SelectableDeck.Deck>(STATE_SELECTED_DECK)
        if (cards.isEmpty() && deck == null) return
        parsedCards = cards
        selectedDeck = deck
        if (cards.isNotEmpty()) {
            previewCard?.isVisible = true
            previewList?.layoutManager = LinearLayoutManager(requireContext())
            previewList?.adapter = PreviewAdapter(parsedCards)
        }
        if (deck != null) {
            deckLabel?.text = deck.name
            deckLabel?.isVisible = true
        }
    }

    private fun showPreview() {
        val text = inputText?.text?.toString().orEmpty()
        parsedCards = TextImportParser.parse(text).toMutableList()
        if (parsedCards.isEmpty()) {
            showSnackbar(R.string.import_text_parse_error)
            return
        }
        previewCard?.isVisible = true
        previewList?.layoutManager = LinearLayoutManager(requireContext())
        previewList?.adapter = PreviewAdapter(parsedCards)
    }

    private fun chooseDeck() {
        lifecycleScope.launch {
            val decks = withCol { decks.allNamesAndIds().map { SelectableDeck.Deck(it) } }
            DeckSelectionDialog
                .newInstance(decks = decks, allowAll = false, allowFiltered = false)
                .show(childFragmentManager, DeckSelectionDialog.TAG)
        }
    }

    /** Shows a dialog editing the front/back of the card at [position]; saves back into the list. */
    private fun editCard(position: Int) {
        val card = parsedCards.getOrNull(position) ?: return
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
                    parsedCards[position] = ParsedTextCard(front, back)
                    previewList?.adapter?.notifyItemChanged(position)
                }
                negativeButton(R.string.dialog_cancel)
            }
    }

    private fun addCardsToDeck() {
        val deckId = selectedDeck?.deckId
        if (deckId == null) {
            showSnackbar(R.string.ai_no_deck_selected)
            return
        }
        if (parsedCards.isEmpty()) {
            showSnackbar(R.string.import_text_parse_error)
            return
        }
        val cards = parsedCards.toList()
        lifecycleScope.launch {
            try {
                // notes must be created on the collection; undoableOp re-enters it for the adds
                val notes =
                    withCol {
                        val notetype =
                            notetypes.byName(BASIC_NOTETYPE)
                                ?: notetypes.all().firstOrNull { it.isStd && it.fieldsNames.size >= 2 }
                                ?: notetypes.all().first()
                        cards.map { card ->
                            newNote(notetype).apply {
                                setField(0, card.front)
                                setField(1, card.back)
                            }
                        }
                    }
                var count = 0
                undoableOp {
                    val results = notes.map { addNote(it, deckId) }
                    count = results.sumOf { it.count }
                    // last expression must be an OpChanges subtype for ChangeManager
                    results.last()
                }
                val addedCount = count
                Timber.i("imported %d cards from text into deck %d", addedCount, deckId)
                showSnackbar(getString(R.string.ai_done_summary, addedCount, selectedDeck?.name.orEmpty()))
                inputText?.setText("")
                parsedCards.clear()
                previewList?.adapter = null
                previewCard?.isVisible = false
            } catch (e: Exception) {
                Timber.e(e, "failed to import text cards")
                showSnackbar(R.string.ai_error_add_failed)
            }
        }
    }

    /** Rows of the preview list: the card's front and back, with an edit action. */
    private inner class PreviewAdapter(
        private val cards: List<ParsedTextCard>,
    ) : RecyclerView.Adapter<PreviewAdapter.ViewHolder>() {
        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): ViewHolder =
            ViewHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.item_text_import_preview, parent, false),
            )

        override fun onBindViewHolder(
            holder: ViewHolder,
            position: Int,
        ) {
            val card = cards[position]
            holder.front.text = card.front
            holder.back.text = card.back
            holder.edit.setOnClickListener { editCard(position) }
        }

        override fun getItemCount(): Int = cards.size

        inner class ViewHolder(
            view: View,
        ) : RecyclerView.ViewHolder(view) {
            val front: TextView = view.findViewById(R.id.preview_front)
            val back: TextView = view.findViewById(R.id.preview_back)
            val edit: View = view.findViewById(R.id.preview_edit)
        }
    }

    companion object {
        private const val BASIC_NOTETYPE = "Basic"
        private const val STATE_PARSED_CARDS = "parsedCards"
        private const val STATE_SELECTED_DECK = "selectedDeck"

        /** Creates the launch intent for [SingleFragmentActivity]. */
        fun getIntent(context: Context): Intent = SingleFragmentActivity.getIntent(context, TextImportFragment::class)
    }
}
