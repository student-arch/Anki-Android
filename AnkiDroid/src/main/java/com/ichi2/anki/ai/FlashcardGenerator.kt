// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ai

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
        while (attempts < MAX_ATTEMPTS) {
            attempts++
            val response =
                client.chatCompletion(
                    provider = provider,
                    modelId = modelId,
                    systemPrompt = SYSTEM_PROMPT,
                    userPrompt = buildUserPrompt(material, count, includeImages),
                )
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

        private fun countInstruction(count: Int): String =
            if (count == COUNT_AUTO) {
                "Generate enough cards to cover the important information in the material; " +
                    "prefer high-quality cards over unnecessary quantity."
            } else {
                "Generate up to $count flashcards from the following material. Cover as many " +
                    "distinct, important aspects of the material as it supports, by asking " +
                    "different question types (definition, conceptual, formula, calculation, " +
                    "application, comparison, process, diagram, troubleshooting) — one concept " +
                    "per card, every card strictly derived from the material. If the material " +
                    "cannot support $count strong cards, return fewer rather than padding with " +
                    "vague, trivial or generic cards; never invent content to reach the count."
            }

        private fun buildUserPrompt(
            material: String,
            count: Int,
            includeImages: Boolean,
        ): String =
            "${countInstruction(count)}\n" +
                "Respond with JSON only, in this shape (omit unsupported sections" +
                "${if (includeImages) "; image_prompt is required and may be an empty string" else ""}):\n" +
                "{\"cards\": [{\"subject\": \"...\", \"topic\": \"...\", \"question\": \"...\", " +
                "\"answer\": \"...\", \"key_points\": [\"...\"], \"formula\": \"...\", " +
                "\"variables\": [\"...\"], \"units\": [\"...\"], \"example\": \"...\", " +
                "\"common_mistake\": \"...\", \"difficulty\": \"Easy\", \"tags\": [\"...\"]" +
                "${if (includeImages) ", \"image_prompt\": \"<diagram instruction or empty string>\"" else ""}]}]}\n\n" +
                "$CORE_RULES\n\n$SECTION_SPEC\n\n" +
                "${if (includeImages) IMAGE_SPEC else NO_IMAGE_SPEC}\n\n" +
                "$QUALITY_RULES\n\n" +
                "Material:\n$material"

        /** Tells the model (which may emit image fields anyway) that no image is wanted. */
        private const val NO_IMAGE_SPEC =
            "Image generation is turned OFF: do not include any image fields in the cards. " +
                "Omit \"image\" objects and diagram instructions entirely; do not describe " +
                "visuals, illustrations or diagrams to be drawn."
    }
}
