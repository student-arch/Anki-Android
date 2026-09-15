// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ai

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.ichi2.anki.R
import com.ichi2.anki.SingleFragmentActivity
import com.ichi2.anki.databinding.FragmentMathDebugBinding
import timber.log.Timber

/**
 * Debug screen for the math rendering pipeline: renders a comprehensive suite of
 * equations (the same varieties AI-generated flashcards contain) through the REAL
 * pipeline — [MathNormalizer] + [FlashcardRenderer] HTML + the viewer's bundled
 * MathJax — so rendering failures are easy to spot visually.
 *
 * The screen shows the typeset cards in a WebView (exactly as the reviewer would)
 * with the normalized TeX source below for comparison.
 *
 * Reachable from AI settings ("Math rendering test").
 */
class MathDebugFragment : Fragment(R.layout.fragment_math_debug) {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        val binding = FragmentMathDebugBinding.bind(view)
        binding.toolbar.setNavigationOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        // every entry goes through the full pipeline: normalize -> render -> MathJax
        val sections =
            listOf(
                "Simple equation" to
                    listOf(
                        "x + y = z",
                        "Solve ${'$'}x^2 + 5x + 6 = 0${'$'}",
                        """\(x^2 + 5x + 6 = 0\)""",
                    ),
                "Quadratic formula" to
                    listOf(
                        """\[
x = \frac{-b \pm \sqrt{b^2 - 4ac}}{2a}
\]""",
                        "${'$'}${'$'}x = \\frac{-b \\pm \\sqrt{b^2 - 4ac}}{2a}${'$'}${'$'}",
                    ),
                "Fractions and roots" to
                    listOf(
                        """\( \frac{a}{b} \) and \( \sqrt{a^2 + b^2} \)""",
                        "${'$'}\\frac{3}{4}${'$'} of the total",
                    ),
                "Powers and subscripts" to
                    listOf(
                        """\( E = mc^2 \), \( a_1 + a_2 \), \( x^{n+1} \)""",
                        """Unicode: \( A = π r² \), \( Σᵢ₌₁ⁿ i \)""",
                    ),
                "Calculus" to
                    listOf(
                        """\( \int_0^\infty e^{-x}\,dx = 1 \)""",
                        """\( \frac{d}{dx}\sin x = \cos x \), \( \lim_{x \to 0} \frac{\sin x}{x} = 1 \)""",
                    ),
                "Summation and products" to
                    listOf(
                        """\( \sum_{i=1}^{n} i = \frac{n(n+1)}{2} \)""",
                        """\( \prod_{i=1}^{n} i = n! \)""",
                    ),
                "Matrices and systems" to
                    listOf(
                        """\[ \begin{pmatrix} a & b \\ c & d \end{pmatrix} \]""",
                        """\( \begin{cases} x + y = 3 \\ x - y = 1 \end{cases} \)""",
                    ),
                "Greek and trigonometry" to
                    listOf(
                        """\( \alpha + \beta = \gamma \), \( \sin^2\theta + \cos^2\theta = 1 \)""",
                        """Unicode inside math: \( α + β = γ \), \( θ \)""",
                    ),
                "Logarithms and inequalities" to
                    listOf(
                        """\( \log_2 8 = 3 \), \( a \leq b \leq c \), \( |x| \geq 0 \)""",
                    ),
                "Physics formulas" to
                    listOf(
                        """\( V = IR \), \( P = VI \), \( F = ma \)""",
                        """\( \vec{F} = q(\vec{E} + \vec{v} \times \vec{B}) \)""",
                    ),
                "Chemistry (mhchem)" to
                    listOf(
                        """\( \ce{2H2 + O2 -> 2H2O} \)""",
                        """\( \ce{H2O + CO2 <=> H2CO3} \)""",
                    ),
                "Quantum (braket)" to
                    listOf(
                        """\( \ket{\psi} = \alpha\ket{0} + \beta\ket{1} \)""",
                        """\( \braket{\phi}{\psi} \)""",
                    ),
                "Statistics" to
                    listOf(
                        """\( \mu = \frac{1}{n}\sum_{i=1}^{n} x_i \), \( \sigma^2 \)""",
                    ),
                "Mixed text and math" to
                    listOf(
                        "To solve \\(ax^2 + bx + c = 0\\), use the formula \\[x = \\frac{-b \\pm \\sqrt{b^2 - 4ac}}{2a}\\] where \\(a \\neq 0\\).",
                        "The cost is ${'$'}5 (money, not math) and the area is ${'$'}A = π r^2${'$'}.",
                    ),
                "Malformed input (must not crash)" to
                    listOf(
                        "unclosed \$x^2",
                        "empty \$\$ \$\$ math",
                    ),
                "Long equation (layout stress)" to
                    listOf(
                        """\[ \int_{-\infty}^{\infty} e^{-a x^2 + b x + c}\,dx = \sqrt{\frac{\pi}{a}} \, e^{\frac{b^2}{4a} + c} \]""",
                    ),
                // the exact user-reported failure: undelimited LaTeX inside a formula section,
                // which used to fall into a <pre> gray box where MathJax never runs
                "Bare LaTeX in a Formula section" to
                    listOf(
                        "h = 15^\\circ \\times (\\text{Solar Time} - 12\\text{h})",
                        "\\tau = RC, \\quad t \\gg RC",
                        "P = VI = I^2R",
                        "v_C(t) = V_0(1 - e^{-t/RC})",
                    ),
            )

        val htmlRows = StringBuilder()
        val sourceRows = StringBuilder()
        for ((heading, samples) in sections) {
            htmlRows.append("<div style=\"margin-top:14px;font-weight:bold;\">").append(heading).append("</div>")
            sourceRows.append("== ").append(heading).append(" ==\n")
            for (sample in samples) {
                // Formula sections exercise the pre/flow decision; the back exercises inline math
                val formulaSections = listOf(CardSection("Formula", sample))
                val card = GeneratedFlashcard(front = heading, back = sample, sections = formulaSections)
                htmlRows.append("<div style=\"padding:2px 0;\">").append(FlashcardRenderer.ankiBack(card)).append("</div>")
                sourceRows.append(MathNormalizer.normalize(sample)).append('\n')
            }
        }

        val html =
            """<!DOCTYPE html>
            <html>
            <head>
            <meta charset="utf-8">
            <style>
            body { font-family: sans-serif; font-size: 15px; margin: 8px; }
            mjx-container { overflow-x: auto; max-width: 100%; }
            </style>
            <script src="file:///android_asset/backend/js/mathjax.js"></script>
            <script src="file:///android_asset/scripts/mathjax-packages.js"></script>
            <script src="file:///android_asset/backend/js/vendor/mathjax/tex-chtml-full.js"></script>
            </head>
            <body>$htmlRows</body>
            </html>"""

        // WebView rendering exactly like the reviewer: MathJax typesets on load
        val webView = WebView(requireContext())
        webView.settings.javaScriptEnabled = true
        webView.webChromeClient =
            object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    consoleMessage?.let { Timber.tag("MathDebugJS").d("%d: %s", it.lineNumber(), it.message()) }
                    return true
                }
            }
        webView.webViewClient =
            object : WebViewClient() {
                override fun onPageFinished(
                    view: WebView?,
                    url: String?,
                ) {
                    // the backend config sets startup.typeset=false; run it manually,
                    // then report typeset statistics for a logcat-verifiable check
                    view?.evaluateJavascript(
                        """
                        if (window.MathJax && MathJax.typesetPromise) {
                            MathJax.typesetPromise().then(function() {
                                var containers = document.querySelectorAll('mjx-container').length;
                                var errors = document.querySelectorAll('mjx-merror').length;
                                console.log('MATHJAX_RESULT containers=' + containers + ' errors=' + errors);
                            });
                        }
                        """.trimIndent(),
                        null,
                    )
                }
            }
        val scroll = binding.mathDebugScroll
        val content = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        content.addView(
            webView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.6f).toInt()),
        )
        val sourceHeader =
            TextView(requireContext()).apply {
                text = getString(R.string.ai_math_debug_source)
                textSize = 14f
                setPadding(0, 24, 0, 8)
            }
        content.addView(sourceHeader)
        val source =
            TextView(requireContext()).apply {
                text = sourceRows.toString()
                textSize = 11f
                typeface = Typeface.MONOSPACE
            }
        content.addView(source)
        scroll.removeAllViews()
        scroll.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        webView.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "utf-8", null)
    }

    companion object {
        /** Creates the launch intent for [SingleFragmentActivity]. */
        fun getIntent(context: Context): Intent = SingleFragmentActivity.getIntent(context, MathDebugFragment::class)
    }
}
