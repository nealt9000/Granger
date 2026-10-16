package app.granger.core.ai.spike

import app.granger.core.ai.GenerationResult
import app.granger.core.ai.LlmBackend
import app.granger.core.ai.LlmEngine
import app.granger.core.ai.LlmEngineFactory
import app.granger.core.ai.MemorySampler
import app.granger.core.ai.MonoClock
import app.granger.core.ai.TextEmbedder
import app.granger.core.ai.TextEmbedderFactory
import app.granger.core.ai.timeGeneration
import kotlin.coroutines.cancellation.CancellationException

data class SpikeConfig(
    val backend: LlmBackend,
    val models: SpikeModels,
    val maxChunks: Int = 128,
)

data class SpikeMeasurement(
    val backend: LlmBackend,
    val step: String,
    val millis: Long? = null,
    val peakRssMb: Int? = null,
    val ttftMs: Long? = null,
    val chunksPerSecond: Double? = null,
    val error: String? = null,
)

/**
 * Measures the spec's riskiest assumptions on a real phone: load and swap cost, time to first token,
 * decode rate, and peak memory with the embedder resident next to the small model.
 * Holds at most one LLM at a time; a failed step is recorded and the run continues.
 */
class SpikeRunner(
    private val llmFactory: LlmEngineFactory,
    private val embedderFactory: TextEmbedderFactory,
    private val memory: MemorySampler,
    private val clock: MonoClock,
    private val fileExists: (String) -> Boolean,
) {
    suspend fun run(
        config: SpikeConfig,
        onProgress: (String) -> Unit = {},
        onRow: (SpikeMeasurement) -> Unit = {},
    ): List<SpikeMeasurement> {
        val m = config.models
        val b = config.backend
        val missing = listOf(m.small, m.large, m.embedder).filterNot(fileExists)
        if (missing.isNotEmpty()) {
            return missing.map { SpikeMeasurement(b, "check files", error = "missing: $it").also(onRow) }
        }

        val out = mutableListOf<SpikeMeasurement>()
        var llm: LlmEngine? = null
        var embedder: TextEmbedder? = null

        suspend fun step(name: String, block: suspend () -> GenerationResult?) =
            measure(b, name, out, onProgress, onRow, block)

        suspend fun generate(prompt: String) =
            timeGeneration(checkNotNull(llm) { "model not loaded" }, prompt, config.maxChunks, clock)

        try {
            step("load small") { llm = openLlm(m.small, b); null }
            step("rewrite (small)") { generate(SpikePrompts.REWRITE) }
            step("answer (small)") { generate(SpikePrompts.ANSWER) }
            step("load embedder (small resident)") { embedder = openEmbedder(m.embedder); null }
            step("embed query + 8 passages") {
                val e = checkNotNull(embedder) { "embedder not loaded" }
                e.embed(listOf(SpikePrompts.QUESTION), isQuery = true)
                e.embed(SpikePrompts.passages(8), isQuery = false)
                null
            }
            step("answer (small + embedder resident)") { generate(SpikePrompts.ANSWER) }
            embedder?.close()
            embedder = null
            step("swap small->large") { llm?.close(); llm = null; llm = openLlm(m.large, b); null }
            step("answer (large)") { generate(SpikePrompts.ANSWER_DEEP) }
            step("swap large->small") { llm?.close(); llm = null; llm = openLlm(m.small, b); null }
        } finally {
            embedder?.close()
            llm?.close()
        }
        return out
    }

    private fun openLlm(path: String, backend: LlmBackend): LlmEngine {
        val engine = llmFactory.create(path, backend)
        try {
            engine.load()
        } catch (t: Throwable) {
            engine.close()
            throw t
        }
        return engine
    }

    private fun openEmbedder(model: String): TextEmbedder {
        val embedder = embedderFactory.create(model)
        try {
            embedder.load()
        } catch (t: Throwable) {
            embedder.close()
            throw t
        }
        return embedder
    }

    private suspend fun measure(
        backend: LlmBackend,
        name: String,
        out: MutableList<SpikeMeasurement>,
        onProgress: (String) -> Unit,
        onRow: (SpikeMeasurement) -> Unit,
        block: suspend () -> GenerationResult?,
    ) {
        onProgress(name)
        val row = try {
            // Time inside the sampled block so the sampler's own cost is not counted.
            val (timed, peak) = memory.peakRssMb {
                val start = clock.nanos()
                val gen = block()
                gen to (clock.nanos() - start) / 1_000_000
            }
            val (gen, millis) = timed
            SpikeMeasurement(
                backend = backend,
                step = name,
                millis = millis,
                peakRssMb = peak,
                ttftMs = gen?.ttftMs,
                chunksPerSecond = gen?.chunksPerSecond,
                error = if (gen != null && gen.chunks == 0) "no output" else null,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Errors too (UnsatisfiedLinkError, OutOfMemoryError): record them and keep going.
            SpikeMeasurement(backend, name, error = t.message ?: t::class.simpleName)
        }
        out += row
        onRow(row)
    }

    companion object {
        val STEPS = listOf(
            "load small",
            "rewrite (small)",
            "answer (small)",
            "load embedder (small resident)",
            "embed query + 8 passages",
            "answer (small + embedder resident)",
            "swap small->large",
            "answer (large)",
            "swap large->small",
        )
    }
}
