package app.granger.core.ai

import kotlinx.coroutines.flow.take

fun interface MonoClock {
    fun nanos(): Long
}

val SystemMonoClock = MonoClock { System.nanoTime() }

data class GenerationResult(
    /** Time from request to first chunk; null when the model produced nothing. */
    val ttftMs: Long?,
    val chunks: Int,
    /** Time from first to last chunk. */
    val decodeMs: Long,
    val text: String,
) {
    /** Chunks per second after the first chunk. LiteRT-LM chunks approximate tokens. */
    val chunksPerSecond: Double
        get() = if (chunks < 2 || decodeMs <= 0) 0.0 else (chunks - 1) * 1000.0 / decodeMs
}

suspend fun timeGeneration(
    engine: LlmEngine,
    prompt: String,
    maxChunks: Int,
    clock: MonoClock,
): GenerationResult {
    val start = clock.nanos()
    var first = -1L
    var last = -1L
    var count = 0
    val text = StringBuilder()
    engine.generate(prompt).take(maxChunks).collect { chunk ->
        val now = clock.nanos()
        if (first < 0) first = now
        last = now
        count++
        text.append(chunk)
    }
    if (count == 0) return GenerationResult(ttftMs = null, chunks = 0, decodeMs = 0, text = "")
    return GenerationResult(
        ttftMs = (first - start) / 1_000_000,
        chunks = count,
        decodeMs = (last - first) / 1_000_000,
        text = text.toString(),
    )
}
