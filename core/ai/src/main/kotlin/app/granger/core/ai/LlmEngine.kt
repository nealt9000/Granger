package app.granger.core.ai

import kotlinx.coroutines.flow.Flow

enum class LlmBackend { CPU, GPU }

/** One on-device language model. Callers must close it before loading another (one LLM resident at a time). */
interface LlmEngine : AutoCloseable {
    /** Blocking and slow (up to ~10 s). Never call on the main thread. */
    fun load()

    /** Streams the reply as text chunks (roughly one token each). */
    fun generate(prompt: String): Flow<String>
}

fun interface LlmEngineFactory {
    fun create(modelPath: String, backend: LlmBackend): LlmEngine
}
