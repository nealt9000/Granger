package app.granger.core.ai

import com.google.ai.edge.localagents.rag.models.EmbedData
import com.google.ai.edge.localagents.rag.models.EmbeddingRequest
import com.google.ai.edge.localagents.rag.models.GemmaEmbeddingModel

class RagTextEmbedder(
    private val modelPath: String,
    private val tokenizerPath: String,
    private val useGpu: Boolean = false,
) : TextEmbedder {
    private var model: GemmaEmbeddingModel? = null

    override fun load() {
        model = GemmaEmbeddingModel(modelPath, tokenizerPath, useGpu)
    }

    override fun embed(texts: List<String>, isQuery: Boolean): List<FloatArray> {
        val m = checkNotNull(model) { "load() first" }
        val task = if (isQuery) EmbedData.TaskType.RETRIEVAL_QUERY else EmbedData.TaskType.RETRIEVAL_DOCUMENT
        return texts.map { text ->
            val request = EmbeddingRequest.create(listOf(EmbedData.create(text, task)))
            m.getEmbeddings(request).get().toFloatArray()
        }
    }

    override fun close() {
        model = null
    }
}
