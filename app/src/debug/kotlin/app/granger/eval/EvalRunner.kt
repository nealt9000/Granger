package app.granger.eval

import app.granger.core.research.Keywords
import app.granger.core.research.LexicalScore
import app.granger.core.research.Outcome
import app.granger.core.research.Passage
import app.granger.core.research.PassageFinder
import app.granger.core.research.PriorTurn
import app.granger.core.research.ResearchEvent
import app.granger.core.research.ResearchPipeline
import app.granger.core.research.ResearchRequest
import app.granger.core.research.Stage
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/** Wraps the real finder and keeps the last call's keywords and passages, for the record's `found` list. */
class RecordingFinder(private val inner: PassageFinder) : PassageFinder {
    var keywords: List<String> = emptyList()
        private set
    var passages: List<Passage> = emptyList()
        private set

    fun reset() {
        keywords = emptyList()
        passages = emptyList()
    }

    override suspend fun find(keywords: List<String>): List<Passage> {
        this.keywords = keywords
        return inner.find(keywords).also { passages = it }
    }

    /** The passages with their lexical score (weighted against all of them, as Ranking.preselect does), best first. */
    fun scored(): List<Pair<Passage, Double>> {
        val score = LexicalScore.scorer(keywords, corpus = passages)
        return passages.map { it to score(it) }.sortedByDescending { it.second }
    }
}

/**
 * Runs eval items through the real Research pipeline and appends one JSON record per item to a file.
 * [sourceName] maps a passage to the name the dataset uses (the ZIM name for articles, the book id for books).
 */
class EvalRunner(
    private val pipeline: ResearchPipeline,
    private val finder: RecordingFinder,
    private val sourceName: (Passage) -> String,
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
                    .put("ms", JSONObject().put("total", 0))
            }
            out.appendText(record.toString() + "\n")
        }
    }

    /** Drops anything after the last parseable line and returns the ids recorded. */
    private fun keepCompleteLines(out: File): Set<String> {
        if (!out.exists()) return emptySet()
        val ids = LinkedHashSet<String>()
        val good = mutableListOf<String>()
        for (line in out.readLines()) {
            if (line.isBlank()) continue
            val o = try { JSONObject(line) } catch (e: org.json.JSONException) { break }
            ids += o.optString("id")
            good += line
        }
        out.writeText(good.joinToString("") { it + "\n" })
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
                    // A deeper run skips retrieval: it has no SEARCHING or RANKING, so WRITING must not claim a rank time.
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
        var prior: PriorTurn? = null
        if (item.priorQuestion != null) {
            val first = execute(pipeline.run(ResearchRequest(item.priorQuestion))).outcome
            // As the app does: only an answered turn is passed on as the prior turn.
            if (first is Outcome.Answered) prior = PriorTurn(item.priorQuestion, first.text)
        }
        finder.reset()
        val request = ResearchRequest(item.question, prior = prior, priorQuestion = item.priorQuestion, medicalHint = item.medical)
        val main = execute(pipeline.run(request))
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

        val pool = when (outcome) {
            is Outcome.Answered -> outcome.passages + outcome.candidates
            is Outcome.Unverified -> outcome.passages + outcome.candidates
            else -> emptyList()
        }
        if (item.multiStep && pool.isNotEmpty()) {
            // Think deeper as the app does it: the same pool (up to 8), no search, the large model.
            val deeperRequest = ResearchRequest(
                item.question, prior = prior, priorQuestion = item.priorQuestion,
                deeperFrom = pool.take(DEEPER_PASSAGES), medicalHint = outcome?.medical ?: false,
            )
            val deeper = execute(pipeline.run(deeperRequest))
            val d = JSONObject()
            describe(deeper.outcome).into(d)
            record.put("deeper", d.put("ms", deeper.ms))
        }
        if (outcome is Outcome.Declined && !outcome.medical && (guesses.small || guesses.large)) {
            val g = JSONObject()
            if (guesses.small) guess(item, large = false)?.let { g.put("small", it) }
            if (guesses.large) guess(item, large = true)?.let { g.put("large", it) }
            if (g.length() > 0) record.put("guesses", g)
        }
        return record
    }

    private suspend fun guess(item: EvalItem, large: Boolean): JSONObject? {
        val run = execute(pipeline.guess(item.question, item.priorQuestion, large))
        val guessed = run.outcome as? Outcome.Guessed ?: return null
        return JSONObject().put("text", guessed.text).put("ms", run.ms.optLong("total")).put("fellBack", guessed.fellBack)
    }

    private class Described(val outcome: String, val reply: String, val cited: List<Int>, val chosen: List<Passage>)

    private fun describe(outcome: Outcome?): Described = when (outcome) {
        is Outcome.Answered -> Described("answered", outcome.text, outcome.cited.sorted(), outcome.passages)
        is Outcome.Unverified -> Described("unverified", outcome.text, emptyList(), outcome.passages)
        is Outcome.Declined -> Described("declined", "", emptyList(), emptyList())
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
        const val DEEPER_PASSAGES = 8
    }
}
