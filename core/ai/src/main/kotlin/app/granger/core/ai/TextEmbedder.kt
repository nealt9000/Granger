package app.granger.core.ai

/** On-device text embedding model used to rerank passages. May stay loaded alongside the small LLM. */
interface TextEmbedder : AutoCloseable {
    fun load()
    fun embed(texts: List<String>, isQuery: Boolean): List<FloatArray>
}

fun interface TextEmbedderFactory {
    fun create(modelPath: String, tokenizerPath: String): TextEmbedder
}
