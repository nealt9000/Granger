package app.granger.core.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class FakeClock : MonoClock {
    var now = 0L
    override fun nanos() = now
    fun advanceMs(ms: Long) { now += ms * 1_000_000 }
}

/** Emits one chunk per entry in [delaysMs], advancing [clock] by that delay before each chunk. */
open class ScriptedLlm(
    private val clock: FakeClock,
    private val delaysMs: List<Long>,
) : LlmEngine {
    var loaded = false
    var closed = false
    override fun load() { loaded = true }
    override fun generate(prompt: String): Flow<String> = flow {
        delaysMs.forEachIndexed { i, d ->
            clock.advanceMs(d)
            emit("t$i ")
        }
    }
    override fun close() { closed = true }
}

class FakeLlmFactory(
    private val clock: FakeClock,
    private val failLoadFor: Set<String> = emptySet(),
    private val delaysMs: List<Long> = listOf(400, 50, 50, 50),
) : LlmEngineFactory {
    val openEngines = mutableSetOf<LlmEngine>()
    var maxOpen = 0
    val events = mutableListOf<String>()

    override fun create(modelPath: String, backend: LlmBackend): LlmEngine {
        events += "create $modelPath"
        val engine = object : ScriptedLlm(clock, delaysMs) {
            override fun load() {
                events += "load $modelPath"
                if (modelPath in failLoadFor) error("load failed: $modelPath")
                super.load()
            }
            override fun close() {
                events += "close $modelPath"
                openEngines -= this
                super.close()
            }
        }
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

class FakeMemory(private val mb: Int = 1234) : MemorySampler {
    override suspend fun <T> peakPssMb(block: suspend () -> T): Pair<T, Int> = block() to mb
}
