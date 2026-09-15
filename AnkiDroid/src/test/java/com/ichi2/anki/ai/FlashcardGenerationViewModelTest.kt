// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ai

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.RobolectricTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.anEmptyMap
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.instanceOf
import org.hamcrest.Matchers.`is`
import org.hamcrest.Matchers.not
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertIs

/**
 * End-to-end tests of flashcard generation and of adding only the accepted cards to a deck.
 */
@RunWith(AndroidJUnit4::class)
class FlashcardGenerationViewModelTest : RobolectricTest() {
    private lateinit var viewModel: FlashcardGenerationViewModel

    /** Always returns [count] fixed cards, without any network access. */
    private class FakeGenerator : FlashcardGenerator(AiClient()) {
        override suspend fun generate(
            provider: AiProvider,
            modelId: String,
            material: String,
            count: Int,
            includeImages: Boolean,
        ): List<GeneratedFlashcard> = List(count) { index -> GeneratedFlashcard(front = "Front $index", back = "Back $index") }
    }

    @Before
    fun setUpViewModel() {
        configureProvider()
        viewModel = FlashcardGenerationViewModel(ApplicationProvider.getApplicationContext(), FakeGenerator())
    }

    @After
    fun tearDownStore() {
        AiProviderStore.resetForTests()
    }

    private fun configureProvider() {
        val store = AiProviderStore.getInstance(targetContext)
        runBlocking {
            store.setProviders(
                listOf(
                    AiProvider(
                        id = "test-provider",
                        name = "Test Provider",
                        baseUrl = "https://test.example.com/v1",
                        apiKey = "test-key",
                        modelIds = listOf("test-model"),
                    ),
                ),
            )
            store.setSelection(AiTaskType.FLASHCARD, "test-provider", "test-model")
            store.setSelection(AiTaskType.IMAGE, "test-provider", "image-model")
        }
    }

    @Test
    fun `viewModel is created by the default factory used by the fragment`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val created = ViewModelProvider.AndroidViewModelFactory.getInstance(app).create(FlashcardGenerationViewModel::class.java)
        assertThat(created.isConfigured, `is`(true))
    }

    @Test
    fun `generate produces cards for review`() =
        runTest {
            viewModel.generate("photosynthesis", 10)
            RobolectricTest.advanceRobolectricLooperUntil(condition = { viewModel.uiState.value is GenerationUiState.Reviewing })
            val state = viewModel.uiState.value
            assertIs<GenerationUiState.Reviewing>(state)
            assertThat(state.cards, hasSize(10))
            assertThat(viewModel.acceptedIds.value, hasSize(0))
        }

    /**
     * A generator whose whole batch becomes visible only after a gate opens, proving the
     * review list is never shown in a partial state: the screen must stay on the generating
     * step until ALL 13 requested cards are ready, then display them in one shot.
     */
    private class GatedBatchGenerator(
        private val gate: CompletableDeferred<Unit>,
    ) : FlashcardGenerator(AiClient()) {
        override suspend fun generate(
            provider: AiProvider,
            modelId: String,
            material: String,
            count: Int,
            includeImages: Boolean,
        ): List<GeneratedFlashcard> {
            gate.await()
            return List(count) { index -> GeneratedFlashcard(front = "Front $index", back = "Back $index") }
        }
    }

    @Test
    fun `requested card count is displayed in full and all at once`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val vm =
                FlashcardGenerationViewModel(
                    ApplicationProvider.getApplicationContext(),
                    GatedBatchGenerator(gate),
                )
            vm.generate("photosynthesis", 13)

            // while the batch is incomplete nothing may be shown: no partial review list
            RobolectricTest.advanceRobolectricLooperUntil(condition = { vm.uiState.value is GenerationUiState.Generating })
            gate.complete(Unit)
            RobolectricTest.advanceRobolectricLooperUntil(condition = { vm.uiState.value is GenerationUiState.Reviewing })

            // every requested card is on screen at once — 13, not a fraction
            val state = vm.uiState.value
            assertIs<GenerationUiState.Reviewing>(state)
            assertThat(state.cards, hasSize(13))
        }

    @Test
    fun `shortfall below the requested count is surfaced as an error`() =
        runTest {
            val short =
                object : FlashcardGenerator(AiClient()) {
                    override suspend fun generate(
                        provider: AiProvider,
                        modelId: String,
                        material: String,
                        count: Int,
                        includeImages: Boolean,
                    ): List<GeneratedFlashcard> =
                        List(count - 6) { index -> GeneratedFlashcard(front = "Front $index", back = "Back $index") }
                }
            val vm = FlashcardGenerationViewModel(ApplicationProvider.getApplicationContext(), short)
            vm.generate("photosynthesis", 13)

            RobolectricTest.advanceRobolectricLooperUntil(condition = { vm.uiState.value is GenerationUiState.Reviewing })
            val state = vm.uiState.value
            assertIs<GenerationUiState.Reviewing>(state)
            assertThat(state.cards, hasSize(7))
            assertThat(vm.error.value, instanceOf(GenerationError::class.java))
        }

    @Test
    fun `generate without configuration reports an error`() =
        runTest {
            AiProviderStore.getInstance(targetContext).clearAll()
            val unconfiguredViewModel =
                FlashcardGenerationViewModel(ApplicationProvider.getApplicationContext(), FakeGenerator())
            unconfiguredViewModel.generate("photosynthesis", 5)
            assertThat(unconfiguredViewModel.uiState.value, instanceOf(GenerationUiState.Input::class.java))
            assertThat(unconfiguredViewModel.error.value, instanceOf(GenerationError::class.java))
        }

    @Test
    fun `accepted cards are added to a new deck`() =
        runTest {
            val cards =
                listOf(
                    GeneratedFlashcard(front = "Q1", back = "A1"),
                    GeneratedFlashcard(front = "Q2", back = "A2"),
                    GeneratedFlashcard(front = "Q3", back = "A3"),
                )
            viewModel.updateCards(cards)
            viewModel.toggleAccepted(cards[0].id, true)
            viewModel.toggleAccepted(cards[2].id, true)

            viewModel.addAcceptedCards(viewModel.acceptedCards(), deckId = null, createDeck = true, newDeckName = "AI Deck")
            RobolectricTest.advanceRobolectricLooperUntil(condition = { viewModel.uiState.value is GenerationUiState.Done })

            val state = viewModel.uiState.value
            assertIs<GenerationUiState.Done>(state)
            assertThat(state.addedCount, equalTo(2))
            assertThat(state.deckName, equalTo("AI Deck"))

            val noteIds = withCol { findNotes("deck:\"AI Deck\"") }
            assertThat(noteIds, hasSize(2))
            val fields = withCol { noteIds.map { nid -> getNote(nid).fields.toList() } }
            assertThat(fields, containsInAnyOrder(listOf("Q1", "A1"), listOf("Q3", "A3")))
        }

    @Test
    fun `accepted cards are added to an existing deck`() =
        runTest {
            val targetDeckId = withCol { decks.id("Target") }
            val cards = listOf(GeneratedFlashcard(front = "Q1", back = "A1"))
            viewModel.updateCards(cards)
            viewModel.toggleAccepted(cards[0].id, true)

            viewModel.addAcceptedCards(viewModel.acceptedCards(), deckId = targetDeckId, createDeck = false, newDeckName = null)
            RobolectricTest.advanceRobolectricLooperUntil(condition = { viewModel.uiState.value is GenerationUiState.Done })

            val noteIds = withCol { findNotes("deck:\"Target\"") }
            assertThat(noteIds, hasSize(1))
            val state = viewModel.uiState.value
            assertIs<GenerationUiState.Done>(state)
            assertThat(state.deckName, equalTo("Target"))
        }

    @Test
    fun `rejected cards are never added`() =
        runTest {
            val cards = listOf(GeneratedFlashcard(front = "Q1", back = "A1"))
            viewModel.updateCards(cards)
            // no card accepted

            viewModel.addAcceptedCards(viewModel.acceptedCards(), deckId = null, createDeck = true, newDeckName = "Empty")

            // nothing accepted -> nothing enqueued, state unchanged
            assertThat(viewModel.uiState.value, instanceOf(GenerationUiState.Reviewing::class.java))
            val deckNames = withCol { decks.allNamesAndIds().map { it.name } }
            assertThat(deckNames, not(`is`(containsInAnyOrder("Empty"))))
        }

    @Test
    fun `editing a card updates its content in place`() {
        val cards = listOf(GeneratedFlashcard(front = "Q1", back = "A1"))
        viewModel.updateCards(cards)
        viewModel.updateCard(cards[0].id, "Edited Q", "Edited A")
        val state = viewModel.uiState.value
        assertIs<GenerationUiState.Reviewing>(state)
        assertThat(state.cards[0].front, equalTo("Edited Q"))
        assertThat(state.cards[0].back, equalTo("Edited A"))
        assertThat(state.cards, hasSize(1))
    }

    @Test
    fun `front math is normalized when written to the note`() =
        runTest {
            val card = GeneratedFlashcard(front = "Solve ${'$'}x^2 = 4${'$'}", back = "x = \\pm 2")
            viewModel.updateCards(listOf(card))
            viewModel.toggleAccepted(card.id, true)

            viewModel.addAcceptedCards(viewModel.acceptedCards(), deckId = null, createDeck = true, newDeckName = "FrontMath")
            RobolectricTest.advanceRobolectricLooperUntil(condition = { viewModel.uiState.value is GenerationUiState.Done })

            val noteIds = withCol { findNotes("deck:\"FrontMath\"") }
            val note = withCol { getNote(noteIds.first()) }
            assertThat(note.fields[0], equalTo("""Solve \(x^2 = 4\)"""))
        }

    @Test
    fun `sections and tags are written to the added notes`() =
        runTest {
            val cards =
                listOf(
                    GeneratedFlashcard(
                        front = "Q1",
                        back = "A1",
                        sections = listOf(CardSection("Explanation", "why"), CardSection("Code (kotlin)", "val x = 1")),
                        tags = listOf("dsa", "Easy"),
                    ),
                )
            viewModel.updateCards(cards)
            viewModel.acceptAll()

            viewModel.addAcceptedCards(viewModel.acceptedCards(), deckId = null, createDeck = true, newDeckName = "Rich")
            RobolectricTest.advanceRobolectricLooperUntil(condition = { viewModel.uiState.value is GenerationUiState.Done })

            val noteIds = withCol { findNotes("deck:\"Rich\"") }
            assertThat(noteIds, hasSize(1))
            val note = withCol { getNote(noteIds.first()) }
            assertThat(note.fields[1], equalTo("A1<div><b>Explanation</b><br>why</div><div><b>Code (kotlin)</b><pre>val x = 1</pre></div>"))
            assertThat(note.tags, containsInAnyOrder("dsa", "Easy"))
        }

    @Test
    fun `image url is downloaded during review and referenced by the note`() =
        runTest {
            val png = byteArrayOf(0x89.toByte()) + "PNG".toByteArray()
            val client =
                object : AiClient() {
                    override suspend fun downloadImage(imageUrl: String): ByteArray = png
                }
            val vm = FlashcardGenerationViewModel(ApplicationProvider.getApplicationContext(), FakeGenerator(), client)
            val cards = listOf(GeneratedFlashcard(front = "Q1", back = "A1", imageUrl = "https://img.example.com/pics/diagram x.png"))
            vm.updateCards(cards)
            RobolectricTest.advanceRobolectricLooperUntil(condition = { vm.imageStates.value[cards[0].id] is CardImageState.Ready })
            assertThat((vm.imageStates.value[cards[0].id] as CardImageState.Ready).fileName, equalTo("diagram_x.png"))
            vm.acceptAll()

            vm.addAcceptedCards(vm.acceptedCards(), deckId = null, createDeck = true, newDeckName = "Imgs")
            RobolectricTest.advanceRobolectricLooperUntil(condition = { vm.uiState.value is GenerationUiState.Done })

            val noteIds = withCol { findNotes("deck:\"Imgs\"") }
            assertThat(noteIds, hasSize(1))
            val note = withCol { getNote(noteIds.first()) }
            assertThat(note.fields[1], containsString("<img src=\"diagram_x.png\""))
            assertThat(note.fields[1], containsString("max-width:100%"))
            assertThat(withCol { media.have("diagram_x.png") }, `is`(true))
        }

    @Test
    fun `failed image download marks the card failed and still adds it`() =
        runTest {
            val client =
                object : AiClient() {
                    override suspend fun downloadImage(imageUrl: String): ByteArray = throw AiException.Network("boom", null)
                }
            val vm = FlashcardGenerationViewModel(ApplicationProvider.getApplicationContext(), FakeGenerator(), client)
            val cards = listOf(GeneratedFlashcard(front = "Q1", back = "A1", imageUrl = "https://img.example.com/x.png"))
            vm.updateCards(cards)
            RobolectricTest.advanceRobolectricLooperUntil(condition = { vm.imageStates.value[cards[0].id] is CardImageState.Failed })
            vm.acceptAll()

            vm.addAcceptedCards(vm.acceptedCards(), deckId = null, createDeck = true, newDeckName = "NoImg")
            RobolectricTest.advanceRobolectricLooperUntil(condition = { vm.uiState.value is GenerationUiState.Done })

            val state = vm.uiState.value
            assertIs<GenerationUiState.Done>(state)
            assertThat(state.addedCount, equalTo(1))
            val noteIds = withCol { findNotes("deck:\"NoImg\"") }
            val note = withCol { getNote(noteIds.first()) }
            assertThat(note.fields[1], equalTo("A1"))
        }

    @Test
    fun `required image is generated during review and attached with caption on add`() =
        runTest {
            val png = byteArrayOf(0x89.toByte()) + "PNG".toByteArray()
            val client =
                object : AiClient() {
                    override suspend fun generateImage(
                        provider: AiProvider,
                        modelId: String,
                        prompt: String,
                    ): ByteArray {
                        assertThat(modelId, equalTo("image-model"))
                        assertThat(prompt, equalTo("Draw a qubit as two circles."))
                        return png
                    }
                }
            val vm = FlashcardGenerationViewModel(ApplicationProvider.getApplicationContext(), FakeGenerator(), client)
            val cards =
                listOf(
                    GeneratedFlashcard(
                        front = "Q1",
                        back = "A1",
                        image = CardImage(required = true, prompt = "Draw a qubit as two circles.", caption = "Qubit superposition"),
                    ),
                )
            vm.updateCards(cards)
            RobolectricTest.advanceRobolectricLooperUntil(condition = { vm.imageStates.value[cards[0].id] is CardImageState.Ready })
            vm.acceptAll()

            vm.addAcceptedCards(vm.acceptedCards(), deckId = null, createDeck = true, newDeckName = "GenImg")
            RobolectricTest.advanceRobolectricLooperUntil(condition = { vm.uiState.value is GenerationUiState.Done })

            val noteIds = withCol { findNotes("deck:\"GenImg\"") }
            assertThat(noteIds, hasSize(1))
            val note = withCol { getNote(noteIds.first()) }
            assertThat(note.fields[1], containsString("<img src=\"ai-"))
            assertThat(note.fields[1], containsString(".png\""))
            assertThat(note.fields[1], containsString("Qubit superposition"))
        }

    @Test
    fun `required image without configured IMAGE provider adds the card without an image`() =
        runTest {
            AiProviderStore.getInstance(targetContext).clearSelection(AiTaskType.IMAGE)
            val vm = FlashcardGenerationViewModel(ApplicationProvider.getApplicationContext(), FakeGenerator(), AiClient())
            val cards =
                listOf(GeneratedFlashcard(front = "Q1", back = "A1", image = CardImage(required = true, prompt = "Draw something.")))
            vm.updateCards(cards)
            vm.acceptAll()

            vm.addAcceptedCards(vm.acceptedCards(), deckId = null, createDeck = true, newDeckName = "NoImageCfg")
            RobolectricTest.advanceRobolectricLooperUntil(condition = { vm.uiState.value is GenerationUiState.Done })

            val noteIds = withCol { findNotes("deck:\"NoImageCfg\"") }
            val note = withCol { getNote(noteIds.first()) }
            assertThat(note.fields[1], equalTo("A1"))
        }

    @Test
    fun `visual concept without a model image prompt still gets a generated diagram`() =
        runTest {
            var generatedPrompt: String? = null
            val client =
                object : AiClient() {
                    override suspend fun generateImage(
                        provider: AiProvider,
                        modelId: String,
                        prompt: String,
                    ): ByteArray {
                        generatedPrompt = prompt
                        return byteArrayOf(0x89.toByte()) + "PNG".toByteArray()
                    }
                }
            val vm = FlashcardGenerationViewModel(ApplicationProvider.getApplicationContext(), FakeGenerator(), client)
            // a circuit card with NO image metadata from the model
            val cards =
                listOf(
                    GeneratedFlashcard(
                        front = "What is a series circuit?",
                        back = "A circuit where current flows through each resistor in turn.",
                        topic = "Series circuit",
                    ),
                )
            vm.updateCards(cards)
            RobolectricTest.advanceRobolectricLooperUntil(condition = { vm.imageStates.value[cards[0].id] is CardImageState.Ready })
            assertThat(generatedPrompt, containsString("series circuit"))
        }

    @Test
    fun `cards that do not require an image never trigger image generation`() =
        runTest {
            var generateCalls = 0
            val client =
                object : AiClient() {
                    override suspend fun generateImage(
                        provider: AiProvider,
                        modelId: String,
                        prompt: String,
                    ): ByteArray {
                        generateCalls++
                        return byteArrayOf(0x89.toByte())
                    }
                }
            val vm = FlashcardGenerationViewModel(ApplicationProvider.getApplicationContext(), FakeGenerator(), client)
            vm.updateCards(listOf(GeneratedFlashcard(front = "Q1", back = "A1")))
            RobolectricTest.advanceRobolectricLooperUntil(condition = { vm.uiState.value is GenerationUiState.Reviewing })
            assertThat(vm.imageStates.value, anEmptyMap())
            assertThat(generateCalls, equalTo(0))
        }

    @Test
    fun `adding a review card does not cancel images already loading`() =
        runTest {
            val response = CompletableDeferred<ByteArray>()
            val client =
                object : AiClient() {
                    override suspend fun downloadImage(imageUrl: String): ByteArray = response.await()
                }
            val vm = FlashcardGenerationViewModel(ApplicationProvider.getApplicationContext(), FakeGenerator(), client)
            val first = GeneratedFlashcard(front = "First", back = "Answer", imageUrl = "https://example.com/first.png")
            val second = GeneratedFlashcard(front = "Second", back = "Answer", imageUrl = "https://example.com/second.png")
            vm.updateCards(listOf(first))
            RobolectricTest.advanceRobolectricLooperUntil(condition = { vm.imageStates.value[first.id] is CardImageState.Generating })
            vm.updateCards(listOf(first, second))
            response.complete(byteArrayOf(1))
            RobolectricTest.advanceRobolectricLooperUntil(condition = { vm.imageStates.value[second.id] is CardImageState.Ready })
            assertIs<CardImageState.Ready>(vm.imageStates.value[first.id])
        }

    @Test
    fun `no image work runs at all when the image provider selection is removed`() =
        runTest {
            var downloadCalls = 0
            var generateCalls = 0
            val client =
                object : AiClient() {
                    override suspend fun downloadImage(imageUrl: String): ByteArray {
                        downloadCalls++
                        return byteArrayOf(1)
                    }

                    override suspend fun generateImage(
                        provider: AiProvider,
                        modelId: String,
                        prompt: String,
                    ): ByteArray {
                        generateCalls++
                        return byteArrayOf(2)
                    }
                }
            // user picked "None (don't generate images)" in settings
            AiProviderStore.getInstance(targetContext).clearSelection(AiTaskType.IMAGE)
            val vm = FlashcardGenerationViewModel(ApplicationProvider.getApplicationContext(), FakeGenerator(), client)
            val cards =
                listOf(
                    GeneratedFlashcard(front = "Q1", back = "A1", imageUrl = "https://example.com/x.png"),
                    GeneratedFlashcard(front = "Q2", back = "A2", image = CardImage(required = true, prompt = "Draw it.")),
                )
            vm.updateCards(cards)
            RobolectricTest.advanceRobolectricLooperUntil(condition = { vm.uiState.value is GenerationUiState.Reviewing })
            assertThat(vm.imageStates.value, anEmptyMap())
            assertThat(downloadCalls, equalTo(0))
            assertThat(generateCalls, equalTo(0))
            assertThat(vm.retrySelection.value, equalTo(emptySet()))
        }

    @Test
    fun `explicit image generation still works after the image provider is removed`() =
        runTest {
            val requested = mutableListOf<String>()
            val client =
                object : AiClient() {
                    override suspend fun generateImage(
                        provider: AiProvider,
                        modelId: String,
                        prompt: String,
                    ): ByteArray {
                        requested += "${provider.id}/$modelId"
                        return byteArrayOf(3)
                    }
                }
            AiProviderStore.getInstance(targetContext).clearSelection(AiTaskType.IMAGE)
            val vm = FlashcardGenerationViewModel(ApplicationProvider.getApplicationContext(), FakeGenerator(), client)
            val card = GeneratedFlashcard(front = "Q", back = "A", image = CardImage(required = true, prompt = "Draw it."))
            vm.updateCards(listOf(card))
            RobolectricTest.advanceRobolectricLooperUntil(condition = { vm.uiState.value is GenerationUiState.Reviewing })
            assertThat(vm.imageStates.value, anEmptyMap())

            vm.regenerateImages(setOf(card.id), "test-provider", "test-model")
            RobolectricTest.advanceRobolectricLooperUntil(condition = { vm.imageStates.value[card.id] is CardImageState.Ready })
            assertThat(requested, equalTo(listOf("test-provider/test-model")))
        }

    @Test
    fun `regeneration replaces only selected images using chosen provider and keeps text`() =
        runTest {
            val store = AiProviderStore.getInstance(targetContext)
            store.setProviders(
                store.getProviders() + AiProvider("other", "Other", "https://other.example.com/v1", "key", listOf("any-model")),
            )
            val requested = mutableListOf<String>()
            val response = CompletableDeferred<ByteArray>()
            val client =
                object : AiClient() {
                    override suspend fun downloadImage(imageUrl: String): ByteArray = byteArrayOf(1)

                    override suspend fun generateImage(
                        provider: AiProvider,
                        modelId: String,
                        prompt: String,
                    ): ByteArray {
                        requested += "${provider.id}/$modelId"
                        return response.await()
                    }
                }
            val vm = FlashcardGenerationViewModel(ApplicationProvider.getApplicationContext(), FakeGenerator(), client)
            val cards =
                listOf(
                    GeneratedFlashcard(front = "Q1", back = "A1", imageUrl = "https://example.com/one.png"),
                    GeneratedFlashcard(front = "Q2", back = "A2", imageUrl = "https://example.com/two.png"),
                )
            vm.updateCards(cards)
            RobolectricTest.advanceRobolectricLooperUntil(condition = {
                vm.imageStates.value.size == 2 &&
                    vm.imageStates.value.values
                        .all { it is CardImageState.Ready }
            })
            val untouched = vm.imageStates.value[cards[1].id]
            vm.regenerateImages(setOf(cards[0].id), "other", "any-model")
            assertIs<CardImageState.Generating>(vm.imageStates.value[cards[0].id])
            assertThat(vm.currentCards(), equalTo(cards))
            vm.regenerateImages(setOf(cards[0].id), "other", "any-model")
            response.complete(byteArrayOf(2))
            RobolectricTest.advanceRobolectricLooperUntil(condition = { vm.imageStates.value[cards[0].id] is CardImageState.Ready })
            assertThat(requested, equalTo(listOf("other/any-model")))
            assertThat(vm.imageStates.value[cards[1].id], equalTo(untouched))
            assertThat((vm.imageStates.value[cards[0].id] as CardImageState.Ready).bytes.toList(), equalTo(listOf(2.toByte())))
        }

    @Test
    fun `failed replacement preserves a previously generated image`() =
        runTest {
            val client =
                object : AiClient() {
                    override suspend fun downloadImage(imageUrl: String): ByteArray = byteArrayOf(1)

                    override suspend fun generateImage(
                        provider: AiProvider,
                        modelId: String,
                        prompt: String,
                    ): ByteArray = throw AiException.Network("quota exceeded", null)
                }
            val vm = FlashcardGenerationViewModel(ApplicationProvider.getApplicationContext(), FakeGenerator(), client)
            val card = GeneratedFlashcard(front = "Q", back = "A", imageUrl = "https://example.com/one.png")
            vm.updateCards(listOf(card))
            RobolectricTest.advanceRobolectricLooperUntil(condition = { vm.imageStates.value[card.id] is CardImageState.Ready })
            val previous = vm.imageStates.value[card.id]
            vm.regenerateImages(setOf(card.id), "test-provider", "test-model")
            RobolectricTest.advanceRobolectricLooperUntil(condition = { vm.error.value != null })
            assertThat(vm.imageStates.value[card.id], equalTo(previous))
            assertIs<GenerationUiState.Reviewing>(vm.uiState.value)
        }

    @Test
    fun `leaving review cancels replacement and clears image state`() =
        runTest {
            val response = CompletableDeferred<ByteArray>()
            val client =
                object : AiClient() {
                    override suspend fun generateImage(
                        provider: AiProvider,
                        modelId: String,
                        prompt: String,
                    ): ByteArray = response.await()
                }
            val vm = FlashcardGenerationViewModel(ApplicationProvider.getApplicationContext(), FakeGenerator(), client)
            val card = GeneratedFlashcard(front = "Q", back = "A")
            vm.updateCards(listOf(card))
            vm.regenerateImages(setOf(card.id), "test-provider", "test-model")
            assertIs<CardImageState.Generating>(vm.imageStates.value[card.id])
            vm.backToInput()
            response.complete(byteArrayOf(2))
            RobolectricTest.advanceRobolectricLooperUntil(condition = { vm.uiState.value is GenerationUiState.Input })
            assertThat(vm.imageStates.value, anEmptyMap())
            assertThat(vm.retrySelection.value, equalTo(emptySet()))
        }

    @Test
    fun `select all and deselect all update the accepted set`() =
        runTest {
            val cards =
                listOf(
                    GeneratedFlashcard(front = "Q1", back = "A1"),
                    GeneratedFlashcard(front = "Q2", back = "A2"),
                )
            viewModel.updateCards(cards)
            viewModel.acceptAll()
            assertThat(viewModel.acceptedCards().size, equalTo(2))
            viewModel.acceptNone()
            assertThat(viewModel.acceptedCards(), hasSize(0))
        }

    @Test
    fun `failed image is preselected and retried via try again`() =
        runTest {
            val png = byteArrayOf(0x89.toByte()) + "PNG".toByteArray()
            var calls = 0
            val client =
                object : AiClient() {
                    override suspend fun generateImage(
                        provider: AiProvider,
                        modelId: String,
                        prompt: String,
                    ): ByteArray {
                        calls++
                        if (calls == 1) throw AiException.Network("boom", null)
                        return png
                    }
                }
            val vm = FlashcardGenerationViewModel(ApplicationProvider.getApplicationContext(), FakeGenerator(), client)
            val cards =
                listOf(
                    GeneratedFlashcard(
                        front = "Q1",
                        back = "A1",
                        image = CardImage(required = true, prompt = "Draw a transformer."),
                    ),
                )
            vm.updateCards(cards)
            RobolectricTest.advanceRobolectricLooperUntil(condition = { vm.imageStates.value[cards[0].id] is CardImageState.Failed })
            // failed images are pre-selected so the batch Try Again is one tap
            assertThat(vm.retrySelection.value, equalTo(setOf(cards[0].id)))

            vm.retrySelectedImages()
            RobolectricTest.advanceRobolectricLooperUntil(condition = { vm.imageStates.value[cards[0].id] is CardImageState.Ready })
            assertThat(calls, equalTo(2))
        }

    @Test
    fun `try again regenerates only the selected failed images`() =
        runTest {
            val promptsRequested = mutableListOf<String>()
            val client =
                object : AiClient() {
                    override suspend fun generateImage(
                        provider: AiProvider,
                        modelId: String,
                        prompt: String,
                    ): ByteArray {
                        promptsRequested.add(prompt)
                        throw AiException.Network("always fails", null)
                    }
                }
            val vm = FlashcardGenerationViewModel(ApplicationProvider.getApplicationContext(), FakeGenerator(), client)
            val cards =
                listOf(
                    GeneratedFlashcard(
                        front = "Q1",
                        back = "A1",
                        image = CardImage(required = true, prompt = "Prompt one."),
                    ),
                    GeneratedFlashcard(
                        front = "Q2",
                        back = "A2",
                        image = CardImage(required = true, prompt = "Prompt two."),
                    ),
                )
            vm.updateCards(cards)
            RobolectricTest.advanceRobolectricLooperUntil(condition = {
                vm.imageStates.value.values
                    .all { it is CardImageState.Failed }
            })
            assertThat(promptsRequested.size, equalTo(2))

            // deselect the second card; only the first must be re-requested
            vm.toggleRetrySelection(cards[1].id, false)
            vm.retrySelectedImages()
            RobolectricTest.advanceRobolectricLooperUntil(condition = {
                vm.imageStates.value.values
                    .all { it is CardImageState.Failed }
            })
            assertThat(promptsRequested.size, equalTo(3))
            assertThat(promptsRequested.count { it == "Prompt one." }, equalTo(2))
            assertThat(promptsRequested.count { it == "Prompt two." }, equalTo(1))
        }

    @Test
    fun `retry skips deselected and non-failed images`() =
        runTest {
            var calls = 0
            val client =
                object : AiClient() {
                    override suspend fun generateImage(
                        provider: AiProvider,
                        modelId: String,
                        prompt: String,
                    ): ByteArray {
                        calls++
                        throw AiException.Network("always fails", null)
                    }
                }
            val vm = FlashcardGenerationViewModel(ApplicationProvider.getApplicationContext(), FakeGenerator(), client)
            val cards =
                listOf(
                    GeneratedFlashcard(
                        front = "Q1",
                        back = "A1",
                        image = CardImage(required = true, prompt = "Prompt one."),
                    ),
                )
            vm.updateCards(cards)
            RobolectricTest.advanceRobolectricLooperUntil(condition = { vm.imageStates.value[cards[0].id] is CardImageState.Failed })

            // a deselected failed card is not re-requested
            vm.toggleRetrySelection(cards[0].id, false)
            vm.retrySelectedImages()
            assertThat(calls, equalTo(1))

            // selecting it again retries it
            vm.toggleRetrySelection(cards[0].id, true)
            vm.retrySelectedImages()
            RobolectricTest.advanceRobolectricLooperUntil(condition = { calls == 2 })
        }

    @Test
    fun `selected provider and model are exposed on creation`() =
        runTest {
            assertThat(
                viewModel.selectedProviderModel.value,
                equalTo(SelectedProviderModel("test-provider", "Test Provider", "test-model")),
            )
        }

    @Test
    fun `selectedProviderModel is null when nothing is configured`() =
        runTest {
            AiProviderStore.getInstance(targetContext).clearAll()
            val unconfiguredViewModel =
                FlashcardGenerationViewModel(ApplicationProvider.getApplicationContext(), FakeGenerator())
            assertThat(unconfiguredViewModel.selectedProviderModel.value, equalTo(null))
        }

    @Test
    fun `selectProviderModel persists the choice and updates the exposed selection`() =
        runTest {
            val store = AiProviderStore.getInstance(targetContext)
            store.setProviders(
                store.getProviders() + AiProvider("other", "Other", "https://other.example.com/v1", "key", listOf("other-model")),
            )

            viewModel.selectProviderModel("other", "other-model")
            RobolectricTest.advanceRobolectricLooperUntil(condition = {
                viewModel.selectedProviderModel.value?.modelId == "other-model"
            })

            assertThat(
                viewModel.selectedProviderModel.value,
                equalTo(SelectedProviderModel("other", "Other", "other-model")),
            )
            assertThat(store.getSelection(AiTaskType.FLASHCARD), equalTo(AiProviderStore.TaskSelection("other", "other-model")))
        }

    @Test
    fun `generate uses the provider and model chosen on the screen`() =
        runTest {
            val store = AiProviderStore.getInstance(targetContext)
            store.setProviders(
                store.getProviders() + AiProvider("other", "Other", "https://other.example.com/v1", "key", listOf("other-model")),
            )
            val requested = mutableListOf<String>()
            val generator =
                object : FlashcardGenerator(AiClient()) {
                    override suspend fun generate(
                        provider: AiProvider,
                        modelId: String,
                        material: String,
                        count: Int,
                        includeImages: Boolean,
                    ): List<GeneratedFlashcard> {
                        requested += "${provider.id}/$modelId"
                        return listOf(GeneratedFlashcard(front = "Q", back = "A"))
                    }
                }
            val vm = FlashcardGenerationViewModel(ApplicationProvider.getApplicationContext(), generator)

            vm.selectProviderModel("other", "other-model")
            RobolectricTest.advanceRobolectricLooperUntil(condition = { vm.selectedProviderModel.value?.modelId == "other-model" })
            vm.generate("photosynthesis", 5)
            RobolectricTest.advanceRobolectricLooperUntil(condition = { vm.uiState.value is GenerationUiState.Reviewing })

            assertThat(requested, equalTo(listOf("other/other-model")))
        }

    @Test
    fun `selectProviderModel ignores an unknown provider or model`() =
        runTest {
            val store = AiProviderStore.getInstance(targetContext)

            viewModel.selectProviderModel("no-such-provider", "test-model")
            viewModel.selectProviderModel("test-provider", "no-such-model")
            RobolectricTest.advanceRobolectricLooperUntil(condition = { true })

            assertThat(
                viewModel.selectedProviderModel.value,
                equalTo(SelectedProviderModel("test-provider", "Test Provider", "test-model")),
            )
            assertThat(store.getSelection(AiTaskType.FLASHCARD), equalTo(AiProviderStore.TaskSelection("test-provider", "test-model")))
        }

    @Test
    fun `refreshSelectedProviderModel picks up changes made in settings`() =
        runTest {
            val store = AiProviderStore.getInstance(targetContext)
            store.setProviders(
                store.getProviders() + AiProvider("other", "Other", "https://other.example.com/v1", "key", listOf("other-model")),
            )
            // the user changed the selection in AI settings while this screen was open
            store.setSelection(AiTaskType.FLASHCARD, "other", "other-model")

            viewModel.refreshSelectedProviderModel()

            assertThat(
                viewModel.selectedProviderModel.value,
                equalTo(SelectedProviderModel("other", "Other", "other-model")),
            )
        }

    @Test
    fun `selecting a provider and model configures generation`() =
        runTest {
            AiProviderStore.getInstance(targetContext).clearAll()
            val unconfiguredViewModel =
                FlashcardGenerationViewModel(ApplicationProvider.getApplicationContext(), FakeGenerator())
            assertThat(unconfiguredViewModel.isConfigured, `is`(false))
            assertThat(unconfiguredViewModel.selectedProviderModel.value, equalTo(null))

            val store = AiProviderStore.getInstance(targetContext)
            store.setProviders(
                listOf(
                    AiProvider(
                        id = "test-provider",
                        name = "Test Provider",
                        baseUrl = "https://test.example.com/v1",
                        apiKey = "test-key",
                        modelIds = listOf("test-model"),
                    ),
                ),
            )
            unconfiguredViewModel.selectProviderModel("test-provider", "test-model")
            RobolectricTest.advanceRobolectricLooperUntil(condition = { unconfiguredViewModel.selectedProviderModel.value != null })

            assertThat(unconfiguredViewModel.isConfigured, `is`(true))
        }

    @Test
    fun `image generation is enabled with a configured image provider on creation`() =
        runTest {
            assertThat(viewModel.isImageGenerationEnabled.value, `is`(true))
            assertThat(
                viewModel.selectedImageProviderModel.value,
                equalTo(SelectedProviderModel("test-provider", "Test Provider", "image-model")),
            )
        }

    @Test
    fun `image generation is disabled when no image provider is selected`() =
        runTest {
            AiProviderStore.getInstance(targetContext).clearSelection(AiTaskType.IMAGE)
            val vm = FlashcardGenerationViewModel(ApplicationProvider.getApplicationContext(), FakeGenerator())

            assertThat(vm.isImageGenerationEnabled.value, `is`(false))
            assertThat(vm.selectedImageProviderModel.value, equalTo(null))
        }

    @Test
    fun `disabling image generation clears the image selection only`() =
        runTest {
            viewModel.setImagesEnabled(false)
            RobolectricTest.advanceRobolectricLooperUntil(condition = { !viewModel.isImageGenerationEnabled.value })

            assertThat(viewModel.isImageGenerationEnabled.value, `is`(false))
            assertThat(viewModel.selectedImageProviderModel.value, equalTo(null))
            assertThat(AiProviderStore.getInstance(targetContext).getSelection(AiTaskType.IMAGE), equalTo(null))
            // the flashcard selection is untouched
            assertThat(
                viewModel.selectedProviderModel.value,
                equalTo(SelectedProviderModel("test-provider", "Test Provider", "test-model")),
            )
        }

    @Test
    fun `enabling image generation auto-selects the first configured provider and model`() =
        runTest {
            val store = AiProviderStore.getInstance(targetContext)
            store.clearSelection(AiTaskType.IMAGE)

            viewModel.setImagesEnabled(true)
            RobolectricTest.advanceRobolectricLooperUntil(condition = { viewModel.selectedImageProviderModel.value != null })

            assertThat(viewModel.isImageGenerationEnabled.value, `is`(true))
            assertThat(
                viewModel.selectedImageProviderModel.value,
                equalTo(SelectedProviderModel("test-provider", "Test Provider", "test-model")),
            )
            assertThat(store.getSelection(AiTaskType.IMAGE), equalTo(AiProviderStore.TaskSelection("test-provider", "test-model")))
        }

    @Test
    fun `selectImageProviderModel persists and exposes the image choice`() =
        runTest {
            val store = AiProviderStore.getInstance(targetContext)
            store.setProviders(
                store.getProviders() +
                    AiProvider("imgprov", "Image Provider", "https://img.example.com/v1", "key", listOf("img-model-a", "img-model-b")),
            )

            viewModel.selectImageProviderModel("imgprov", "img-model-b")
            RobolectricTest.advanceRobolectricLooperUntil(
                condition = { viewModel.selectedImageProviderModel.value?.modelId == "img-model-b" },
            )

            assertThat(viewModel.isImageGenerationEnabled.value, `is`(true))
            assertThat(
                viewModel.selectedImageProviderModel.value,
                equalTo(SelectedProviderModel("imgprov", "Image Provider", "img-model-b")),
            )
            assertThat(store.getSelection(AiTaskType.IMAGE), equalTo(AiProviderStore.TaskSelection("imgprov", "img-model-b")))
        }

    @Test
    fun `refreshImageSelection picks up changes made in settings`() =
        runTest {
            AiProviderStore.getInstance(targetContext).clearSelection(AiTaskType.IMAGE)

            viewModel.refreshImageSelection()

            assertThat(viewModel.isImageGenerationEnabled.value, `is`(false))
            assertThat(viewModel.selectedImageProviderModel.value, equalTo(null))
        }

    /** Records the [FlashcardGenerator.generate] includeImages flag and returns one rich card. */
    private fun capturingGenerator(
        requested: MutableList<Boolean>,
        withImage: Boolean,
    ): FlashcardGenerator =
        object : FlashcardGenerator(AiClient()) {
            override suspend fun generate(
                provider: AiProvider,
                modelId: String,
                material: String,
                count: Int,
                includeImages: Boolean,
            ): List<GeneratedFlashcard> {
                requested += includeImages
                return listOf(
                    GeneratedFlashcard(
                        front = "Q with an image request",
                        back = "A detailed and self-contained answer.",
                        imageUrl = if (withImage) "https://img.example.com/pic.png" else null,
                        image =
                            if (withImage) {
                                CardImage(required = true, prompt = "Draw a diagram.")
                            } else {
                                CardImage.None
                            },
                    ),
                )
            }
        }

    @Test
    fun `generate omits image prompts when image generation is disabled`() =
        runTest {
            AiProviderStore.getInstance(targetContext).clearSelection(AiTaskType.IMAGE)
            val requested = mutableListOf<Boolean>()
            val vm =
                FlashcardGenerationViewModel(ApplicationProvider.getApplicationContext(), capturingGenerator(requested, withImage = true))

            vm.generate("photosynthesis", 5)
            RobolectricTest.advanceRobolectricLooperUntil(condition = { vm.uiState.value is GenerationUiState.Reviewing })

            assertThat(requested, equalTo(listOf(false)))
            val state = vm.uiState.value
            assertIs<GenerationUiState.Reviewing>(state)
            // even if the model emitted image metadata anyway, it must be stripped
            assertThat(state.cards.single().image, equalTo(CardImage.None))
            assertThat(state.cards.single().imageUrl, equalTo(null))
            assertThat(vm.imageStates.value, anEmptyMap())
        }

    @Test
    fun `generate requests and keeps image prompts when image generation is enabled`() =
        runTest {
            val requested = mutableListOf<Boolean>()
            val vm =
                FlashcardGenerationViewModel(ApplicationProvider.getApplicationContext(), capturingGenerator(requested, withImage = true))

            vm.generate("photosynthesis", 5)
            RobolectricTest.advanceRobolectricLooperUntil(condition = { vm.uiState.value is GenerationUiState.Reviewing })

            assertThat(requested, equalTo(listOf(true)))
            val state = vm.uiState.value
            assertIs<GenerationUiState.Reviewing>(state)
            assertThat(
                state.cards
                    .single()
                    .image.required,
                `is`(true),
            )
            assertThat(
                state.cards
                    .single()
                    .image.prompt,
                equalTo("Draw a diagram."),
            )
            RobolectricTest.advanceRobolectricLooperUntil(condition = { vm.imageStates.value.isNotEmpty() })
        }
}
