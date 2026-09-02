// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import anki.decks.deckTreeNode
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.dialogs.DeckSelectionDialog
import com.ichi2.anki.libanki.sched.DeckNode
import com.ichi2.anki.model.SelectableDeck
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.contains
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression tests for the deck list shown by [DeckSelectionDialog] when opened from the
 * flashcard generation screen ('Choose deck').
 */
@RunWith(AndroidJUnit4::class)
class FlashcardGenerationDeckListTest : RobolectricTest() {
    /**
     * The backend's `deckTree()` omits the empty 'Default' deck on device. A deck passed to
     * [DeckSelectionDialog] which is missing from the tree must still be displayed, otherwise
     * a fresh collection shows an empty 'Choose deck' list.
     */
    @Test
    fun `decks missing from the due tree are displayed as leaf nodes`() {
        val tree =
            DeckNode(
                deckTreeNode {
                    name = ""
                    deckId = 0
                    level = 0
                    children.add(
                        deckTreeNode {
                            name = "AI Deck"
                            deckId = 5
                            level = 1
                        },
                    )
                },
                "",
                null,
            )
        val passed =
            listOf(
                SelectableDeck.Deck(deckId = 1, name = "Default"),
                SelectableDeck.Deck(deckId = 5, name = "AI Deck"),
            )

        val displayed =
            DeckSelectionDialog.resolveDecksForDisplay(
                androidx.test.core.app.ApplicationProvider
                    .getApplicationContext(),
                passed,
                tree,
            )

        assertThat(displayed.map { it.fullDeckName }, contains("Default", "AI Deck"))
    }
}
