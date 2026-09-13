// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.importer

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.libanki.testutils.ext.newNote
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies that content imported via [TextImportParser] (formulas, quantum/chemistry
 * notation, code, rich formatting) survives the full note lifecycle: create → save →
 * reload → edit → save again → render for review. Fields are stored as raw HTML, so
 * every wrapper the editor toolbar produces must round-trip without modification.
 */
@RunWith(AndroidJUnit4::class)
class TextImportPersistenceTest : RobolectricTest() {
    private val mixedCard =
        ParsedTextCard(
            front = """Mixed card with \( E = mc^2 \) and \( \ket{\psi} = \alpha\ket{0} + \beta\ket{1} \)""",
            back =
                """<h3>States</h3><b>bold</b> <span style="color:#ff0000">red</span> """ +
                    """\( \ce{H2SO4} \) <pre><code>for i in range(10):
    print(i)</code></pre>""",
        )

    @Test
    fun `imported note persists content and formatting through save and reload`() =
        runTest {
            val noteId = addImportedNote(mixedCard)

            // reload the note from the collection: content must be byte-for-byte intact
            val reloaded = col.getNote(noteId)
            assertThat(reloaded.cards(col).size, equalTo(1))
            assertThat(reloaded.getItem("Front"), equalTo(mixedCard.front))
            assertThat(reloaded.getItem("Back"), equalTo(mixedCard.back))

            // edit stage: append text, save again, reload once more
            reloaded.update { setItem("Back", getItem("Back") + " <i>edited</i>") }
            val afterEdit = col.getNote(noteId)
            assertThat(afterEdit.getItem("Back"), equalTo(mixedCard.back + " <i>edited</i>"))

            // review stage: the rendered question contains the math content verbatim
            // (the notetype's styling is prepended by design)
            val card = afterEdit.cards(col).first()
            assertThat(card.question(col), containsString(mixedCard.front))
        }

    @Test
    fun `multiple imported notes keep their contents distinct`() =
        runTest {
            val first = addImportedNote(ParsedTextCard("Q1; front one", "A1; back one"))
            val second = addImportedNote(ParsedTextCard("Front two", "Back two"))
            assertThat(col.getNote(first).getItem("Front"), equalTo("Q1; front one"))
            assertThat(col.getNote(second).getItem("Front"), equalTo("Front two"))
        }

    private fun addImportedNote(card: ParsedTextCard) =
        col
            .newNote()
            .apply {
                setItem("Front", card.front)
                setItem("Back", card.back)
            }.also { col.addNote(it, 0) }
            .id
}
