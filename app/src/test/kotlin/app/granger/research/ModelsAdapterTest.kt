package app.granger.research

import app.granger.core.ai.DeviceTier
import app.granger.core.ai.InstalledModels
import app.granger.core.ai.LlmBackend
import app.granger.core.ai.LlmEngine
import app.granger.core.ai.LlmEngineFactory
import app.granger.core.ai.ModelManager
import app.granger.core.ai.TextEmbedder
import app.granger.core.ai.TextEmbedderFactory
import app.granger.core.research.ModelUnavailableException
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Release jobs run on backgroundScope, which advanceUntilIdle doesn't wait for: runCurrent runs them. */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelsAdapterTest {
    private class GatedLlm(val path: String) : LlmEngine {
        val gate = CompletableDeferred<Unit>()
        var closed = false
        override fun load() {
            if (path == "broken") error("bad file")
        }
        override fun generate(prompt: String, maxTokens: Int): Flow<String> = flow {
            emit("one ")
            gate.await()
            emit("two")
        }
        override fun close() { closed = true }
    }

    private class CountingEmbedder : TextEmbedder {
        var closed = false
        override fun load() {}
        override fun embed(texts: List<String>, isQuery: Boolean) = texts.map { FloatArray(2) }
        override fun close() { closed = true }
    }

    private val engines = mutableListOf<GatedLlm>()
    private val embedders = mutableListOf<CountingEmbedder>()

    private fun TestScope.adapter(installed: InstalledModels): Pair<ModelsAdapter, ModelManager> =
        adapter(MutableStateFlow(installed))

    private fun TestScope.adapter(installed: MutableStateFlow<InstalledModels>): Pair<ModelsAdapter, ModelManager> {
        val io = StandardTestDispatcher(testScheduler)
        val manager = ModelManager(
            DeviceTier.ASK, installed,
            LlmEngineFactory { path, _: LlmBackend -> GatedLlm(path).also { engines += it } },
            TextEmbedderFactory { CountingEmbedder().also { embedders += it } },
            backgroundScope, useEmbedder = { true }, io = io,
        )
        return ModelsAdapter(manager, io, Mutex()) to manager
    }

    @Test fun aSecondGenerationWaitsForTheFirst() = runTest {
        val (adapter, _) = adapter(InstalledModels(llm = File("small")))
        val generator = adapter.generator()
        val heard = mutableListOf<String>()
        launch { generator.generate("a", 16).collect { heard += "a:$it" } }
        advanceUntilIdle()
        launch { generator.generate("b", 16).collect { heard += "b:$it" } }
        advanceUntilIdle()
        assertThat(heard).containsExactly("a:one ")
        engines.single().gate.complete(Unit)
        advanceUntilIdle()
        assertThat(heard).containsExactly("a:one ", "a:two", "b:one ", "b:two").inOrder()
    }

    @Test fun aGenerationSaysItIsReadyOnlyOnceTheModelIsFree() = runTest {
        val (adapter, _) = adapter(InstalledModels(llm = File("small")))
        val generator = adapter.generator()
        launch { generator.generate("a", 16).toList() }
        advanceUntilIdle()
        var ready = false
        launch { generator.generate("b", 16) { ready = true }.toList() }
        advanceUntilIdle()
        assertThat(ready).isFalse() // still waiting for the first reply
        engines.single().gate.complete(Unit)
        advanceUntilIdle()
        assertThat(ready).isTrue()
    }

    @Test fun releaseRequestedMidGenerationWaitsForTheReply() = runTest {
        val (adapter, manager) = adapter(InstalledModels(llm = File("small")))
        val generator = adapter.generator()
        var reply: List<String>? = null
        launch { reply = generator.generate("q", 16).toList() }
        advanceUntilIdle()
        manager.releaseNow()
        runCurrent()
        assertThat(engines.single().closed).isFalse()
        engines.single().gate.complete(Unit)
        advanceUntilIdle()
        runCurrent()
        assertThat(reply).containsExactly("one ", "two").inOrder()
        assertThat(engines.single().closed).isTrue()
    }

    @Test fun aModelThatWontLoadIsUnavailableAndNotHeld() = runTest {
        val installed = MutableStateFlow(InstalledModels(llm = File("broken")))
        val (adapter, manager) = adapter(installed)
        val error = runCatching { adapter.generator() }.exceptionOrNull()
        assertThat(error).isInstanceOf(ModelUnavailableException::class.java)
        installed.value = InstalledModels(llm = File("small"))
        val generator = adapter.generator()
        launch { generator.generate("q", 16).toList() }
        advanceUntilIdle()
        engines.last().gate.complete(Unit)
        advanceUntilIdle()
        // A hold leaked by the failed load would defer this release for ever.
        manager.releaseNow()
        runCurrent()
        assertThat(engines.last().closed).isTrue()
    }

    @Test fun embedderEmbedsAndLetsGo() = runTest {
        val (adapter, manager) = adapter(InstalledModels(llm = File("small"), embedder = File("emb")))
        val embedder = checkNotNull(adapter.embedder())
        assertThat(embedder.embed(listOf("a", "b"), isQuery = false)).hasSize(2)
        manager.releaseNow()
        runCurrent()
        assertThat(embedders.single().closed).isTrue()
    }

    @Test fun noEmbedderInstalledMeansNone() = runTest {
        val (adapter, _) = adapter(InstalledModels(llm = File("small")))
        assertThat(adapter.embedder()).isNull()
    }
}
