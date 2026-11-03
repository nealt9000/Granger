package app.granger.core.ai

/** On-device text embedding model used to rerank passages. May stay loaded alongside the small LLM. */
interface TextEmbedder : AutoCloseable {
    fun load()
    fun embed(texts: List<String>, isQuery: Boolean): List<FloatArray>

    /**
     * Documents with their titles, as `tools/vectors/build.py` embeds the catalog's (`title: <title> | text: <text>`), so
     * vectors made on the phone match the downloaded ones (Plan R2 Task 9). A model without a titled form embeds the
     * text alone.
     */
    fun embedDocuments(docs: List<Pair<String, String>>): List<FloatArray> = embed(docs.map { it.second }, isQuery = false)
}

fun interface TextEmbedderFactory {
    fun create(modelPath: String): TextEmbedder
}
