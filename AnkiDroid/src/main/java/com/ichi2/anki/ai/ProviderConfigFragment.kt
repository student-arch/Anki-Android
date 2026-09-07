// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ai

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.view.WindowInsetsCompat.Type.displayCutout
import androidx.core.view.WindowInsetsCompat.Type.systemBars
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.ichi2.anki.R
import com.ichi2.anki.databinding.DialogAiProviderBinding
import com.ichi2.anki.databinding.FragmentAiProvidersBinding
import com.ichi2.anki.launchCatchingTask
import com.ichi2.anki.preferences.PreferencesActivity
import com.ichi2.anki.snackbar.showSnackbar
import com.ichi2.anki.utils.doOnApplyWindowInsets
import com.ichi2.anki.withProgress
import com.ichi2.utils.customView
import com.ichi2.utils.negativeButton
import com.ichi2.utils.positiveButton
import com.ichi2.utils.show
import com.ichi2.utils.title
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Screen for managing the configured AI providers: adding, editing and deleting them.
 *
 * Each provider is an OpenAI-compatible API endpoint: a display name, a base URL and an API key.
 * Saving a provider validates the configuration by fetching the models the API offers.
 */
class ProviderConfigFragment : Fragment(R.layout.fragment_ai_providers) {
    private val providerStore: AiProviderStore by lazy {
        AiProviderStore.getInstance(requireContext().applicationContext)
    }
    private val client: AiClient by lazy { AiClient() }

    private var adapter: ProviderAdapter? = null

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        val binding = FragmentAiProvidersBinding.bind(view)
        adapter =
            ProviderAdapter(
                onEdit = { provider -> showEditorDialog(provider) },
                onDelete = { provider -> confirmDelete(provider) },
            )
        binding.providers.layoutManager = LinearLayoutManager(requireContext())
        binding.providers.adapter = adapter
        binding.addProvider.setOnClickListener { showEditorDialog(null) }
        binding.toolbar.setNavigationOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        applyInsets(binding)
        reload()
    }

    override fun onDestroyView() {
        adapter = null
        super.onDestroyView()
    }

    private fun reload() {
        val providers = providerStore.getProviders()
        adapter?.submitList(providers)
        view?.findViewById<View>(R.id.empty)?.isVisible = providers.isEmpty()
    }

    /** Keeps the toolbar, list and FAB clear of the system bars. */
    private fun applyInsets(binding: FragmentAiProvidersBinding) {
        binding.appbar.doOnApplyWindowInsets { view, insets, _ ->
            val bars = insets.getInsets(systemBars() or displayCutout())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right)
        }
        binding.providers.doOnApplyWindowInsets { view, insets, initial ->
            val bars = insets.getInsets(systemBars() or displayCutout())
            view.updatePadding(
                left = initial.padding.left + bars.left,
                right = initial.padding.right + bars.right,
                bottom = initial.padding.bottom + bars.bottom,
            )
        }
        binding.addProvider.doOnApplyWindowInsets { view, insets, initial ->
            val bars = insets.getInsets(systemBars() or displayCutout())
            view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = initial.margins.left + bars.left
                rightMargin = initial.margins.right + bars.right
                bottomMargin = initial.margins.bottom + bars.bottom
            }
        }
    }

    private fun showEditorDialog(existingProvider: AiProvider?) {
        val binding = DialogAiProviderBinding.inflate(layoutInflater)
        existingProvider?.let { provider ->
            binding.name.setText(provider.name)
            binding.baseUrl.setText(provider.baseUrl)
            binding.apiKey.setText(provider.apiKey)
            binding.models.setText(provider.modelIds.joinToString("\n"))
        }
        androidx.appcompat.app.AlertDialog
            .Builder(requireContext())
            .show {
                title(
                    if (existingProvider == null) R.string.ai_provider_add_title else R.string.ai_provider_edit_title,
                )
                customView(view = binding.root)
                positiveButton(R.string.save) {
                    val manualModels =
                        parseModels(
                            binding.models.text
                                .toString(),
                        )
                    validateAndSave(
                        AiProvider(
                            id = existingProvider?.id ?: AiProvider.newId(),
                            name =
                                binding.name.text
                                    .toString()
                                    .trim(),
                            baseUrl = AiClient.normalizeBaseUrl(binding.baseUrl.text.toString()),
                            apiKey =
                                binding.apiKey.text
                                    .toString()
                                    .trim(),
                            modelIds = manualModels,
                        ),
                        manualModels,
                    )
                }
                negativeButton(R.string.dialog_cancel)
            }
    }

    /** Splits a comma- and/or newline-separated model list into distinct non-blank ids. */
    private fun parseModels(text: String): List<String> =
        text
            .split(',', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    private fun validateAndSave(
        provider: AiProvider,
        manualModels: List<String>,
    ) {
        if (provider.name.isEmpty() || provider.baseUrl.isEmpty()) {
            showSnackbar(R.string.ai_provider_missing_fields)
            return
        }
        launchCatchingTask {
            withProgress(getString(R.string.ai_provider_testing)) {
                runCatching { client.listModels(provider) }
                    .onSuccess { fetched ->
                        val models = (fetched + manualModels).distinct()
                        Timber.i("provider '%s' offers %d models", provider.name, models.size)
                        persist(provider.copy(modelIds = models))
                        showSnackbar(getString(R.string.ai_provider_validated, models.size))
                    }.onFailure { e ->
                        // Some providers (e.g. Cloudflare AI) don't support GET /models (HTTP 405).
                        // That is not fatal: fall back to the models the user typed manually.
                        Timber.w(e, "model listing failed for %s", provider.name)
                        if (manualModels.isNotEmpty()) {
                            persist(provider.copy(modelIds = manualModels))
                            showSnackbar(getString(R.string.ai_provider_saved_manual))
                        } else {
                            showNeedsModelsDialog(provider, e)
                        }
                    }
            }
        }
    }

    /** Explains that model listing is unsupported and lets the user save anyway or add models. */
    private fun showNeedsModelsDialog(
        provider: AiProvider,
        e: Throwable,
    ) {
        val code = Regex("HTTP (\\d+)").find(e.message.orEmpty())?.groupValues?.get(1) ?: "?"
        androidx.appcompat.app.AlertDialog
            .Builder(requireContext())
            .setTitle(R.string.ai_provider_test_failed)
            .setMessage(getString(R.string.ai_provider_needs_manual_models, code))
            .setPositiveButton(R.string.save_anyway) { _, _ -> persist(provider) }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun persist(provider: AiProvider) {
        lifecycleScope.launch {
            val current = providerStore.getProviders()
            val updated =
                if (current.any { it.id == provider.id }) {
                    current.map { if (it.id == provider.id) provider else it }
                } else {
                    current + provider
                }
            providerStore.setProviders(updated)
            reload()
        }
    }

    private fun confirmDelete(provider: AiProvider) {
        androidx.appcompat.app.AlertDialog
            .Builder(requireContext())
            .setTitle(R.string.ai_provider_delete_title)
            .setMessage(getString(R.string.ai_provider_delete_message, provider.name))
            .setPositiveButton(R.string.dialog_positive_delete) { _, _ ->
                lifecycleScope.launch {
                    providerStore.setProviders(providerStore.getProviders().filter { it.id != provider.id })
                    reload()
                }
            }.setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    companion object {
        /** Opens this screen, e.g. when the generation screen finds no configured provider. */
        fun openFrom(context: Context) {
            context.startActivity(PreferencesActivity.getIntent(context, ProviderConfigFragment::class))
        }
    }
}
