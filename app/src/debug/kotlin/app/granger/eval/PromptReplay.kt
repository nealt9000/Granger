package app.granger.eval

import app.granger.core.ai.LlmBackend
import app.granger.core.ai.LlmEngine
import app.granger.core.ai.LlmOptions
import app.granger.core.ai.MemorySampler
import app.granger.core.ai.ThinkingOff
import app.granger.core.research.CitationCheck
import app.granger.core.research.Citations
import java.io.File
import kotlinx.coroutines.flow.fold
import org.json.JSONArray
import org.json.JSONObject

/**
 * Plan R0, Task 5: the same answer prompts through another model. Reads `{id, prompt, passages}` lines, loads one
 * model, replies to each greedily and writes `{id, reply, outcome, cited, firstWordsMs, totalMs, peakRssMb}` lines,
 * plus the engine's token counts and rates when it measures them (Plan R4: `prefillTokens`, `prefillTokensPerSecond`,
 * `outputTokens`, `decodeTokensPerSecond`). The first line written also carries `loadMs` and [meminfo] before and after
 * the load (`memBeforeLoad`, `memAfterLoad`: RSS misses GPU memory). A run picks up where a killed one stopped: ids
 * already written are skipped.
 */
class PromptReplay(
    private val engine: (String, LlmBackend) -> LlmEngine,
    private val memory: MemorySampler,
    private val maxTokens: Int = ANSWER_TOKENS,
    private val meminfo: () -> Map<String, Int> = { emptyMap() },
    private val clock: () -> Long,
) {
    var onItem: (String, Int, Int) -> Unit = { _, _, _ -> }

    suspend fun run(prompts: File, modelPath: String, backend: LlmBackend, out: File) {
        val lines = prompts.readLines().filter { it.isNotBlank() }.map(::JSONObject)
        val done = if (out.exists()) out.readLines().filter { it.isNotBlank() }.map { JSONObject(it).getString("id") }.toSet() else emptySet()
        val todo = lines.filter { it.getString("id") !in done }
        if (todo.isEmpty()) return
        val before = meminfo()
        val started = clock()
        val model = engine(modelPath, backend).also { it.load() }
        var loadMs: Long? = clock() - started
        val after = meminfo()
        try {
            todo.forEachIndexed { n, line ->
                val id = line.getString("id")
                onItem(id, done.size + n + 1, lines.size)
                val record = reply(model, line.getString("prompt"), line.getInt("passages")).put("id", id)
                loadMs?.let {
                    record.put("loadMs", it).put("memBeforeLoad", JSONObject(before)).put("memAfterLoad", JSONObject(after))
                    loadMs = null
                }
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
            model.generate(prompt, maxTokens).fold("") { acc, chunk ->
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
            .apply {
                model.lastStats?.let {
                    put("prefillTokens", it.prefillTokens).put("prefillTokensPerSecond", it.prefillTokensPerSecond)
                    put("outputTokens", it.decodeTokens).put("decodeTokensPerSecond", it.decodeTokensPerSecond)
                }
            }
    }

    companion object {
        /** As the pipeline asks for. */
        const val ANSWER_TOKENS = 512

        /**
         * The replay's model options from its extras: `speculative` on|off, `thinking` off (both switches), config (the
         * runtime's) or template (the chat template's). Always benchmarked, for the token rates. Anything else is an error.
         */
        fun options(speculative: String?, thinking: String?) = LlmOptions(
            speculative = when (speculative) {
                null -> null
                "on" -> true
                "off" -> false
                else -> throw IllegalArgumentException("speculative is on or off, not $speculative")
            },
            thinkingOff = when (thinking) {
                null -> null
                "off" -> ThinkingOff.BOTH
                "config" -> ThinkingOff.CONFIG
                "template" -> ThinkingOff.TEMPLATE
                else -> throw IllegalArgumentException("thinking is off, config or template, not $thinking")
            },
            benchmark = true,
        )
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
