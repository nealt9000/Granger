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

    private fun TestScope.adapter(installed: InstalledModels): Pair<ModelsAdapter, ModelManager> {
        val io = StandardTestDispatcher(testScheduler)
        val manager = ModelManager(
            DeviceTier.THINK_DEEPER, MutableStateFlow(installed),
            LlmEngineFactory { path, _: LlmBackend -> GatedLlm(path).also { engines += it } },
            TextEmbedderFactory { CountingEmbedder().also { embedders += it } },
            backgroundScope, useEmbedder = true, io = io,
        )
        return ModelsAdapter(manager, io) to manager
    }

    @Test fun releaseRequestedMidGenerationWaitsForTheReply() = runTest {
        val (adapter, manager) = adapter(InstalledModels(small = File("small")))
        val generator = adapter.generator(deeper = false)
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

    @Test fun deeperUsesTheLargeModel() = runTest {
        val (adapter, _) = adapter(InstalledModels(small = File("small"), large = File("large")))
        adapter.generator(deeper = true)
        assertThat(engines.single().path).isEqualTo("large")
    }

    @Test fun aModelThatWontLoadIsUnavailableAndNotHeld() = runTest {
        val (adapter, manager) = adapter(InstalledModels(small = File("small"), large = File("broken")))
        val error = runCatching { adapter.generator(deeper = true) }.exceptionOrNull()
        assertThat(error).isInstanceOf(ModelUnavailableException::class.java)
        val small = adapter.generator(deeper = false)
        launch { small.generate("q", 16).toList() }
        advanceUntilIdle()
        engines.last().gate.complete(Unit)
        advanceUntilIdle()
        // A hold leaked by the failed load would defer this release for ever.
        manager.releaseNow()
        runCurrent()
        assertThat(engines.last().closed).isTrue()
    }

    @Test fun embedderEmbedsAndLetsGo() = runTest {
        val (adapter, manager) = adapter(InstalledModels(small = File("small"), embedder = File("emb")))
        val embedder = checkNotNull(adapter.embedder())
        assertThat(embedder.embed(listOf("a", "b"), isQuery = false)).hasSize(2)
        manager.releaseNow()
        runCurrent()
        assertThat(embedders.single().closed).isTrue()
    }

    @Test fun noEmbedderInstalledMeansNone() = runTest {
        val (adapter, _) = adapter(InstalledModels(small = File("small")))
        assertThat(adapter.embedder()).isNull()
    }
}
