// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ai

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.protobuf.kotlin.toByteString
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.R
import com.ichi2.anki.libanki.Collection
import com.ichi2.anki.libanki.DeckId
import com.ichi2.anki.libanki.Note
import com.ichi2.anki.libanki.NotetypeJson
import com.ichi2.anki.model.SelectableDeck
import com.ichi2.anki.observability.undoableOp
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import timber.log.Timber

/**
 * State machine for the flashcard generation screen:
 * input -> generating -> reviewing -> adding -> done.
 */
sealed class GenerationUiState {
    /** The user is entering material and generation options. */
    data object Input : GenerationUiState()

    /** A generation request is in flight. */
    data object Generating : GenerationUiState()

    /** Generation produced [cards]; the user reviews, edits and accepts them. */
    data class Reviewing(
        val cards: List<GeneratedFlashcard>,
    ) : GenerationUiState()

    /** Accepted cards are being written to the collection. */
    data object Adding : GenerationUiState()

    /** [addedCount] cards were added to [deckName]. */
    data class Done(
        val addedCount: Int,
        val deckName: String,
    ) : GenerationUiState()
}

/** An error that aborts the flow and returns the user to the input step. */
data class GenerationError(
    val message: String,
)

/**
 * The provider/model pair selected for flashcard generation, with the provider's display name
 * for presentation on the input step.
 */
data class SelectedProviderModel(
    val providerId: String,
    val providerName: String,
    val modelId: String,
)

/** Per-card state of the asynchronously generated/downloaded image shown during review. */
sealed class CardImageState {
    /** The image request is in flight. */
    data object Generating : CardImageState()

    /** The image bytes are ready; [fileName] is the intended collection.media name. */
    class Ready(
        val fileName: String,
        val bytes: ByteArray,
    ) : CardImageState()

    /** Generation/download failed; the card is still usable without an image. */
    data object Failed : CardImageState()
}

/**
 * ViewModel for the flashcard generation screen: generates flashcards from user-provided
 * material via the configured AI provider, and adds the accepted ones to a deck. The whole
 * batch of requested cards is generated first and then displayed all at once in the review
 * list — no partial display, streaming or pagination.
 */
class FlashcardGenerationViewModel
    @JvmOverloads
    constructor(
        application: Application,
        private val generator: FlashcardGenerator = FlashcardGenerator(AiClient()),
        private val client: AiClient = AiClient(),
    ) : AndroidViewModel(application) {
        private val providerStore: AiProviderStore = AiProviderStore.getInstance(application)

        private val _uiState = MutableStateFlow<GenerationUiState>(GenerationUiState.Input)
        val uiState: StateFlow<GenerationUiState> = _uiState

        /** The in-flight generation job, cancelable via [backToInput]. */
        private var generationJob: Job? = null

        private val _error = MutableStateFlow<GenerationError?>(null)
        val error: StateFlow<GenerationError?> = _error

        private val _acceptedIds = MutableStateFlow<Set<Long>>(emptySet())
        val acceptedIds: StateFlow<Set<Long>> = _acceptedIds

        private val _imageStates = MutableStateFlow<Map<Long, CardImageState>>(emptyMap())

        /** Per-card image state, updated as async image generation completes during review. */
        val imageStates: StateFlow<Map<Long, CardImageState>> = _imageStates

        private val _retrySelection = MutableStateFlow<Set<Long>>(emptySet())

        /**
         * Card ids whose failed image the user selected for retry (failed images are selected by
         * default); [retrySelectedImages] regenerates only these.
         */
        val retrySelection: StateFlow<Set<Long>> = _retrySelection

        private val imageJobs = mutableMapOf<Long, Job>()
        private val imageSources = mutableMapOf<Long, ImageSource>()
        private val imageSemaphore = Semaphore(MAX_CONCURRENT_IMAGES)

        /** The deck chosen for adding the accepted cards (when using an existing deck). */
        var selectedDeck: SelectableDeck.Deck? = null

        /** Whether a flashcard provider/model pair is configured; false shows the setup hint. */
        val isConfigured: Boolean
            get() = providerStore.getSelection(AiTaskType.FLASHCARD) != null

        private val _selectedProviderModel = MutableStateFlow(providerModelFromStore())

        /**
         * The provider/model pair the flashcards are generated with. Selection made on this
         * screen and in AI settings share [AiProviderStore], so both stay in sync.
         */
        val selectedProviderModel: StateFlow<SelectedProviderModel?> = _selectedProviderModel

        /** Re-reads the selection from [AiProviderStore] (e.g. after returning from settings). */
        fun refreshSelectedProviderModel() {
            _selectedProviderModel.value = providerModelFromStore()
        }

        /**
         * Selects the provider/model pair used for flashcard generation, persisting it for the
         * whole app (AI settings shows the same pair). Unknown providers or models are ignored so
         * a stale picker entry can never be used silently.
         */
        fun selectProviderModel(
            providerId: String,
            modelId: String,
        ) {
            val provider = providerStore.getProvider(providerId) ?: return
            if (modelId !in provider.modelIds) return
            viewModelScope.launch {
                runCatching { providerStore.setSelection(AiTaskType.FLASHCARD, providerId, modelId) }
                    .onFailure { e ->
                        Timber.w(e, "failed to persist AI selection for %s", AiTaskType.FLASHCARD)
                    }
                _selectedProviderModel.value = SelectedProviderModel(provider.id, provider.name, modelId)
            }
        }

        private fun providerModelFromStore(): SelectedProviderModel? {
            val selection = providerStore.getSelection(AiTaskType.FLASHCARD) ?: return null
            val provider = providerStore.getProvider(selection.providerId) ?: return null
            return SelectedProviderModel(provider.id, provider.name, selection.modelId)
        }

        private val _selectedImageProviderModel = MutableStateFlow(imageProviderModelFromStore())
        private val _isImageGenerationEnabled = MutableStateFlow(_selectedImageProviderModel.value != null)

        /**
         * The provider/model pair images are generated with, or null when image generation is
         * disabled. Selection made on this screen and in AI settings share [AiProviderStore], so
         * both stay in sync.
         */
        val selectedImageProviderModel: StateFlow<SelectedProviderModel?> = _selectedImageProviderModel

        /** Whether image generation is on (an image provider/model is selected). */
        val isImageGenerationEnabled: StateFlow<Boolean> = _isImageGenerationEnabled

        /**
         * Enables or disables image generation. Disabling clears the image selection (the "None"
         * entry in AI settings does the same); enabling auto-selects the first configured
         * provider/model so image work can start immediately.
         */
        fun setImagesEnabled(enabled: Boolean) {
            viewModelScope.launch {
                if (enabled) {
                    if (providerStore.getSelection(AiTaskType.IMAGE) == null) {
                        // auto-select the first provider that can serve images
                        val provider =
                            providerStore.getProviders().firstOrNull { it.modelIds.isNotEmpty() } ?: run {
                                _error.value = GenerationError(getApplication<Application>().getString(R.string.ai_image_no_models))
                                _selectedImageProviderModel.value = null
                                _isImageGenerationEnabled.value = false
                                return@launch
                            }
                        runCatching { providerStore.setSelection(AiTaskType.IMAGE, provider.id, provider.modelIds.first()) }
                            .onFailure { e -> Timber.w(e, "failed to persist AI selection for %s", AiTaskType.IMAGE) }
                    }
                } else {
                    providerStore.clearSelection(AiTaskType.IMAGE)
                }
                refreshImageSelection()
            }
        }

        /**
         * Selects the provider/model pair used for image generation, persisting it for the whole
         * app (AI settings shows the same pair). Unknown providers or models are ignored so a
         * stale picker entry can never be used silently.
         */
        fun selectImageProviderModel(
            providerId: String,
            modelId: String,
        ) {
            val provider = providerStore.getProvider(providerId) ?: return
            if (modelId !in provider.modelIds) return
            viewModelScope.launch {
                runCatching { providerStore.setSelection(AiTaskType.IMAGE, providerId, modelId) }
                    .onFailure { e ->
                        Timber.w(e, "failed to persist AI selection for %s", AiTaskType.IMAGE)
                    }
                _selectedImageProviderModel.value = SelectedProviderModel(provider.id, provider.name, modelId)
                _isImageGenerationEnabled.value = true
            }
        }

        /** Re-reads the image selection from [AiProviderStore] (e.g. after returning from settings). */
        fun refreshImageSelection() {
            val selection =
                providerStore.getSelection(AiTaskType.IMAGE)?.let { stored ->
                    providerStore.getProvider(stored.providerId)?.let { provider ->
                        SelectedProviderModel(provider.id, provider.name, stored.modelId)
                    }
                }
            _selectedImageProviderModel.value = selection
            _isImageGenerationEnabled.value = selection != null
        }

        private fun imageProviderModelFromStore(): SelectedProviderModel? {
            val selection = providerStore.getSelection(AiTaskType.IMAGE) ?: return null
            val provider = providerStore.getProvider(selection.providerId) ?: return null
            return SelectedProviderModel(provider.id, provider.name, selection.modelId)
        }

        /** @return the cards currently under review, or empty if not in the review step. */
        fun currentCards(): List<GeneratedFlashcard> = (_uiState.value as? GenerationUiState.Reviewing)?.cards.orEmpty()

        /** @return the accepted cards, in display order. */
        fun acceptedCards(): List<GeneratedFlashcard> = currentCards().filter { it.id in _acceptedIds.value }

        fun toggleAccepted(
            cardId: Long,
            isAccepted: Boolean,
        ) {
            _acceptedIds.value =
                if (isAccepted) _acceptedIds.value + cardId else _acceptedIds.value - cardId
        }

        fun acceptAll() {
            _acceptedIds.value = currentCards().map { it.id }.toSet()
        }

        fun acceptNone() {
            _acceptedIds.value = emptySet()
        }

        /**
         * Requests flashcard generation for [material]. The screen stays on the generating
         * step until the whole batch of [count] cards is ready, then displays them all at
         * once in the review list.
         *
         * If the provider cannot supply the exact count, whatever unique quality-gated
         * cards it did return are displayed and the shortfall is surfaced as an error.
         */
        fun generate(
            material: String,
            count: Int,
        ) {
            val selection = providerStore.getSelection(AiTaskType.FLASHCARD)
            val provider = providerStore.getProvider(selection?.providerId)
            if (selection == null || provider == null) {
                _error.value = GenerationError(getApplication<Application>().getString(R.string.ai_error_not_configured))
                return
            }
            _uiState.value = GenerationUiState.Generating
            // image settings are fixed at generation time so a mid-flight toggle never
            // mixes imageless and image-bearing cards in one review batch
            val includeImages = providerStore.getSelection(AiTaskType.IMAGE) != null
            generationJob =
                viewModelScope.launch {
                    try {
                        // AiClient performs its I/O on Dispatchers.IO internally
                        val generated = generator.generate(provider, selection.modelId, material, count, includeImages)
                        // the model may emit image metadata despite being told not to;
                        // with images off, no card may carry, show or attach an image
                        val cards =
                            if (includeImages) generated else generated.map { it.copy(imageUrl = null, image = CardImage.None) }
                        if (cards.isEmpty()) {
                            _error.value = GenerationError(getApplication<Application>().getString(R.string.ai_error_no_cards))
                            _uiState.value = GenerationUiState.Input
                        } else {
                            if (cards.size < count) {
                                _error.value =
                                    GenerationError(
                                        getApplication<Application>().getString(R.string.ai_error_count_not_reached, cards.size, count),
                                    )
                            }
                            enterReview(cards)
                        }
                    } catch (e: AiException) {
                        Timber.w(e, "flashcard generation failed")
                        _error.value = GenerationError(e.message ?: getApplication<Application>().getString(R.string.ai_error_generic))
                        _uiState.value = GenerationUiState.Input
                    }
                }
        }

        /** Enters the review step with the complete batch [cards], resetting review state. */
        private fun enterReview(cards: List<GeneratedFlashcard>) {
            _acceptedIds.value = emptySet()
            imageJobs.values.forEach { it.cancel() }
            imageJobs.clear()
            imageSources.clear()
            _retrySelection.value = emptySet()
            _imageStates.value = emptyMap()
            _uiState.value = GenerationUiState.Reviewing(cards)
            startImageGeneration(cards)
        }

        /** Returns to the input step, discarding the generated cards and any in-flight images. */
        fun backToInput() {
            generationJob?.cancel()
            generationJob = null
            imageJobs.values.forEach { it.cancel() }
            imageJobs.clear()
            imageSources.clear()
            _retrySelection.value = emptySet()
            _acceptedIds.value = emptySet()
            _imageStates.value = emptyMap()
            _uiState.value = GenerationUiState.Input
        }

        /** Replaces the cards under review (e.g. after a rejection), dropping rejected selections. */
        fun updateCards(cards: List<GeneratedFlashcard>) {
            val keptIds = cards.map { it.id }.toSet()
            imageJobs.keys
                .toList()
                .filter { it !in keptIds }
                .forEach { imageJobs.remove(it)?.cancel() }
            imageSources.keys.retainAll(keptIds)
            _acceptedIds.value = _acceptedIds.value intersect keptIds
            _imageStates.value = _imageStates.value.filterKeys { it in keptIds }
            _retrySelection.value = _retrySelection.value intersect keptIds
            _uiState.value = GenerationUiState.Reviewing(cards)
            startImageGeneration(cards)
        }

        /** Applies an edit to the card with [cardId], keeping it in the same position. */
        fun updateCard(
            cardId: Long,
            front: String,
            back: String,
        ) {
            val current = _uiState.value as? GenerationUiState.Reviewing ?: return
            _uiState.value =
                GenerationUiState.Reviewing(
                    current.cards.map { card ->
                        if (card.id == cardId) card.copy(front = front, back = back) else card
                    },
                )
        }

        fun clearError() {
            _error.value = null
        }

        /** Selects or deselects [cardId]'s failed image for the next [retrySelectedImages] run. */
        fun toggleRetrySelection(
            cardId: Long,
            isSelected: Boolean,
        ) {
            _retrySelection.value =
                if (isSelected) _retrySelection.value + cardId else _retrySelection.value - cardId
        }

        /**
         * Regenerates the images of the selected failed cards only: each selected card whose
         * image state is [CardImageState.Failed] goes back to [CardImageState.Generating] and its
         * image is re-requested; on completion the review list updates through [imageStates].
         * Cards that are still generating, already ready or deselected are not touched.
         */
        fun retrySelectedImages() {
            val cardsById = currentCards().associateBy { it.id }
            val toRetry =
                _retrySelection.value
                    .mapNotNull(cardsById::get)
                    .filter { _imageStates.value[it.id] is CardImageState.Failed }
            if (toRetry.isEmpty()) return
            val imageSelection = providerStore.getSelection(AiTaskType.IMAGE)
            val imageProvider = providerStore.getProvider(imageSelection?.providerId)
            toRetry.forEach { card ->
                (imageSources[card.id] ?: imageSourceFor(card, imageProvider, imageSelection))?.let { requestImage(card, it) }
            }
        }

        /** Explicit regeneration uses the chosen model, even for cards with an existing image URL. */
        fun regenerateImages(
            cardIds: Set<Long>,
            providerId: String,
            modelId: String,
        ) {
            val provider = providerStore.getProvider(providerId) ?: return
            if (modelId !in provider.modelIds) return
            currentCards().filter { it.id in cardIds && imageJobs[it.id]?.isActive != true }.forEach { card ->
                val prompt =
                    imagePromptFor(card)?.takeIf { it.isNotBlank() }
                        ?: "Create an educational diagram illustrating this question and answer. " +
                        "Use only these facts, without adding labels or values not supplied: ${card.front}\n${card.back}"
                requestImage(card, ImageSource.Prompt(provider, modelId, prompt))
            }
        }

        private fun requestImage(
            card: GeneratedFlashcard,
            source: ImageSource,
        ) {
            if (imageJobs[card.id]?.isActive == true) return
            imageSources[card.id] = source
            val previous = _imageStates.value[card.id] as? CardImageState.Ready
            _imageStates.update { it + (card.id to CardImageState.Generating) }
            _retrySelection.update { it - card.id }
            val job =
                viewModelScope.launch(start = CoroutineStart.LAZY) {
                    val result = imageSemaphore.withPermit { fetchCardImage(source) }
                    ensureActive()
                    if (result is CardImageState.Failed && previous != null) {
                        _error.value = GenerationError(getApplication<Application>().getString(R.string.ai_image_replacement_failed))
                    }
                    _imageStates.update { it + (card.id to (if (result is CardImageState.Failed) previous ?: result else result)) }
                    if (result is CardImageState.Failed && previous == null) _retrySelection.update { it + card.id }
                }
            imageJobs[card.id] = job
            job.start()
        }

        /**
         * Adds the cards the user accepted to deck [deckId] (creating a deck named [newDeckName]
         * first when [createDeck] is true), as a single undoable operation.
         *
         * Only accepted cards are added; each card becomes one note (front -> back).
         */
        fun addAcceptedCards(
            accepted: List<GeneratedFlashcard>,
            deckId: DeckId?,
            createDeck: Boolean,
            newDeckName: String?,
        ) {
            val cardsToAdd = accepted.filter { it.front.isNotBlank() && it.back.isNotBlank() }
            if (cardsToAdd.isEmpty()) return
            _uiState.value = GenerationUiState.Adding
            viewModelScope.launch {
                try {
                    // wait for any image still being generated during review
                    imageJobs.values.toList().forEach { it.join() }
                    val targetDeckId =
                        when {
                            createDeck && !newDeckName.isNullOrBlank() -> withCol { decks.id(newDeckName.trim()) }
                            deckId != null -> deckId
                            else -> withCol { decks.selected() }
                        }
                    var addedCount = 0
                    val notes = buildNotes(cardsToAdd)
                    undoableOp {
                        val results = notes.map { addNote(it, targetDeckId) }
                        addedCount = results.sumOf { it.count }
                        // last expression must be an OpChanges subtype for ChangeManager
                        results.last()
                    }
                    _uiState.value =
                        GenerationUiState.Done(addedCount = addedCount, deckName = withCol { decks.name(targetDeckId) })
                } catch (e: Exception) {
                    Timber.e(e, "failed to add generated cards")
                    _error.value =
                        GenerationError(getApplication<Application>().getString(R.string.ai_error_add_failed))
                    _uiState.value = GenerationUiState.Reviewing(accepted)
                }
            }
        }

        /**
         * Starts async image generation for [cards] that need a visual, so images appear in the
         * review list before the cards are added to a deck:
         * - cards with an [GeneratedFlashcard.imageUrl] get it downloaded,
         * - cards whose structured [CardImage] is `required` get an image generated from its prompt
         *   via the user-configured IMAGE provider/model.
         *
         * Requests run in parallel (bounded by [MAX_CONCURRENT_IMAGES]); each result updates
         * [imageStates] per card, and a failure for one card never affects the others.
         * Cards that already have a state (e.g. after [updateCards]) are left untouched.
         */
        private fun startImageGeneration(cards: List<GeneratedFlashcard>) {
            // no image provider selected ("None" in settings) means the user turned image
            // generation off entirely: neither prompt-based generation nor downloads of
            // model-provided image URLs run, and no image is attached to the added cards
            val imageSelection = providerStore.getSelection(AiTaskType.IMAGE)
            val imageProvider = imageSelection?.let { providerStore.getProvider(it.providerId) }
            if (imageSelection == null || imageProvider == null) return
            val pending = cards.filter { _imageStates.value[it.id] == null }
            pending.forEach { card ->
                imageSourceFor(card, imageProvider, imageSelection)?.let { requestImage(card, it) }
            }
        }

        /**
         * @return the image work for [card] — download its URL, or generate from its prompt via
         * the user-configured IMAGE provider/model — or null when the card wants no image.
         */
        private fun imageSourceFor(
            card: GeneratedFlashcard,
            imageProvider: AiProvider?,
            imageSelection: AiProviderStore.TaskSelection?,
        ): ImageSource? =
            when {
                card.imageUrl != null -> ImageSource.Url(card.imageUrl)
                imageProvider != null && imageSelection != null ->
                    imagePromptFor(card)?.let { ImageSource.Prompt(imageProvider, imageSelection.modelId, it) }
                else -> null
            }

        /**
         * @return the prompt to generate [card]'s image, or null when no image is wanted. Uses the
         * model's own `image_prompt` when it requested one; otherwise, for clearly visual engineering
         * concepts the model under-requested an image for, synthesizes a grounded prompt from the
         * card's own text (so visual cards reliably get a diagram).
         */
        private fun imagePromptFor(card: GeneratedFlashcard): String? {
            if (card.image.required) return card.image.prompt
            if (!isVisualConcept(card)) return null
            val concept = card.topic ?: card.subject ?: "the concept"
            return "Create a clean educational engineering diagram explaining $concept: ${card.front} " +
                "Depict the elements described: ${card.back} " +
                "Use standard engineering symbols, clear readable labels, arrows for direction of " +
                "flow/force/current where relevant. Style: professional textbook diagram, plain " +
                "white background, minimal, high contrast, no decoration, no watermark."
        }

        /** @return true when [card]'s text describes a spatial/structural/process concept worth a diagram. */
        private fun isVisualConcept(card: GeneratedFlashcard): Boolean {
            val haystack = listOfNotNull(card.subject, card.topic, card.front, card.back).joinToString(" ").lowercase()
            return VISUAL_KEYWORDS.any { haystack.contains(it) }
        }

        private sealed interface ImageSource {
            data class Url(
                val url: String,
            ) : ImageSource

            data class Prompt(
                val provider: AiProvider,
                val modelId: String,
                val prompt: String,
            ) : ImageSource
        }

        /** @return the image bytes for [source] (download or generation), or [CardImageState.Failed]. */
        private suspend fun fetchCardImage(source: ImageSource): CardImageState =
            try {
                when (source) {
                    is ImageSource.Url ->
                        CardImageState.Ready(
                            fileName = imageFileNameFromUrl(source.url),
                            bytes = client.downloadImage(source.url),
                        )
                    is ImageSource.Prompt ->
                        CardImageState.Ready(
                            fileName = imageFileNameFromPrompt(source.prompt),
                            bytes = client.generateImage(source.provider, source.modelId, source.prompt),
                        )
                }
            } catch (e: AiException) {
                Timber.w(e, "failed to prepare card image; the card stays usable without it")
                CardImageState.Failed
            }

        /**
         * Builds the notes for [cards], writing every ready image into collection.media.
         * Must be called on the collection (inside [withCol]).
         */
        private fun Collection.writeNotes(
            cards: List<GeneratedFlashcard>,
            notetype: NotetypeJson,
        ): List<Note> =
            cards.map { card ->
                val note = newNote(notetype)
                note.setField(0, card.front)
                if (note.fields.size > 1) {
                    val image = _imageStates.value[card.id] as? CardImageState.Ready
                    val imageTag =
                        if (image != null) {
                            val stored = media.writeData(image.fileName, image.bytes.toByteString())
                            // max-width:100% + height:auto + object-fit:contain keeps the whole
                            // image visible and scaled to the card width, centered, never cropped
                            "<div style=\"text-align:center;\">" +
                                "<img src=\"$stored\" alt=\"${htmlAttr(card.image.alt ?: "")}\" style=\"max-width:100%;max-height:70vh;" +
                                "height:auto;width:auto;object-fit:contain;display:inline-block;\">" +
                                FlashcardRenderer.captionHtml(card) +
                                "</div>"
                        } else {
                            ""
                        }
                    note.setField(1, FlashcardRenderer.ankiBack(card) + imageTag)
                }
                note.tags.addAll(card.tags)
                note
            }

        private suspend fun buildNotes(cards: List<GeneratedFlashcard>): List<Note> =
            withCol {
                val notetype =
                    notetypes.byName(BASIC_NOTETYPE)
                        ?: notetypes.all().firstOrNull { it.isStd && it.fieldsNames.size >= 2 }
                        ?: notetypes.all().first()
                writeNotes(cards, notetype)
            }

        /** @return a media file name derived from [url]'s last path segment and content-safe. */
        private fun imageFileNameFromUrl(url: String): String {
            val name = url.substringBefore('?').substringAfterLast('/')
            val base = name.ifEmpty { "ai-image" }.take(FILE_NAME_MAX_LENGTH)
            val withExtension = if ('.' in base) base else "$base.png"
            return withExtension.replace(Regex("[^A-Za-z0-9._-]"), "_")
        }

        /** @return a stable media file name derived from [prompt] (same prompt -> same file name). */
        private fun imageFileNameFromPrompt(prompt: String): String =
            "ai-" +
                Integer.toHexString(prompt.hashCode()).padStart(8, '0') +
                ".png"

        /** Escapes [text] for safe use inside an HTML attribute value. */
        private fun htmlAttr(text: String): String =
            text
                .replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")

        companion object {
            private const val BASIC_NOTETYPE = "Basic"
            private const val FILE_NAME_MAX_LENGTH = 64
            private const val MAX_CONCURRENT_IMAGES = 3

            /** Terms whose concepts are inherently spatial/structural/process-based (want a diagram). */
            private val VISUAL_KEYWORDS =
                setOf(
                    "circuit",
                    "transformer",
                    "motor",
                    "engine",
                    "machine",
                    "turbine",
                    "pump",
                    "force",
                    "free-body",
                    "beam",
                    "truss",
                    "column",
                    "foundation",
                    "structure",
                    "flow",
                    "cycle",
                    "process",
                    "wave",
                    "waveform",
                    "graph",
                    "curve",
                    "network",
                    "protocol",
                    "topology",
                    "algorithm",
                    "stack",
                    "queue",
                    "tree",
                    "linked",
                    "binary",
                    "diagram",
                    "winding",
                    "induction",
                    "current",
                    "voltage",
                    "resistor",
                    "bond",
                    "reaction",
                    "distillation",
                    "exchanger",
                    "crystal",
                    "memory",
                    "cache",
                    "pipeline",
                    "lever",
                    "gear",
                    "pulley",
                    "load",
                    "stress",
                    "strain",
                    "osmosis",
                )
        }
    }
