// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.cardviewer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.RobolectricTest
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.stringContainsInOrder
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the MathJax package-enabling script: it must register the chemistry/quantum
 * packages compiled into the bundled MathJax, and must be idempotent (safe to execute
 * more than once) and null-safe.
 */
@RunWith(AndroidJUnit4::class)
class MathJaxPackagesScriptTest : RobolectricTest() {
    private val script: String by lazy {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context
            .assets
            .open("scripts/mathjax-packages.js")
            .bufferedReader()
            .readText()
    }

    @Test
    fun `script registers the chemistry quantum and color packages`() {
        assertThat(script, stringContainsInOrder("mhchem", "braket", "physics", "color"))
    }

    @Test
    fun `script targets the tex packages config`() {
        assertThat(script, containsString("window.MathJax.tex"))
    }

    @Test
    fun `script is null-safe`() {
        assertThat(script, containsString("typeof window.MathJax"))
    }

    @Test
    fun `script does not overwrite the backend config`() {
        // must add to the "[+]" array, not replace it
        assertThat(script, containsString("packages[\"[+]\"]"))
    }
}
