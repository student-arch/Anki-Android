// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ai

import kotlinx.coroutines.delay
import timber.log.Timber

/**
 * Generates flashcards from user-provided learning material using a configured provider.
 *
 * Open so tests can provide a fake implementation.
 */
open class FlashcardGenerator(
    private val client: AiClient,
) {
    /**
     * Asks [provider]/[modelId] to produce flashcards for [material].
     *
     * Cards that fail the quality gate ([isQualityCard]) are dropped — the model is retried once
     * if too few usable cards come back — so the result can contain fewer cards than [count]
     * rather than vague or unsupported ones.
     *
     * Transient provider failures (HTTP 5xx overload, network aborts) are retried with a short
     * backoff: busy gateways commonly reject the first request but serve the retry.
     *
     * When [includeImages] is false, the prompt does not ask for image prompts and every parsed
     * card has its image metadata stripped, so no card carries or references an image.
     *
     * @param count the requested number of flashcards, or [COUNT_AUTO] to cover the material
     * @return the generated flashcards; empty if the model returned no usable cards
     * @throws AiException if the request fails
     */
    open suspend fun generate(
        provider: AiProvider,
        modelId: String,
        material: String,
        count: Int,
        includeImages: Boolean = true,
    ): List<GeneratedFlashcard> {
        Timber.i("generating %d flashcards with %s/%s", count, provider.name, modelId)
        val unique = LinkedHashMap<String, GeneratedFlashcard>()
        var attempts = 0
        var transientRetries = 0
        while (attempts < MAX_ATTEMPTS) {
            attempts++
            val response =
                try {
                    requestGeneration(provider, modelId, material, count, includeImages)
                } catch (e: AiException) {
                    if (!isTransient(e) || transientRetries >= MAX_TRANSIENT_RETRIES) throw e
                    transientRetries++
                    Timber.w(e, "transient AI failure (retry %d/%d), retrying", transientRetries, MAX_TRANSIENT_RETRIES)
                    delay(RETRY_DELAY_MS * transientRetries)
                    attempts--
                    continue
                }
            FlashcardParser
                .parse(response)
                .forEach { card ->
                    if (isQualityCard(card)) {
                        unique.putIfAbsent(card.front, card)
                    } else {
                        Timber.i("dropping low-quality card: %s", card.front.take(80))
                    }
                }
            if (count == COUNT_AUTO || unique.size >= count) break
            Timber.i("model returned %d of %d requested cards, retrying", unique.size, count)
        }
        val cards = if (count == COUNT_AUTO) unique.values.toList() else unique.values.take(count).toList()
        if (cards.isEmpty()) {
            Timber.w("no flashcards could be parsed from the response")
        }
        // the model may emit image metadata despite the instruction; never keep it when off
        return if (includeImages) cards else cards.map { it.copy(imageUrl = null, image = CardImage.None) }
    }

    /** Performs one chat-completion request for the generation step. */
    private suspend fun requestGeneration(
        provider: AiProvider,
        modelId: String,
        material: String,
        count: Int,
        includeImages: Boolean,
    ): String =
        client.chatCompletion(
            provider = provider,
            modelId = modelId,
            systemPrompt = SYSTEM_PROMPT,
            userPrompt = buildUserPrompt(material, count, includeImages),
        )

    /**
     * Streaming variant of [generate]: each card is passed to [onCard] the moment it completes
     * on the wire (parsed from the accumulated response after every delta), while generation
     * continues in the background. Cards already delivered are never re-emitted.
     *
     * The base implementation streams via [AiClient.chatCompletionStream]; providers without
     * SSE support return the whole body at once, whose cards are delivered from the final
     * text — degrading gracefully to all-at-once display.
     *
     * @return all emitted cards, in emission order
     * @throws AiException if the request fails
     */
    open suspend fun generateStream(
        provider: AiProvider,
        modelId: String,
        material: String,
        count: Int,
        includeImages: Boolean = true,
        onCard: suspend (GeneratedFlashcard) -> Unit,
    ): List<GeneratedFlashcard> {
        Timber.i("streaming %d flashcards with %s/%s", count, provider.name, modelId)
        val emitted = LinkedHashMap<String, GeneratedFlashcard>()

        /** Parses [text] and delivers every passing card not yet emitted. */
        suspend fun deliverFrom(text: String) {
            FlashcardParser
                .parse(text)
                .forEach { card ->
                    if (isQualityCard(card)) {
                        val fresh = emitted.putIfAbsent(card.front, card) == null
                        if (fresh) {
                            if (includeImages) {
                                onCard(card)
                            } else {
                                // never deliver image metadata when images are off
                                onCard(card.copy(imageUrl = null, image = CardImage.None))
                            }
                        }
                    } else {
                        Timber.i("dropping low-quality card: %s", card.front.take(80))
                    }
                }
        }
        val finalText =
            client.chatCompletionStream(
                provider = provider,
                modelId = modelId,
                systemPrompt = SYSTEM_PROMPT,
                userPrompt = buildUserPrompt(material, count, includeImages),
            ) { accumulated -> deliverFrom(accumulated) }
        // providers without SSE support return the whole body without invoking the delta
        // callback; the final text still yields its cards through the same path
        deliverFrom(finalText)
        if (emitted.isEmpty()) Timber.w("no flashcards could be parsed from the stream")
        return emitted.values.toList()
    }

    /**
     * Whether [e] is a transient provider failure worth retrying: server overload (HTTP 5xx)
     * or a network-level abort. Client errors (HTTP 4xx) and parse failures are permanent.
     */
    private fun isTransient(e: AiException): Boolean = e is AiException.Server || e is AiException.Network

    /**
     * Quality gate applied to every parsed card, guarding the model-side instructions with a
     * deterministic check so vague or meta cards never reach the review list:
     * - the question and answer must be self-contained (no references to "the material",
     *   "the text", "the passage", "the user" or similar);
     * - both sides must carry actual content (not one-word fragments);
     * - question and answer must not be the same text.
     */
    private fun isQualityCard(card: GeneratedFlashcard): Boolean {
        val front = card.front.trim()
        val back = card.back.trim()
        if (front.length < MIN_FRONT_LENGTH || back.length < MIN_BACK_LENGTH) return false
        if (front.equals(back, ignoreCase = true)) return false
        // questions naming the source are always meta ("...based on the material?")
        if (META_QUESTION_PHRASES.any { front.contains(it, ignoreCase = true) }) return false
        // answers only fail on the unambiguous meta phrases ("according to the material", ...)
        if (META_ANSWER_PHRASES.any { back.contains(it, ignoreCase = true) }) return false
        return true
    }

    companion object {
        /** Passed to [generate] when the user did not pick a fixed card count. */
        const val COUNT_AUTO = 0

        /** Extra generation attempts when the model returns fewer cards than requested. */
        private const val MAX_ATTEMPTS = 2

        /** Extra attempts for transient provider failures (5xx overload, network aborts). */
        private const val MAX_TRANSIENT_RETRIES = 3

        /** Base delay before retrying a transient failure; grows linearly per attempt. */
        private const val RETRY_DELAY_MS = 1_000L

        /** A question shorter than this cannot name a specific concept. */
        private const val MIN_FRONT_LENGTH = 10

        /** An answer shorter than this cannot explain anything (bare "Yes"/"42"/"Force"). */
        private const val MIN_BACK_LENGTH = 10

        /** Phrases that make a question meta (about the source) instead of about the concept. */
        private val META_QUESTION_PHRASES =
            listOf("material", "passage", "the text", "the user", "the article", "the paragraph", "the prompt", "given content")

        /** Unambiguously meta phrases for answers (kept narrower to avoid false positives). */
        private val META_ANSWER_PHRASES =
            listOf(
                "according to the material",
                "based on the material",
                "the provided material",
                "the given material",
                "as mentioned",
                "as stated",
                "the passage",
                "the user",
            )

        private const val SYSTEM_PROMPT =
            "You transform user-provided learning material into high-quality flashcards for " +
                "Anki, optimized for ENGINEERING STUDENTS. The user's provided topic, text, notes, " +
                "document or learning material is the ONLY knowledge source. First identify the " +
                "engineering branch and subject from the material (e.g. Computer Science/IT, " +
                "Electrical, Electronics & Communication, Mechanical, Civil, Chemical, Aerospace, " +
                "Biomedical, Automobile Engineering, Engineering Mathematics/Physics/Chemics/" +
                "Mechanics); if the material is not engineering, still make the best flashcards " +
                "from it. Structure each card the way an engineering student revises: precise " +
                "terminology, formulas with variables and units, worked numerical examples, and " +
                "clear diagrams \u2014 but ONLY using what the material provides. Each card tests " +
                "exactly one concept. Write in simple, plain language. Do not number the cards. " +
                "Respond with valid JSON only; no prose outside the JSON."

        private const val CORE_RULES =
            "Core rules:\n" +
                "- Generate flashcards only from the information provided by the user; do not use " +
                "external knowledge to add facts, values, formulas, examples or explanations.\n" +
                "- Do not invent engineering data: never fabricate numbers, constants, units, " +
                "circuit values, material properties or standard values that are not in the " +
                "material. If a formula/numerical example is not supported by the content, omit it.\n" +
                "- Preserve important terminology, names, symbols, formulas, units, dimensions and " +
                "definitions exactly as the material states them.\n" +
                "- Do not change the meaning of the provided information; if it conflicts, keep it.\n" +
                "- Include a section ONLY when the material supports it; otherwise omit it entirely " +
                "(never leave a section empty or fill it with generic text).\n" +
                "- Focus on important, learnable information; do not create unnecessary cards.\n" +
                "- Every card must be SELF-CONTAINED: never mention \"the material\", \"the text\", " +
                "\"the passage\", \"the user\" or \"the provided content\" inside a question or " +
                "answer. A student seeing only the card must understand it and know where the " +
                "content comes from.\n" +
                "- Never pad the output: if the material cannot support the requested number of " +
                "strong cards, return fewer. A vague, trivial, off-topic or invented card is worse " +
                "than a missing one."

        /**
         * Rules for topic-style input (a short subject name, as the input hint invites: "describe
         * a topic, e.g. Key concepts of photosynthesis"). A bare topic offers nothing to
         * extract, so under the strict grounding rules well-behaved models returned an empty
         * card list; here the model may draw on its own knowledge of the subject instead.
         */
        private const val TOPIC_RULES =
            "Core rules:\n" +
                "- The user named a topic, not full learning material. Create flashcards covering " +
                "the KEY CONCEPTS of this topic, using your own accurate knowledge of it.\n" +
                "- Where other instructions below mention \"the material\", treat your accurate " +
                "knowledge of the topic as the source.\n" +
                "- Be factually correct and precise; include the standard definitions, formulas, " +
                "units and terminology of the topic where they apply.\n" +
                "- If you do not reliably know the topic, return fewer cards rather than inventing.\n" +
                "- Every card must be SELF-CONTAINED: never mention \"the topic\" in a way that " +
                "only makes sense with the original input; each card must stand alone.\n" +
                "- Focus on important, learnable information; do not create unnecessary cards.\n" +
                "- Never pad the output: prefer fewer strong cards over weak ones."

        /**
         * Math is rendered by the viewer's bundled MathJax, so the model MUST wrap equations in
         * MathJax delimiters — raw LaTeX without them displays as literal text.
         * All the packages below are compiled into the app's MathJax build.
         */
        private const val MATH_RULES =
            "Mathematical notation rules (the app renders MathJax with LaTeX, mhchem and braket):\n" +
                "- Wrap EVERY formula, equation, expression and single symbol in MathJax delimiters: " +
                "inline math as \\( ... \\) and important/displayed equations as \\[ ... \\].\n" +
                "- Chemistry: use mhchem — \\( \\ce{H2O} \\), \\( \\ce{2H2 + O2 -> 2H2O} \\), " +
                "\\( \\ce{SO4^2-} \\); never write chemical subscripts with _ or ^ outside \\ce{...}.\n" +
                "- Quantum/Dirac notation: use braket — \\( \\ket{\\psi} = \\alpha\\ket{0} + \\beta\\ket{1} \\), " +
                "\\( \\braket{\\phi}{\\psi} \\), \\( \\braket{\\phi}{\\psi} = \\phi^{\\dagger}\\psi \\).\n" +
                "- Never emit Markdown math ($...$, $$...$$), Unicode pseudo-math or HTML entities; " +
                "always real LaTeX inside the delimiters: \\frac{...}{...}, \\sqrt{...}, " +
                "\\int_{a}^{b}, \\sum_{i=1}^{n}, \\lim_{x \\to 0}, \\begin{pmatrix} a & b \\\\ c & d \\end{pmatrix}.\n" +
                "- Code stays as plain text (it is rendered in a monospace block); do not put code in math delimiters."

        private const val SECTION_SPEC =
            "Each card is a JSON object. Always include \"question\" and \"answer\"; include any of " +
                "the optional sections below ONLY when the material supports them:\n" +
                "- \"subject\": the engineering branch/subject (e.g. \"Electrical Engineering\").\n" +
                "- \"topic\": the specific topic (e.g. \"Ohm's Law\").\n" +
                "- \"question\": one focused question testing one concept. Vary the type across cards: " +
                "definition, conceptual, formula, calculation, application, comparison, process, " +
                "diagram-interpretation, problem-solving, code, troubleshooting, design principle.\n" +
                "- \"answer\": the direct answer first, then a 1-3 sentence explanation of the key " +
                "point so the card teaches the concept (drawn only from the material); " +
                "technically accurate, using correct engineering terminology; wrap important " +
                "terms in **bold**.\n" +
                "- \"key_points\": array of 2-5 essential points (symbols, relationships, units).\n" +
                "- \"definition\", \"explanation\": only if present in the material.\n" +
                "- \"formula\": the equation(s) from the material; \"variables\": array of \"symbol = " +
                "meaning (unit)\" lines; \"units\": array of \"quantity = unit (symbol)\" lines. Only " +
                "when the material gives them.\n" +
                "- \"example\": a practical example/application from the material (numerical problem, " +
                "circuit, mechanism, structure, process, code, or step-by-step calculation).\n" +
                "- Numerical problems: use \"given\" (known values), \"formula\", \"solution\" (steps) " +
                "and \"final_answer\" (the result, with units). Verify every calculation and unit.\n" +
                "- \"code\", \"code_language\", \"code_explanation\", \"output\": only for programming " +
                "content present in the material.\n" +
                "- \"comparison\": only when the material describes two or more comparable concepts.\n" +
                "- \"common_mistake\": one relevant misconception/calculation error the material implies; " +
                "omit if none.\n" +
                "- \"diagram\": a simple text/ASCII diagram built only from structure/flow/relationships " +
                "in the material.\n" +
                "- \"use_cases\", \"advantages\", \"disadvantages\", \"practical_scenario\", " +
                "\"related_concepts\", \"interview_questions\", \"source\": only when in the material.\n" +
                "- \"difficulty\": \"Easy\" | \"Medium\" | \"Hard\".\n" +
                "- \"tags\": array of 2-5 short tags.\n" +
                "Section values may be multi-line strings or arrays; do not use Markdown fences inside " +
                "values (use **bold** for emphasis)."

        private const val IMAGE_SPEC =
            "Image (REQUIRED field on every card): add a top-level \"image_prompt\" string. Ask " +
                "\"would a diagram make THIS concept easier to understand?\" If YES (the concept is " +
                "spatial/structural/process/graphical \u2014 circuits, machines, free-body/force " +
                "diagrams, flow/cycle/process diagrams, graphs and waveforms, network/system/block " +
                "diagrams, algorithms/data structures, cross-sections), set \"image_prompt\" to a " +
                "precise engineering-diagram instruction: name the concept; list the exact parts to " +
                "draw; the relationships and directions (forces, current, flow, arrows); the labels " +
                "and values taken ONLY from the material; and end with the style line \"clean " +
                "professional engineering textbook diagram, plain white background, minimal, high " +
                "contrast, clear readable labels, standard engineering symbols, no decoration, no " +
                "watermark, no fake data\". If NO (a pure single-fact or definition card with nothing " +
                "to draw), set \"image_prompt\" to an empty string \"\". Never put decorative images."

        private const val QUALITY_RULES =
            "Writing style (every card):\n" +
                "- Be concise and easy to revise quickly; use correct engineering terminology and " +
                "explain any term the material introduces.\n" +
                "- The question must be specific and self-contained, name the exact concept being " +
                "tested, and must not hint at the answer.\n" +
                "- The answer must be self-contained and informative: state the direct answer " +
                "first, then briefly explain the key point (how or why it works) so the card " +
                "teaches the concept. A bare \"yes/no\", a lone word or a number without its " +
                "meaning is never an acceptable answer; use **bold** for key terms/symbols.\n" +
                "- Prefer one concept per card over cramming several facts into one answer.\n" +
                "Engineering quality checklist before returning:\n" +
                "- Formulas, variables and units must be correct and taken from the material.\n" +
                "- For numerical cards: verify the calculation and the units, show the formula, and " +
                "state the final answer clearly with its unit.\n" +
                "- Diagrams must use standard engineering conventions and correct labels/arrows; " +
                "never depict something technically wrong.\n" +
                "- Hide (omit) every section that is null, empty or not applicable.\n" +
                "- Remove duplicate, irrelevant and vague cards; every answer must be traceable to " +
                "the material and consistent with its question; do not hallucinate missing data. " +
                "Double-check every card for factual accuracy before including it; drop any card " +
                "you cannot ground in the material."

        /**
         * Whether [material] is a bare topic (a short subject name like "Qubit concept" or
         * "Key concepts of photosynthesis") rather than actual learning content. Topic-style
         * input has no sentences to extract from, so the strict material-only grounding
         * rules would leave the model nothing to build cards on.
         */
        internal fun isTopicInput(material: String): Boolean {
            val trimmed = material.trim()
            if (trimmed.length > MAX_TOPIC_LENGTH) return false
            // full material contains sentence punctuation; a topic is a noun phrase
            return !trimmed.contains('.') && !trimmed.contains(';') && !trimmed.contains('\n')
        }

        private const val MAX_TOPIC_LENGTH = 120

        private fun countInstruction(
            count: Int,
            isTopic: Boolean,
        ): String {
            val source = if (isTopic) "the key concepts of the topic" else "the material"
            return if (count == COUNT_AUTO) {
                "Generate enough cards to cover the important information about $source; " +
                    "prefer high-quality cards over unnecessary quantity."
            } else {
                "Generate up to $count flashcards about $source. Cover as many " +
                    "distinct, important aspects as possible, by asking " +
                    "different question types (definition, conceptual, formula, calculation, " +
                    "application, comparison, process, diagram, troubleshooting) — one concept " +
                    "per card. If the topic cannot support $count strong cards, return fewer " +
                    "rather than padding with vague, trivial or generic cards; never invent " +
                    "content to reach the count."
            }
        }

        private fun buildUserPrompt(
            material: String,
            count: Int,
            includeImages: Boolean,
        ): String {
            val isTopic = isTopicInput(material)
            val coreRules = if (isTopic) TOPIC_RULES else CORE_RULES
            val sourceLabel = if (isTopic) "Topic" else "Material"
            return "${countInstruction(count, isTopic)}\n" +
                "Respond with JSON only, in this shape (omit unsupported sections" +
                "${if (includeImages) "; image_prompt is required and may be an empty string" else ""}):\n" +
                "{\"cards\": [{\"subject\": \"...\", \"topic\": \"...\", \"question\": \"...\", " +
                "\"answer\": \"...\", \"key_points\": [\"...\"], \"formula\": \"...\", " +
                "\"variables\": [\"...\"], \"units\": [\"...\"], \"example\": \"...\", " +
                "\"common_mistake\": \"...\", \"difficulty\": \"Easy\", \"tags\": [\"...\"]" +
                "${if (includeImages) ", \"image_prompt\": \"<diagram instruction or empty string>\"" else ""}]}]}\n\n" +
                "$coreRules\n\n$MATH_RULES\n\n$SECTION_SPEC\n\n" +
                "${if (includeImages) IMAGE_SPEC else NO_IMAGE_SPEC}\n\n" +
                "$QUALITY_RULES\n\n" +
                "$sourceLabel:\n$material"
        }

        /** Tells the model (which may emit image fields anyway) that no image is wanted. */
        private const val NO_IMAGE_SPEC =
            "Image generation is turned OFF: do not include any image fields in the cards. " +
                "Omit \"image\" objects and diagram instructions entirely; do not describe " +
                "visuals, illustrations or diagrams to be drawn."
    }
}
