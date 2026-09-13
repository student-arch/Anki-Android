// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.importer

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.model.SelectableDeck
import com.ichi2.testutils.AnkiFragmentScenario
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.equalTo
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies that the text import screen retains the parsed preview and the selected
 * deck across a configuration change (fragment recreation).
 */
@RunWith(AndroidJUnit4::class)
class TextImportFragmentTest : RobolectricTest() {
    @Test
    fun `parsed cards and selected deck survive recreation`() {
        AnkiFragmentScenario.launchInContainer(TextImportFragment::class.java).use { scenario ->
            scenario.onFragment { fragment ->
                fragment.parsedCardsForTest =
                    mutableListOf(
                        ParsedTextCard("What is 2+2?", "4"),
                        ParsedTextCard("Front two", "Back two"),
                    )
                fragment.selectedDeckForTest = SelectableDeck.Deck(1L, "Default")
            }

            // recreate the fragment: the state must be restored from saved instance state
            scenario.recreate()

            scenario.onFragment { fragment ->
                assertThat(
                    fragment.parsedCardsForTest.map { it.front },
                    contains("What is 2+2?", "Front two"),
                )
                assertThat(
                    (fragment.selectedDeckForTest as SelectableDeck.Deck).name,
                    equalTo("Default"),
                )
            }
        }
    }

    @Test
    fun `restored preview is visible with cards listed`() {
        AnkiFragmentScenario.launchInContainer(TextImportFragment::class.java).use { scenario ->
            scenario.onFragment { fragment ->
                fragment.parsedCardsForTest = mutableListOf(ParsedTextCard("What is 2+2?", "4"))
            }
            scenario.recreate()
            onView(withId(R.id.preview_front)).check(matches(withText("What is 2+2?")))
        }
    }
}
