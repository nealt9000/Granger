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
