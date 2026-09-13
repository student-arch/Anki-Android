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
import org.hamcrest.Matchers.equalTo
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

    /**
     * The destination picker must support unlimited nesting: a deck at any depth resolves onto
     * the due tree (keeping its expander/subdeck structure) instead of degrading to a leaf.
     */
    @Test
    fun `deeply nested subdecks resolve onto the tree at their real depth`() {
        val tree =
            DeckNode(
                deckTreeNode {
                    name = ""
                    deckId = 0
                    level = 0
                    children.add(
                        deckTreeNode {
                            name = "Deep"
                            deckId = 1
                            level = 1
                            children.add(
                                deckTreeNode {
                                    name = "One"
                                    deckId = 2
                                    level = 2
                                    children.add(
                                        deckTreeNode {
                                            name = "Two"
                                            deckId = 3
                                            level = 3
                                            children.add(
                                                deckTreeNode {
                                                    name = "Three"
                                                    deckId = 4
                                                    level = 4
                                                    children.add(
                                                        deckTreeNode {
                                                            name = "Four"
                                                            deckId = 5
                                                            level = 5
                                                            children.add(
                                                                deckTreeNode {
                                                                    name = "Five"
                                                                    deckId = 6
                                                                    level = 6
                                                                },
                                                            )
                                                        },
                                                    )
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
                "",
                null,
            )
        // the flat list the generation screen passes: allNamesAndIds() names with '::' separators
        val passed =
            (1..6).map { depth ->
                val names = listOf("Deep", "One", "Two", "Three", "Four", "Five").take(depth)
                SelectableDeck.Deck(deckId = depth.toLong(), name = names.joinToString("::"))
            }

        val displayed =
            DeckSelectionDialog.resolveDecksForDisplay(
                androidx.test.core.app.ApplicationProvider
                    .getApplicationContext(),
                passed,
                tree,
            )

        assertThat(
            displayed.map { it.fullDeckName },
            contains(
                "Deep",
                "Deep::One",
                "Deep::One::Two",
                "Deep::One::Two::Three",
                "Deep::One::Two::Three::Four",
                "Deep::One::Two::Three::Four::Five",
            ),
        )
        // every level keeps its position in the hierarchy (expanders/indent work at any depth)
        assertThat(displayed.map { it.depth }, contains(0, 1, 2, 3, 4, 5))
        // the deepest deck retains its subdeck-free children list but a real parent chain
        assertThat(displayed.last().children.isEmpty(), equalTo(true))
        assertThat(
            displayed
                .last()
                .parent
                ?.get()
                ?.fullDeckName,
            equalTo("Deep::One::Two::Three::Four"),
        )
    }
}
