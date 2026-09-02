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

    /** Populates both task categories with the currently configured providers and models. */
    private fun refreshTaskSelections() {
        AiTaskType.entries.forEach { taskType -> refreshTaskSelection(taskType) }
    }

    private fun refreshTaskSelection(taskType: AiTaskType) {
        val providerPreference = requirePreference<ListPreference>(providerKey(taskType))
        val modelPreference = requirePreference<ListPreference>(modelKey(taskType))

        val providers = providerStore.getProviders()
        providerPreference.entries = providers.map { it.name }.toTypedArray()
        providerPreference.entryValues = providers.map { it.id }.toTypedArray()
        providerPreference.isEnabled = providers.isNotEmpty()

        val selection = providerStore.getSelection(taskType)
        if (selection != null && providers.none { it.id == selection.providerId }) {
            providerPreference.value = null
            providerPreference.summary = null
            modelPreference.value = null
            modelPreference.summary = getString(R.string.ai_model_none_selected)
            return
        }

        providerPreference.value = selection?.providerId
        providerPreference.summary = providers.firstOrNull { it.id == selection?.providerId }?.name
        providerPreference.setOnPreferenceChangeListener { _, newValue ->
            val providerId = newValue as String
            // reset the model whenever the provider changes
            providerPreference.summary = providers.firstOrNull { it.id == providerId }?.name
            modelPreference.value = null
            modelPreference.summary = getString(R.string.ai_model_none_selected)
            updateModelEntries(taskType, providerId)
            true
        }

        updateModelEntries(taskType, selection?.providerId)
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
        val models = providerStore.getProvider(providerId)?.modelIds.orEmpty()
        modelPreference.entries = models.toTypedArray()
        modelPreference.entryValues = models.toTypedArray()
        modelPreference.isEnabled = models.isNotEmpty()
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
}
