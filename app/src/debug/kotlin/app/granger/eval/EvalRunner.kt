package app.granger.eval

import app.granger.core.library.DensePassageFinder
import app.granger.core.library.DenseTimings
import app.granger.core.library.KeywordTimings
import app.granger.core.library.LibraryPassageFinder
import app.granger.core.research.Keywords
import app.granger.core.research.LexicalScore
import app.granger.core.research.Outcome
import app.granger.core.research.DenseFinder
import app.granger.core.research.Passage
import app.granger.core.research.PassageFinder
import app.granger.core.research.PriorTurn
import app.granger.core.research.PassageEmbedder
import app.granger.core.research.ResearchEvent
import app.granger.core.research.ResearchModels
import app.granger.core.research.ResearchPipeline
import app.granger.core.research.ResearchRequest
import app.granger.core.research.SourceKind
import app.granger.core.research.Stage
import app.granger.core.research.TextGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/**
 * The `source` a record gives a passage, as `tools/eval` datasets name it: an article's pack by its ZIM name (its id when
 * the pack has none); anything else by its sourceId (a book's `book:granger:<ref>`, a media card's `album:<albumKey>` or
 * audiobook id).
 */
fun evalSourceName(packNames: Map<String, String>): (Passage) -> String =
    { p -> if (p.kind == SourceKind.ARTICLE) packNames[p.sourceId]?.ifBlank { null } ?: p.sourceId else p.sourceId }

/**
 * Wraps the real finder and keeps the last call's keywords and passages, for the record's `found` list, and how long it
 * took (`ms.keywordSearch`, Plan R5 Task 1: `ms.search` also covers the dense side running alongside).
 */
class RecordingFinder(private val inner: PassageFinder, private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) : PassageFinder {
    var keywords: List<String> = emptyList()
        private set
    var passages: List<Passage> = emptyList()
        private set
    var ms: Long? = null
        private set
    /** Plan R5 Task 1: the split of [ms], when the finder is the app's [LibraryPassageFinder]. */
    var timings: KeywordTimings? = null
        private set

    fun reset() {
        keywords = emptyList()
        passages = emptyList()
        ms = null
        timings = null
    }

    override suspend fun find(keywords: List<String>): List<Passage> {
        this.keywords = keywords
        val start = clock()
        return inner.find(keywords).also {
            passages = it
            ms = clock() - start
            timings = (inner as? LibraryPassageFinder)?.lastTimings
        }
    }

    /** The passages with their lexical score (as Ranking.preselect scores them), best first. */
    fun scored(): List<Pair<Passage, Double>> {
        val score = LexicalScore.scorer(keywords)
        return passages.map { it to score(it) }.sortedByDescending { it.second }
    }
}

/** Records the dense hits of the current item and how long the scan and rebuild took, for found.jsonl and `ms.dense`. */
class RecordingDenseFinder(private val inner: DenseFinder, private val clock: () -> Long) : DenseFinder {
    var passages: List<Passage> = emptyList()
        private set
    var ms: Long? = null
        private set
    /** Plan R5 Task 1: the split of [ms], when the finder is the app's [DensePassageFinder]. */
    var timings: DenseTimings? = null
        private set

    fun reset() {
        passages = emptyList()
        ms = null
        timings = null
    }

    override suspend fun find(query: FloatArray): List<Passage> {
        val start = clock()
        return inner.find(query).also {
            passages = it
            ms = clock() - start
            timings = (inner as? DensePassageFinder)?.lastTimings
        }
    }
}

/**
 * Wraps the models the pipeline uses and keeps, for the current item, every prompt sent and the reply it streamed back.
 * The stage comes from where the runner is ([begin] before each pipeline run), not from the prompt text: within a
 * main run the calls are the rewrite and the answer.
 */
class PromptRecorder(private val inner: ResearchModels) : ResearchModels {
    private class Entry(val stage: String, val prompt: String) { val output = StringBuilder() }

    private val entries = mutableListOf<Entry>()
    private var phase = "main"
    private var calls = 0

    /** Clears the recording (call per item). */
    fun reset() {
        synchronized(entries) { entries.clear(); phase = "main"; calls = 0 }
    }

    /** Starts a pipeline run of [phase]: "prior", "main" or "guess-small". */
    fun begin(phase: String) {
        synchronized(entries) { this.phase = phase; calls = 0 }
    }

    /** What was recorded so far, in call order. */
    fun toJson(): JSONArray = synchronized(entries) {
        JSONArray(entries.map { JSONObject().put("stage", it.stage).put("prompt", it.prompt).put("output", it.output.toString()) })
    }

    private fun stage(): String = synchronized(entries) {
        val n = ++calls
        when (phase) {
            "guess-small" -> phase
            else -> {
                val base = if (n == 1) "rewrite" else "answer"
                if (phase == "prior") "prior-$base" else base
            }
        }
    }

    override suspend fun generator(): TextGenerator {
        val real = inner.generator()
        return object : TextGenerator {
            override fun generate(prompt: String, maxTokens: Int): Flow<String> = record(prompt, real.generate(prompt, maxTokens))

            override fun generate(prompt: String, maxTokens: Int, onReady: () -> Unit): Flow<String> =
                record(prompt, real.generate(prompt, maxTokens, onReady))
        }
    }

    private fun record(prompt: String, source: Flow<String>): Flow<String> = flow {
        val entry = Entry(stage(), prompt)
        synchronized(entries) { entries += entry }
        source.collect { chunk ->
            synchronized(entries) { entry.output.append(chunk) }
            emit(chunk)
        }
    }

    override suspend fun embedder(): PassageEmbedder? = inner.embedder()
}

/**
 * Runs eval items through the real Research pipeline and appends one JSON record per item to a file.
 * [sourceName] maps a passage to the name the dataset uses (the ZIM name for articles, the book id for books).
 */
class EvalRunner(
    private val pipeline: ResearchPipeline,
    private val finder: RecordingFinder,
    private val sourceName: (Passage) -> String,
    private val prompts: PromptRecorder? = null,
    /** The dense finder the pipeline was given, when dense retrieval is on for the run. */
    private val dense: RecordingDenseFinder? = null,
    private val clock: () -> Long,
) {
    /** Called before each item with the item and its place (1-based) among the items still to run. */
    var onItem: (EvalItem, Int, Int) -> Unit = { _, _, _ -> }

    /**
     * Appends a record to [out] for every item not already in it, flushing after each. A half-written last line
     * (a run cut off mid-write) is dropped and its item runs again.
     */
    suspend fun run(items: List<EvalItem>, out: File, guesses: GuessModels, runId: String = out.parentFile?.name.orEmpty()) {
        out.parentFile?.mkdirs()
        val done = keepCompleteLines(out)
        val todo = items.filter { it.id !in done }
        todo.forEachIndexed { i, item ->
            onItem(item, i + 1, todo.size)
            val record = try {
                record(runId, item, guesses)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                JSONObject().put("runId", runId).put("id", item.id).put("keywords", JSONArray()).put("rewriteFallback", false)
                    .put("medical", false).put("found", JSONArray()).put("chosen", JSONArray()).put("outcome", "failed")
                    .put("reply", "${e::class.simpleName}: ${e.message}").put("cited", JSONArray())
                    .put("ms", JSONObject().put("total", 0)).also { o -> prompts?.let { o.put("prompts", it.toJson()) } }
            }
            out.appendText(record.toString() + "\n")
            // Everything the search found, with text, so ranking can be tried offline against the real corpus.
            File(out.parentFile, CORPUS_FILE).appendText(corpus(item).toString() + "\n")
        }
    }

    private fun corpus(item: EvalItem): JSONObject = JSONObject().put("id", item.id).put("keywords", JSONArray(finder.keywords))
        .put("passages", JSONArray(finder.passages.map(::corpusPassage)))
        .also { o -> dense?.let { d -> o.put("dense", JSONArray(d.passages.map(::corpusPassage))) } }

    private fun corpusPassage(p: Passage): JSONObject = JSONObject().put("kind", p.kind.name).put("source", sourceName(p))
        .put("title", p.sourceTitle).put("location", p.location).put("heading", p.heading).put("searchScore", p.searchScore)
        .put("text", p.text).also { o -> p.cosine?.let { o.put("cosine", it) } }

    /**
     * Keeps the complete, non-failed lines (a truncated tail is dropped, and a failed item is retried) and returns
     * the ids recorded. The file is replaced through a temp file, so a crash here can't lose records.
     */
    private fun keepCompleteLines(out: File): Set<String> {
        if (!out.exists()) return emptySet()
        val ids = LinkedHashSet<String>()
        val good = mutableListOf<String>()
        var dropped = false
        for (line in out.readLines()) {
            if (line.isBlank()) continue
            val o = try { JSONObject(line) } catch (e: org.json.JSONException) { dropped = true; break }
            if (o.optString("outcome") == "failed") { dropped = true; continue }
            ids += o.optString("id")
            good += line
        }
        if (dropped || !out.readText().endsWith("\n")) {
            val tmp = File(out.parentFile, out.name + ".tmp")
            tmp.writeText(good.joinToString("") { it + "\n" })
            java.nio.file.Files.move(tmp.toPath(), out.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        }
        return ids
    }

    private class Run(val outcome: Outcome?, val ms: JSONObject)

    /** Runs the pipeline to its Done event, timing the stages from the Progress events. */
    private suspend fun execute(flow: kotlinx.coroutines.flow.Flow<ResearchEvent>): Run {
        val start = clock()
        val ms = JSONObject()
        var stageStart = start
        var outcome: Outcome? = null
        var first = false
        flow.collect { event ->
            val now = clock()
            when (event) {
                is ResearchEvent.Progress -> {
                    val key = when (event.stage) {
                        Stage.SEARCHING -> "rewrite" to start
                        Stage.RANKING -> "search" to stageStart
                        Stage.WRITING -> "rank" to stageStart
                        Stage.LOADING_MODEL -> null
                    }
                    // A guess skips retrieval: it has no SEARCHING or RANKING, so WRITING must not claim a rank time.
                    if (key != null && !(event.stage == Stage.WRITING && !ms.has("search"))) ms.put(key.first, now - key.second)
                    stageStart = now
                }
                is ResearchEvent.Partial -> if (!first) { first = true; ms.put("firstWords", now - start) }
                is ResearchEvent.Done -> outcome = event.outcome
                else -> Unit
            }
        }
        ms.put("total", clock() - start)
        return Run(outcome, ms)
    }

    private suspend fun record(runId: String, item: EvalItem, guesses: GuessModels): JSONObject {
        prompts?.reset()
        var prior: PriorTurn? = null
        if (item.priorQuestion != null) {
            prompts?.begin("prior")
            val first = execute(pipeline.run(ResearchRequest(item.priorQuestion))).outcome
            // As the app does: only an answered turn is passed on as the prior turn.
            if (first is Outcome.Answered) prior = PriorTurn(item.priorQuestion, first.text)
        }
        finder.reset()
        dense?.reset()
        val request = ResearchRequest(item.question, prior = prior, priorQuestion = item.priorQuestion)
        prompts?.begin("main")
        val main = execute(pipeline.run(request))
        dense?.ms?.let { main.ms.put("dense", it) }
        finder.ms?.let { main.ms.put("keywordSearch", it) }
        dense?.timings?.let { t ->
            main.ms.put("denseSources", t.sourcesMs).put("denseScan", t.scanMs).put("denseRebuild", t.rebuildMs)
        }
        finder.timings?.let { t ->
            main.ms.put("kwFulltext", t.fulltextMs).put("kwRead", t.readMs).put("kwBooks", t.booksMs)
                .put("kwCards", t.cardsMs).put("kwMedia", t.mediaMs)
            main.ms.put("kwPacks", JSONObject(t.packMs as Map<*, *>))
        }
        val outcome = main.outcome
        val keywords = outcome?.keywords.orEmpty()
        val fallbackKeywords = Keywords.extract(listOfNotNull(item.question, item.priorQuestion).joinToString(" "))
        val record = JSONObject()
            .put("runId", runId).put("id", item.id)
            .put("keywords", JSONArray(keywords))
            // The pipeline doesn't say; equal keywords means the plain fallback was used (or the model chose the same words).
            .put("rewriteFallback", keywords.isNotEmpty() && keywords == fallbackKeywords)
            .put("medical", outcome?.medical ?: false)
            .put("found", JSONArray(finder.scored().take(FOUND_CAP).map { (p, lexical) ->
                JSONObject().put("kind", p.kind.name).put("source", sourceName(p)).put("title", p.sourceTitle)
                    .put("location", p.location).put("heading", p.heading).put("lexical", lexical)
            }))
        describe(outcome).into(record)
        record.put("ms", main.ms)

        if (outcome is Outcome.Declined && !outcome.medical && guesses == GuessModels.SMALL) {
            // Under "small", as tools/eval names the model's guesses.
            guess(item)?.let { record.put("guesses", JSONObject().put("small", it)) }
        }
        prompts?.let { record.put("prompts", it.toJson()) }
        return record
    }

    private suspend fun guess(item: EvalItem): JSONObject? {
        prompts?.begin("guess-small")
        val run = execute(pipeline.guess(item.question, item.priorQuestion))
        val guessed = run.outcome as? Outcome.Guessed ?: return null
        return JSONObject().put("text", guessed.text).put("ms", run.ms.optLong("total"))
    }

    private class Described(val outcome: String, val reply: String, val cited: List<Int>, val chosen: List<Passage>)

    /** "<reason>: <ExceptionClass>: <message>", so a failed record says what failed. */
    private fun failureText(outcome: Outcome.Failed): String {
        val cause = outcome.cause ?: return outcome.reason.name
        return "${outcome.reason}: ${cause::class.simpleName}: ${cause.message}"
    }

    private fun describe(outcome: Outcome?): Described = when (outcome) {
        is Outcome.Answered -> Described("answered", outcome.text, outcome.cited.sorted(), outcome.passages)
        is Outcome.Unverified -> Described("unverified", outcome.text, emptyList(), outcome.passages)
        is Outcome.Declined -> Described("declined", outcome.reply, emptyList(), outcome.passages)
        is Outcome.Failed -> Described("failed", failureText(outcome), emptyList(), emptyList())
        else -> Described("failed", "", emptyList(), emptyList())
    }

    /** Fills a record with the outcome fields and the chosen passages. */
    private fun Described.into(o: JSONObject) {
        o.put("outcome", outcome).put("reply", reply).put("cited", JSONArray(cited))
        run {
            o.put("chosen", JSONArray(chosen.map { p ->
                JSONObject().put("kind", p.kind.name).put("source", sourceName(p)).put("title", p.sourceTitle)
                    .put("location", p.location).put("heading", p.heading).put("text", p.text)
            }))
        }
    }

    companion object {
        const val FOUND_CAP = 60
        const val CORPUS_FILE = "found.jsonl"
    }
}
