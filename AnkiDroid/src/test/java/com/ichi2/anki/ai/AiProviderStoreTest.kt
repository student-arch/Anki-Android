// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.RobolectricTest
import kotlinx.coroutines.test.runTest
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.empty
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.nullValue
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Tests for persisting providers and per-task selections. */
@RunWith(AndroidJUnit4::class)
class AiProviderStoreTest : RobolectricTest() {
    private val providerA =
        AiProvider(
            id = "provider-a",
            name = "Provider A",
            baseUrl = "https://a.example.com/v1",
            apiKey = "key-a",
            modelIds = listOf("model-a1", "model-a2"),
        )
    private val providerB =
        AiProvider(
            id = "provider-b",
            name = "Provider B",
            baseUrl = "https://b.example.com/v1",
            apiKey = "key-b",
        )

    @Before
    fun resetStore() {
        AiProviderStore.resetForTests()
    }

    @After
    fun resetStoreAgain() {
        AiProviderStore.resetForTests()
    }

    private fun store(): AiProviderStore = AiProviderStore.getInstance(targetContext)

    @Test
    fun `empty by default`() =
        runTest {
            assertThat(store().getProviders(), empty())
            assertThat(store().getSelection(AiTaskType.FLASHCARD), nullValue())
        }

    @Test
    fun `providers survive a new instance`() =
        runTest {
            store().setProviders(listOf(providerA, providerB))
            // drop the singleton to force a fresh read from preferences
            AiProviderStore.resetForTests()
            val providers = store().getProviders()
            assertThat(providers, hasSize(2))
            assertThat(providers.map { it.id }, containsInAnyOrder("provider-a", "provider-b"))
            assertThat(providers.first { it.id == "provider-a" }.modelIds, containsInAnyOrder("model-a1", "model-a2"))
            assertThat(providers.first { it.id == "provider-a" }.apiKey, equalTo("key-a"))
        }

    @Test
    fun `selection is persisted per task`() =
        runTest {
            store().setProviders(listOf(providerA, providerB))
            store().setSelection(AiTaskType.FLASHCARD, providerA.id, "model-a1")
            store().setSelection(AiTaskType.IMAGE, providerB.id, "model-b1")
            assertThat(store().getSelection(AiTaskType.FLASHCARD)?.modelId, equalTo("model-a1"))
            assertThat(store().getSelection(AiTaskType.IMAGE)?.modelId, equalTo("model-b1"))
        }

    @Test
    fun `selection for unknown provider is rejected`() =
        runTest {
            assertThrows(IllegalArgumentException::class.java) {
                kotlinx.coroutines.runBlocking { store().setSelection(AiTaskType.FLASHCARD, "missing", "m") }
            }
        }

    @Test
    fun `removing a provider clears its selections`() =
        runTest {
            store().setProviders(listOf(providerA, providerB))
            store().setSelection(AiTaskType.FLASHCARD, providerA.id, "model-a1")
            store().setSelection(AiTaskType.IMAGE, providerB.id, "model-b1")

            store().setProviders(listOf(providerB))

            assertThat(store().getSelection(AiTaskType.FLASHCARD), nullValue())
            assertThat(store().getSelection(AiTaskType.IMAGE)?.providerId, equalTo("provider-b"))
        }

    @Test
    fun `updating a provider keeps its selection`() =
        runTest {
            store().setProviders(listOf(providerA))
            store().setSelection(AiTaskType.FLASHCARD, providerA.id, "model-a1")
            store().setProviders(listOf(providerA.copy(apiKey = "new-key")))
            assertThat(store().getSelection(AiTaskType.FLASHCARD)?.modelId, equalTo("model-a1"))
            assertThat(store().getProvider("provider-a")?.apiKey, equalTo("new-key"))
        }

    @Test
    fun `clearSelection removes the task selection`() =
        runTest {
            store().setProviders(listOf(providerA))
            store().setSelection(AiTaskType.FLASHCARD, providerA.id, "model-a1")
            store().clearSelection(AiTaskType.FLASHCARD)
            assertThat(store().getSelection(AiTaskType.FLASHCARD), nullValue())
        }

    @Test
    fun `getProviderForTask resolves the configured provider`() =
        runTest {
            store().setProviders(listOf(providerA, providerB))
            store().setSelection(AiTaskType.IMAGE, providerB.id, "model-b1")
            assertThat(store().getProviderForTask(AiTaskType.IMAGE)?.id, equalTo("provider-b"))
            assertThat(store().getProviderForTask(AiTaskType.FLASHCARD), nullValue())
        }
}
