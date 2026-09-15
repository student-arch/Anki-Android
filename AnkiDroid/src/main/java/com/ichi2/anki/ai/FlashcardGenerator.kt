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
     * The user chose the exact number of cards, so the generator keeps making follow-up
     * requests until [count] unique cards pass the quality gate ([isQualityCard]): each
     * follow-up asks for only the missing cards and lists the questions already generated
     * so none are repeated. Duplicates and low-quality cards are dropped.
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
        var transientRetries = 0
        var requests = 0
        while (count == COUNT_AUTO || unique.size < count) {
            if (requests >= MAX_REQUESTS) break
            requests++
            val response =
                try {
                    requestGeneration(provider, modelId, material, count, includeImages, generatedFronts = unique.keys)
                } catch (e: AiException) {
                    if (!isTransient(e) || transientRetries >= MAX_TRANSIENT_RETRIES) throw e
                    transientRetries++
                    Timber.w(e, "transient AI failure (retry %d/%d), retrying", transientRetries, MAX_TRANSIENT_RETRIES)
                    delay(RETRY_DELAY_MS * transientRetries)
                    requests--
                    continue
                }
            val before = unique.size
            FlashcardParser
                .parse(response)
                .forEach { card ->
                    if (isQualityCard(card)) {
                        unique.putIfAbsent(card.front, card)
                    } else {
                        Timber.i("dropping low-quality card: %s", card.front.take(80))
                    }
                }
            // auto-count always finishes after one response (no count to top up);
            // a fixed count keeps requesting only the deficit until it is reached
            if (count == COUNT_AUTO) break
            if (unique.size < count) {
                Timber.i("have %d of %d requested cards, requesting the missing ones", unique.size, count)
            }
            // a follow-up that adds nothing new cannot make progress: stop instead of
            // looping (a first empty response still gets the one retry it always had)
            if (unique.size == before && requests >= 2) break
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
        generatedFronts: Set<String>,
    ): String =
        client.chatCompletion(
            provider = provider,
            modelId = modelId,
            systemPrompt = SYSTEM_PROMPT,
            userPrompt = buildUserPrompt(material, count, includeImages, generatedFronts),
        )

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

        /**
         * Upper bound on generation requests for one call, however many top-ups a short
         * response needs: a model that keeps returning duplicates must eventually give up.
         */
        private const val MAX_REQUESTS = 8

        /** Extra attempts for transient provider failures (5xx overload, network aborts). */
        private const val MAX_TRANSIENT_RETRIES = 3

        /** Base delay before retrying a transient failure; grows linearly per attempt. */
        private const val RETRY_DELAY_MS = 1_000L

        /** Cap on the already-generated-questions list embedded in a deficit request. */
        private const val MAX_EXISTING_QUESTIONS_LENGTH = 4000

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
            "You transform what the user provides into high-quality flashcards for Anki. " +
                "The user's input can contain two kinds of text: generation INSTRUCTIONS " +
                "(what type or format of cards they want, the focus, the style, how many) " +
                "and learning CONTENT (the topic, notes, document or material the cards must " +
                "teach). Follow the instructions faithfully; use only the content as the " +
                "source of facts. First infer the subject, the learner's level and the goal " +
                "(memorize definitions, apply formulas, solve numerical problems, understand " +
                "code, compare concepts, recall facts, prepare for interviews/exams...), then " +
                "choose the flashcard type and the exact set of sections that serve that goal " +
                "- never force a fixed template on every request. Each card tests exactly one " +
                "concept. Write in simple, plain language, matching the language of the " +
                "material. Do not number the cards. Respond with valid JSON only; no prose " +
                "outside the JSON."

        private const val CORE_RULES =
            "Core rules:\n" +
                "- Generate flashcards only from the information provided by the user; do not use " +
                "external knowledge to add facts, values, formulas, examples or explanations.\n" +
                "- Do not invent data: never fabricate numbers, constants, units, dates, " +
                "circuit values, material properties or standard values that are not in the " +
                "material. If a formula/numerical example is not supported by the content, omit it.\n" +
                "- Preserve important terminology, names, symbols, formulas, units, dimensions and " +
                "definitions exactly as the material states them.\n" +
                "- Do not change the meaning of the provided information; if it conflicts, keep it.\n" +
                "- Include a section ONLY when the card type you selected needs it and the " +
                "material supports it; otherwise omit it entirely (never leave a section empty " +
                "or fill it with generic text).\n" +
                "- Focus on important, learnable information; do not create unnecessary cards.\n" +
                "- Every card must be SELF-CONTAINED: never mention \"the material\", \"the text\", " +
                "\"the passage\", \"the user\" or \"the provided content\" inside a question or " +
                "answer. A student seeing only the card must understand it and know where the " +
                "content comes from.\n" +
                "- Never pad the output with duplicate, vague, trivial or off-topic cards; " +
                "every card must be strong, distinct and traceable to the material."

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
                "- If you do not reliably know a concept, do not invent it; cover the parts of " +
                "the topic you do know accurately.\n" +
                "- Every card must be SELF-CONTAINED: never mention \"the topic\" in a way that " +
                "only makes sense with the original input; each card must stand alone.\n" +
                "- Focus on important, learnable information; do not create unnecessary cards.\n" +
                "- Never pad the output with duplicate, vague, trivial or off-topic cards; " +
                "every card must be strong, distinct and accurate."

        /**
         * The intent model: read the user's instructions and the content's nature, then
         * pick ONE card type and only the sections it needs. The rich generic schema is
         * the fallback, never the default. Prompt-layer only: the parser, renderer and the
         * formula pipeline accept whatever subset of sections the chosen type emits.
         */
        private const val INTENT_RULES =
            "Adaptive card design (decide automatically from the input; the user configures nothing):\n" +
                "- If the input asks for a particular kind of card (\"just definitions\", " +
                "\"numerical problems\", \"code cards\", \"compare X and Y\", \"interview " +
                "questions\", \"short cards\"...), produce exactly that kind and give the " +
                "cards ONLY the sections that kind needs.\n" +
                "- Otherwise infer the best type from the content: glossaries/terminology -> " +
                "definition cards (term to meaning, no equations unless the content gives " +
                "them); laws and equations -> formula cards (the question asks for the " +
                "equation or its meaning; the answer carries the math plus, when the content " +
                "states them, the variables and units); worked calculations -> " +
                "problem-solving cards (given/formula/solution/final_answer); programming " +
                "content -> code/output cards; mechanisms and workflows -> ordered-steps " +
                "cards; closely related concepts -> comparison cards; dates/facts/events -> " +
                "short fact cards; biological/chemical processes -> explanation cards; " +
                "otherwise mixed concept cards.\n" +
                "- Use ONE consistent card type, section set and question-answer style across " +
                "the whole batch, unless the user asks for a mix or the content clearly needs " +
                "different kinds.\n" +
                "- Exclude everything that does not serve the chosen type: a definition batch " +
                "gets no equations or code; a formula batch no long prose or use-case lists; " +
                "a fact batch no key_points filler. Omitting a section entirely is always " +
                "better than padding it with generic text.\n" +
                "- Match phrasing and detail to the subject: exact equations, symbols and units " +
                "for quantitative fields; concise plain wording for humanities and languages; " +
                "real identifiers and output for programming.\n" +
                "- The full rich format (key_points, formula, variables, example, " +
                "common_mistake...) is only the fallback for quantitative content when no " +
                "narrower type fits."

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
                "the optional sections below ONLY when they serve the card type you selected AND " +
                "the material supports them - a section irrelevant to the chosen type is omitted, " +
                "even when the material could supply it:\n" +
                "- \"subject\": the subject area (e.g. \"Electrical Engineering\", \"Cell Biology\", \"Data Structures\").\n" +
                "- \"topic\": the specific topic (e.g. \"Ohm's Law\").\n" +
                "- \"question\": one focused question testing one concept, phrased in the style of " +
                "the chosen card type (definition, formula, calculation, comparison, code, " +
                "steps, fact, conceptual...); only in a deliberately mixed batch vary the " +
                "question types from card to card.\n" +
                "- \"answer\": the direct answer first, then a 1-3 sentence explanation of the key " +
                "point so the card teaches the concept (drawn only from the material); " +
                "accurate, using the correct terminology of the subject; wrap important " +
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
                "- Be concise and easy to revise quickly; use correct terminology for the " +
                "subject and explain any term the material introduces.\n" +
                "- The question must be specific and self-contained, name the exact concept being " +
                "tested, and must not hint at the answer.\n" +
                "- The answer must be self-contained and informative: state the direct answer " +
                "first, then briefly explain the key point (how or why it works) so the card " +
                "teaches the concept. A bare \"yes/no\", a lone word or a number without its " +
                "meaning is never an acceptable answer; use **bold** for key terms/symbols.\n" +
                "- Prefer one concept per card over cramming several facts into one answer.\n" +
                "Quality checklist before returning (skip items that do not apply to the\n" +
                "chosen card type):\n" +
                "- When a card shows formulas: variables and units must be correct and taken from " +
                "the material.\n" +
                "- For numerical cards: verify the calculation and the units, show the formula, and " +
                "state the final answer clearly with its unit.\n" +
                "- When you include a diagram: it must use standard conventions and correct " +
                "labels/arrows; never depict something technically wrong.\n" +
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
            generatedFronts: Set<String>,
        ): String {
            val source = if (isTopic) "the key concepts of the topic" else "the material"
            if (count == COUNT_AUTO) {
                return "Generate enough cards to cover the important information about $source: " +
                    "pick the number yourself from the material's depth and breadth (typically " +
                    "6-15), cover every major concept at least once, split large concepts into " +
                    "separate cards instead of repeating small ones, and prefer high-quality " +
                    "cards over unnecessary quantity."
            }
            if (generatedFronts.isEmpty()) {
                return "Generate EXACTLY $count flashcards about $source — neither fewer nor more. " +
                    "Cover as many distinct, important aspects as possible, by asking " +
                    "different question types (definition, conceptual, formula, calculation, " +
                    "application, comparison, process, diagram, troubleshooting) — one concept " +
                    "per card. This is a fixed requirement, not a maximum: the response must " +
                    "contain exactly $count cards."
            }
            // follow-up for a short response: ask for only the deficit, never repeating a card
            val deficit = count - generatedFronts.size
            val existing =
                generatedFronts
                    .joinToString("\n") { front -> "- $front" }
                    .take(MAX_EXISTING_QUESTIONS_LENGTH)
            return "A previous request for $count flashcards returned only ${generatedFronts.size}. " +
                "Generate EXACTLY $deficit additional flashcards about $source to complete the " +
                "request — neither fewer nor more. Each new card must cover a DIFFERENT concept " +
                "and must not duplicate or rephrase any question from this list of already " +
                "generated cards:\n$existing"
        }

        private fun buildUserPrompt(
            material: String,
            count: Int,
            includeImages: Boolean,
            generatedFronts: Set<String>,
        ): String {
            val isTopic = isTopicInput(material)
            val coreRules = if (isTopic) TOPIC_RULES else CORE_RULES
            val sourceLabel = if (isTopic) "Topic" else "Material"
            return "${countInstruction(count, isTopic, generatedFronts)}\n" +
                "Respond with JSON only, in this shape (omit unsupported sections" +
                "${if (includeImages) "; image_prompt is required and may be an empty string" else ""}):\n" +
                "{\"cards\": [{\"subject\": \"...\", \"topic\": \"...\", \"question\": \"...\", " +
                "\"answer\": \"...\", \"key_points\": [\"...\"], \"formula\": \"...\", " +
                "\"variables\": [\"...\"], \"units\": [\"...\"], \"example\": \"...\", " +
                "\"common_mistake\": \"...\", \"difficulty\": \"Easy\", \"tags\": [\"...\"]" +
                "${if (includeImages) ", \"image_prompt\": \"<diagram instruction or empty string>\"" else ""}]}]}\n\n" +
                "$coreRules\n\n$INTENT_RULES\n\n$MATH_RULES\n\n$SECTION_SPEC\n\n" +
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
