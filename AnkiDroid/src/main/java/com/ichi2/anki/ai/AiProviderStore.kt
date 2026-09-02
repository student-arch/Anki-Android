// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Persists the user-configured AI providers and per-task provider/model selections.
 *
 * Provider API keys are stored in a dedicated preferences file, separate from the main
 * preferences, so they are not picked up by crash reporting. Configurations are not synced.
 *
 * Holds only a [SharedPreferences] reference, so the singleton never leaks a context.
 */
class AiProviderStore private constructor(
    prefs: SharedPreferences,
) {
    private val mutex = Mutex()

    private val preferences = prefs

    private fun prefs() = preferences

    /** @return all configured providers. Mutating the returned list does not persist changes. */
    fun getProviders(): List<AiProvider> {
        val array = prefs().getString(KEY_PROVIDERS, null) ?: return emptyList()
        return runCatching {
            val parsed = JSONArray(array)
            List(parsed.length()) { index -> parseProvider(parsed.getJSONObject(index)) }
        }.onFailure { e ->
            Timber.w(e, "failed to parse stored AI providers")
        }.getOrDefault(emptyList())
    }

    /**
     * Replaces the list of configured providers with [providers].
     * Per-task selections referencing removed providers are cleared.
     */
    suspend fun setProviders(providers: List<AiProvider>) {
        val json =
            JSONArray().apply {
                providers.forEach { provider -> put(toJson(provider)) }
            }
        mutex.withLock {
            prefs().edit { putString(KEY_PROVIDERS, json.toString()) }
        }
        val staleProviderIds = selectedProviderIds() - providers.map { it.id }.toSet()
        staleProviderIds.forEach { staleProviderId -> clearSelectionsForProvider(staleProviderId) }
    }

    /** @return the provider with [providerId], or null if it has been removed. */
    fun getProvider(providerId: String?): AiProvider? = getProviders().firstOrNull { it.id == providerId }

    /** @return the provider configured for [taskType], or null if not yet configured. */
    fun getProviderForTask(taskType: AiTaskType): AiProvider? = getProvider(getSelection(taskType)?.providerId)

    /**
     * Selects which provider and model to use for [taskType].
     * @throws IllegalArgumentException if [providerId] does not refer to a configured provider
     */
    suspend fun setSelection(
        taskType: AiTaskType,
        providerId: String,
        modelId: String,
    ) {
        require(getProvider(providerId) != null) { "unknown provider: $providerId" }
        mutex.withLock {
            prefs().edit {
                putString(
                    selectionKey(taskType),
                    JSONObject().put("provider", providerId).put("model", modelId).toString(),
                )
            }
        }
    }

    /** @return the provider/model selected for [taskType], or null if not yet configured. */
    fun getSelection(taskType: AiTaskType): TaskSelection? {
        val json = prefs().getString(selectionKey(taskType), null) ?: return null
        return runCatching {
            val obj = JSONObject(json)
            TaskSelection(providerId = obj.getString("provider"), modelId = obj.getString("model"))
        }.onFailure { e ->
            Timber.w(e, "failed to parse stored AI selection for $taskType")
        }.getOrNull()
    }

    /** Removes the selection for [taskType] (e.g. after its provider was deleted). */
    suspend fun clearSelection(taskType: AiTaskType) {
        mutex.withLock { prefs().edit { remove(selectionKey(taskType)) } }
    }

    private fun selectedProviderIds(): Set<String> =
        AiTaskType.entries.mapNotNull { taskType -> getSelection(taskType)?.providerId }.toSet()

    private suspend fun clearSelectionsForProvider(providerId: String) {
        AiTaskType.entries
            .filter { taskType -> getSelection(taskType)?.providerId == providerId }
            .forEach { taskType -> clearSelection(taskType) }
    }

    /** Removes all stored providers and selections. */
    fun clearAll() {
        prefs().edit { clear() }
    }

    private fun selectionKey(taskType: AiTaskType) = "$KEY_SELECTION_PREFIX${taskType.name}"

    private fun parseProvider(obj: JSONObject): AiProvider =
        AiProvider(
            id = obj.getString("id"),
            name = obj.getString("name"),
            baseUrl = obj.getString("baseUrl"),
            apiKey = obj.getString("apiKey"),
            modelIds =
                buildList {
                    val models = obj.optJSONArray("models") ?: return@buildList
                    for (i in 0 until models.length()) add(models.getString(i))
                },
        )

    private fun toJson(provider: AiProvider): JSONObject =
        JSONObject()
            .put("id", provider.id)
            .put("name", provider.name)
            .put("baseUrl", provider.baseUrl)
            .put("apiKey", provider.apiKey)
            .put("models", JSONArray(provider.modelIds))

    /** A provider + model pair selected for one [AiTaskType]. */
    data class TaskSelection(
        val providerId: String,
        val modelId: String,
    )

    companion object {
        private const val PREFS_NAME = "ai_provider_prefs"
        private const val KEY_PROVIDERS = "providers"
        private const val KEY_SELECTION_PREFIX = "selection_"

        @Volatile
        private var instance: AiProviderStore? = null

        /** Returns the singleton store, backed by a dedicated (non-synced) preferences file. */
        @JvmStatic
        fun getInstance(context: Context): AiProviderStore =
            instance
                ?: synchronized(this) {
                    instance
                        ?: AiProviderStore(
                            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
                        ).also { instance = it }
                }

        /** Test-only: discards the singleton so the next [getInstance] creates a fresh store. */
        fun resetForTests() {
            synchronized(this) { instance = null }
        }
    }
}
