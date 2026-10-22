package app.granger.eval

import app.granger.core.ai.LlmBackend
import app.granger.core.ai.LlmEngine
import app.granger.core.ai.MemorySampler
import app.granger.core.research.CitationCheck
import app.granger.core.research.Citations
import java.io.File
import kotlinx.coroutines.flow.fold
import org.json.JSONArray
import org.json.JSONObject

/**
 * Plan R0, Task 5: the same answer prompts through another model. Reads `{id, prompt, passages}` lines, loads one
 * model, replies to each greedily and writes `{id, reply, outcome, cited, firstWordsMs, totalMs, peakRssMb}` lines.
 * The first line written also carries `loadMs`. A run picks up where a killed one stopped: ids already written are skipped.
 */
class PromptReplay(
    private val engine: (String, LlmBackend) -> LlmEngine,
    private val memory: MemorySampler,
    private val clock: () -> Long,
) {
    var onItem: (String, Int, Int) -> Unit = { _, _, _ -> }

    suspend fun run(prompts: File, modelPath: String, backend: LlmBackend, out: File) {
        val lines = prompts.readLines().filter { it.isNotBlank() }.map(::JSONObject)
        val done = if (out.exists()) out.readLines().filter { it.isNotBlank() }.map { JSONObject(it).getString("id") }.toSet() else emptySet()
        val todo = lines.filter { it.getString("id") !in done }
        if (todo.isEmpty()) return
        val started = clock()
        val model = engine(modelPath, backend).also { it.load() }
        var loadMs: Long? = clock() - started
        try {
            todo.forEachIndexed { n, line ->
                val id = line.getString("id")
                onItem(id, done.size + n + 1, lines.size)
                val record = reply(model, line.getString("prompt"), line.getInt("passages")).put("id", id)
                loadMs?.let { record.put("loadMs", it); loadMs = null }
                out.appendText(record.toString() + "\n")
            }
        } finally {
            model.close()
        }
    }

    private suspend fun reply(model: LlmEngine, prompt: String, passages: Int): JSONObject {
        val start = clock()
        var first: Long? = null
        // First words of the answer itself: a reasoning model's thinking doesn't count.
        val (text, peak) = memory.peakRssMb {
            model.generate(prompt, ANSWER_TOKENS).fold("") { acc, chunk ->
                (acc + chunk).also { if (first == null && withoutThinking(it).isNotBlank()) first = clock() }
            }
        }
        val total = clock() - start
        val answer = withoutThinking(text)
        val check = Citations.check(answer, passages)
        return JSONObject()
            .put("reply", answer)
            .put("raw", if (answer != text) text else JSONObject.NULL)
            .put("outcome", when (check) {
                is CitationCheck.Valid -> "answered"
                CitationCheck.ModelDeclined -> "declined"
                CitationCheck.Invalid -> "unverified"
            })
            .put("cited", JSONArray((check as? CitationCheck.Valid)?.cited?.sorted().orEmpty()))
            .put("firstWordsMs", first?.minus(start) ?: total)
            .put("totalMs", total)
            .put("peakRssMb", peak)
    }

    companion object {
        /** As the pipeline asks for. */
        const val ANSWER_TOKENS = 512
        private val THINKING = Regex("^.*?</think>\\s*", RegexOption.DOT_MATCHES_ALL)

        /**
         * A reasoning model's thinking isn't part of the answer: everything up to `</think>`, whose `<think>` may be in
         * the prompt rather than the reply. Thinking that never closes (out of tokens) leaves nothing.
         */
        fun withoutThinking(text: String): String = when {
            "</think>" in text -> text.replaceFirst(THINKING, "")
            text.trimStart().startsWith("<think>") -> ""
            else -> text
        }
    }
}
