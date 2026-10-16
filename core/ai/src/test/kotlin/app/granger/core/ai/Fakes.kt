package app.granger.core.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class FakeClock : MonoClock {
    var now = 0L
    override fun nanos() = now
    fun advanceMs(ms: Long) { now += ms * 1_000_000 }
}

/**
 * Emits one chunk per entry in [delaysMs], advancing [clock] by that delay before each chunk.
 * Chunk text comes from [texts] when given, else "t<i> ".
 */
open class ScriptedLlm(
    private val clock: FakeClock,
    private val delaysMs: List<Long>,
    private val texts: List<String>? = null,
) : LlmEngine {
    var loaded = false
    var closed = false
    var lastMaxTokens: Int? = null
    val prompts = mutableListOf<String>()
    override fun load() { loaded = true }
    override fun generate(prompt: String, maxTokens: Int): Flow<String> = flow {
        prompts += prompt
        lastMaxTokens = maxTokens
        delaysMs.forEachIndexed { i, d ->
            clock.advanceMs(d)
            emit(texts?.get(i) ?: "t$i ")
        }
    }
    override fun close() { closed = true }
}

class FakeLlmFactory(
    private val clock: FakeClock,
    private val failLoadFor: Map<String, Throwable> = emptyMap(),
    private val delaysMs: List<Long> = listOf(400, 50, 50, 50),
) : LlmEngineFactory {
    val openEngines = mutableSetOf<LlmEngine>()
    var maxOpen = 0
    val events = mutableListOf<String>()
    val byPath = mutableMapOf<String, MutableList<ScriptedLlm>>()

    override fun create(modelPath: String, backend: LlmBackend): LlmEngine {
        events += "create $modelPath"
        val engine = object : ScriptedLlm(clock, delaysMs) {
            override fun load() {
                events += "load $modelPath"
                failLoadFor[modelPath]?.let { throw it }
                super.load()
            }
            override fun close() {
                events += "close $modelPath"
                openEngines -= this
                super.close()
            }
        }
        byPath.getOrPut(modelPath) { mutableListOf() } += engine
        openEngines += engine
        maxOpen = maxOf(maxOpen, openEngines.size)
        return engine
    }
}

class FakeEmbedder : TextEmbedder {
    var closed = false
    override fun load() {}
    override fun embed(texts: List<String>, isQuery: Boolean) = texts.map { FloatArray(4) }
    override fun close() { closed = true }
}

/** Reports [mb]; when [clock] is set, simulates sampling cost by advancing it [overheadMs] before and after. */
class FakeMemory(
    private val mb: Int = 1234,
    private val clock: FakeClock? = null,
    private val overheadMs: Long = 0,
) : MemorySampler {
    override suspend fun <T> peakRssMb(block: suspend () -> T): Pair<T, Int> {
        clock?.advanceMs(overheadMs)
        val result = block()
        clock?.advanceMs(overheadMs)
        return result to mb
    }
}
