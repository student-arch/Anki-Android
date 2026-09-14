// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.noteeditor

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.RobolectricTest
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ToolbarRichFormatTest : RobolectricTest() {
    @Test
    fun `list formatter wraps lines as an unordered list`() {
        val result = Toolbar.ListFormatter(ordered = false).format("one\ntwo").result
        assertThat(result, equalTo("<ul><li>one</li><li>two</li></ul>"))
    }

    @Test
    fun `list formatter wraps lines as an ordered list`() {
        val result = Toolbar.ListFormatter(ordered = true).format("one\ntwo").result
        assertThat(result, equalTo("<ol><li>one</li><li>two</li></ol>"))
    }

    @Test
    fun `code block formatter wraps selection in a pre tag`() {
        val result =
            Toolbar
                .TextWrapper(
                    prefix = "<pre><code>",
                    suffix = "</code></pre>",
                ).format("for i in range(10):\n    print(i)")
                .result
        assertThat(result, equalTo("<pre><code>for i in range(10):\n    print(i)</code></pre>"))
    }

    @Test
    fun `clear format keeps code block text but removes tags`() {
        val cleared = Toolbar.ClearFormatFormatter.format("<pre><code>x = 1</code></pre>").result
        assertThat(cleared, equalTo("x = 1"))
    }

    @Test
    fun `clear format strips bold italic underline code headings and spans`() {
        val formatted = "<h2><b>Title</b></h2><span style=\"color:#ff0000\">red <i>text</i></span><code>x()</code>"
        val cleared = Toolbar.ClearFormatFormatter.format(formatted).result
        assertThat(cleared, equalTo("Title" + "red " + "text" + "x()"))
    }

    @Test
    fun `clear format keeps plain text intact`() {
        val cleared = Toolbar.ClearFormatFormatter.format("plain text with  spaces").result
        assertThat(cleared, equalTo("plain text with  spaces"))
    }

    @Test
    fun `clear format removes font tags`() {
        val cleared = Toolbar.ClearFormatFormatter.format("<font color=\"red\">r</font>").result
        assertThat(cleared, equalTo("r"))
    }
}
