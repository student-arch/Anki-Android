// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ai

import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import com.ichi2.anki.R
import com.ichi2.anki.preferences.SettingsFragment
import com.ichi2.anki.preferences.requirePreference
import com.ichi2.anki.snackbar.showSnackbar
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Settings screen for AI generation features:
 *
 * * management of configured providers (base URL + API key + models),
 * * independent provider/model selection for flashcard generation and image generation.
 */
class AiSettingsFragment : SettingsFragment() {
    override val preferenceResource: Int
        get() = R.xml.preferences_ai

    override val analyticsScreenNameConstant: String
        get() = "prefs.ai"

    private val providerStore: AiProviderStore by lazy {
        AiProviderStore.getInstance(requireContext().applicationContext)
    }
    private val client: AiClient by lazy { AiClient() }

    override fun initSubscreen() {
        setupProvidersEntry()
        setupRefreshModels()
        setupMathDebug()
        refreshTaskSelections()
    }

    override fun onResume() {
        super.onResume()
        // the provider list may have changed while the provider screen was open
        refreshTaskSelections()
    }

    private fun setupProvidersEntry() {
        val entry = requirePreference<Preference>(R.string.ai_providers_entry_key)
        entry.fragment = ProviderConfigFragment::class.java.name
        entry.summaryProvider =
            Preference.SummaryProvider<Preference> {
                val count = providerStore.getProviders().size
                if (count == 0) {
                    getString(R.string.ai_providers_none_configured)
                } else {
                    resources.getQuantityString(R.plurals.ai_providers_count, count, count)
                }
            }
    }

    private fun setupRefreshModels() {
        val refresh = requirePreference<Preference>(R.string.ai_refresh_models_key)
        refresh.setOnPreferenceClickListener {
            refreshAllProviderModels()
            true
        }
    }

    /** Opens the math rendering test screen (typesets the equation suite via MathJax). */
    private fun setupMathDebug() {
        val debug = requirePreference<Preference>(R.string.ai_math_debug_key)
        debug.setOnPreferenceClickListener {
            startActivity(MathDebugFragment.getIntent(requireContext()))
            true
        }
    }

    /** Populates both task categories with the currently configured providers and models. */
    private fun refreshTaskSelections() {
        AiTaskType.entries.forEach { taskType -> refreshTaskSelection(taskType) }
    }

    private fun refreshTaskSelection(taskType: AiTaskType) {
        val providerPreference = requirePreference<ListPreference>(providerKey(taskType))
        val modelPreference = requirePreference<ListPreference>(modelKey(taskType))

        val providers = providerStore.getProviders()
        // image generation can be explicitly turned off by picking "None"; flashcards always
        // need a provider, so only the image task offers the none entry
        val noneAllowed = taskType == AiTaskType.IMAGE
        val noneLabel = getString(R.string.ai_image_provider_none)
        val names =
            buildList {
                if (noneAllowed) add(noneLabel)
                addAll(providers.map { it.name })
            }
        val values =
            buildList {
                if (noneAllowed) add(VALUE_NONE)
                addAll(providers.map { it.id })
            }
        providerPreference.entries = names.toTypedArray()
        providerPreference.entryValues = values.toTypedArray()
        providerPreference.isEnabled = values.isNotEmpty()

        val selection = providerStore.getSelection(taskType)
        val selectedId =
            when {
                selection == null -> if (noneAllowed) VALUE_NONE else null
                providers.none { it.id == selection.providerId } -> if (noneAllowed) VALUE_NONE else null
                else -> selection.providerId
            }
        providerPreference.value = selectedId
        providerPreference.summary =
            when (selectedId) {
                VALUE_NONE -> noneLabel
                null -> null
                else -> providers.firstOrNull { it.id == selectedId }?.name
            }
        providerPreference.setOnPreferenceChangeListener { _, newValue ->
            val providerId = newValue as String
            if (providerId == VALUE_NONE) {
                // the user turned image generation off
                providerPreference.summary = noneLabel
                modelPreference.value = null
                modelPreference.isEnabled = false
                modelPreference.summary = getString(R.string.ai_images_off)
            } else {
                providerPreference.summary = providers.firstOrNull { it.id == providerId }?.name
                modelPreference.value = null
                modelPreference.summary = getString(R.string.ai_model_none_selected)
                updateModelEntries(taskType, providerId)
            }
            // clear the saved selection on every provider change: generation stays off until a
            // model is picked, so a stale provider/model pair can never be used silently
            viewLifecycleOwner.lifecycleScope.launch {
                providerStore.clearSelection(taskType)
            }
            true
        }

        if (selectedId == VALUE_NONE) {
            modelPreference.value = null
            modelPreference.isEnabled = false
            modelPreference.summary = getString(R.string.ai_images_off)
            return
        }

        updateModelEntries(taskType, selectedId)
        val modelId = selection?.modelId
        if (modelId != null) {
            modelPreference.value = modelId
            modelPreference.summary = modelId
        } else {
            modelPreference.summary = getString(R.string.ai_model_none_selected)
        }
        modelPreference.setOnPreferenceChangeListener { _, newValue ->
            val selectedModel = newValue as String
            val providerId = providerPreference.value ?: return@setOnPreferenceChangeListener false
            modelPreference.summary = selectedModel
            saveSelection(taskType, providerId, selectedModel)
            true
        }
    }

    private fun updateModelEntries(
        taskType: AiTaskType,
        providerId: String?,
    ) {
        val modelPreference = requirePreference<ListPreference>(modelKey(taskType))
        // every model the provider offers is selectable for every task; the user decides,
        // and a mismatched pick surfaces the provider's own error message
        val models = providerStore.getProvider(providerId)?.modelIds.orEmpty()
        modelPreference.entries = models.toTypedArray()
        modelPreference.entryValues = models.toTypedArray()
        modelPreference.isEnabled = models.isNotEmpty()
        if (models.isEmpty()) {
            modelPreference.summary = getString(R.string.ai_model_none_selected)
        }
    }

    private fun saveSelection(
        taskType: AiTaskType,
        providerId: String,
        modelId: String,
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { providerStore.setSelection(taskType, providerId, modelId) }
                .onFailure { e ->
                    Timber.w(e, "failed to persist AI selection for $taskType")
                }
        }
    }

    private fun providerKey(taskType: AiTaskType): Int =
        when (taskType) {
            AiTaskType.FLASHCARD -> R.string.ai_flashcard_provider_key
            AiTaskType.IMAGE -> R.string.ai_image_provider_key
        }

    private fun modelKey(taskType: AiTaskType): Int =
        when (taskType) {
            AiTaskType.FLASHCARD -> R.string.ai_flashcard_model_key
            AiTaskType.IMAGE -> R.string.ai_image_model_key
        }

    private fun refreshAllProviderModels() {
        viewLifecycleOwner.lifecycleScope.launch {
            val providers = providerStore.getProviders()
            if (providers.isEmpty()) {
                showSnackbar(R.string.ai_providers_none_configured)
                return@launch
            }
            var failures = 0
            providers.forEach { provider ->
                runCatching { client.listModels(provider) }
                    .onSuccess { models ->
                        providerStore.setProviders(
                            providerStore.getProviders().map {
                                if (it.id == provider.id) it.copy(modelIds = models) else it
                            },
                        )
                    }.onFailure { e ->
                        failures++
                        Timber.w(e, "failed to refresh models for %s", provider.name)
                    }
            }
            if (failures > 0) {
                showSnackbar(getString(R.string.ai_models_refresh_failed, failures))
            } else {
                showSnackbar(R.string.ai_models_refreshed)
            }
            refreshTaskSelections()
        }
    }

    private companion object {
        /** Picker value of the "None (don't generate images)" entry; never a real provider id. */
        private const val VALUE_NONE = "__none__"
    }
}
