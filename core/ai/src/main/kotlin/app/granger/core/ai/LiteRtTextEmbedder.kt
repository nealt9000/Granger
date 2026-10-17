package app.granger.core.ai

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.InputData

/** EmbeddingGemma 2 (.litertlm) on LiteRT-LM's EmbeddingEngine: synchronous load, real close. */
class LiteRtTextEmbedder(
    private val modelPath: String,
    private val cacheDir: String,
    private val backend: LlmBackend = LlmBackend.CPU,
) : TextEmbedder {
    private var engine: EmbeddingEngine? = null

    override fun load() {
        val e = EmbeddingEngine(EmbeddingEngineConfig(modelPath = modelPath, backend = when (backend) {
            LlmBackend.CPU -> Backend.CPU()
            LlmBackend.GPU -> Backend.GPU()
        }, cacheDir = cacheDir))
        try {
            e.initialize()
        } catch (t: Throwable) {
            e.close()
            throw t
        }
        engine = e
    }

    override fun embed(texts: List<String>, isQuery: Boolean): List<FloatArray> {
        val e = checkNotNull(engine) { "load() first" }
        // EmbeddingGemma's retrieval prompt format.
        val inputs = texts.map { listOf(InputData.Text(if (isQuery) "task: search result | query: $it" else "title: none | text: $it")) }
        return e.computeEmbeddingBatch(inputs).map { it.embedding }
    }

    override fun close() {
        engine?.close()
        engine = null
    }
}
