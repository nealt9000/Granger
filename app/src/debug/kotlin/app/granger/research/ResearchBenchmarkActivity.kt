package app.granger.research

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import app.granger.GrangerApp
import app.granger.core.ai.LiteRtTextEmbedder
import app.granger.core.ai.LlmBackend
import app.granger.core.ai.LlmSize
import app.granger.core.research.Keywords
import app.granger.core.research.LexicalScore
import app.granger.core.research.Outcome
import app.granger.core.research.Passage
import app.granger.core.research.PassageEmbedder
import app.granger.core.research.PassageFinder
import app.granger.core.research.Ranking
import app.granger.core.research.ResearchEvent
import app.granger.core.research.ResearchModels
import app.granger.core.research.ResearchPipeline
import app.granger.core.research.ResearchRequest
import app.granger.core.research.Stage
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Debug-only: measures Research on this phone and logs `Research:` lines under the Granger tag.
 * (a) the embedder on CPU and GPU, (b) Send to first answer chunk for six questions, (c) their top cosines.
 * Start with `adb shell am start -n app.granger/.research.ResearchBenchmarkActivity`.
 */
class ResearchBenchmarkActivity : ComponentActivity() {
    private val lines = mutableStateListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
                        Text("Research benchmark")
                        lines.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                    }
                }
            }
        }
        if (savedInstanceState == null) {
            lifecycleScope.launch {
                try {
                    run()
                    log("done")
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.e(TAG, "Research: benchmark failed", t)
                    log("failed: $t")
                }
            }
        }
    }

    private fun log(line: String) {
        Log.i(TAG, "Research: $line")
        lines += line
    }

    private suspend fun run() {
        val c = (application as GrangerApp).container
        val models = c.installedModels.first()
        log("models small=${models.small?.name} large=${models.large?.name} embedder=${models.embedder?.name}")
        c.modelManager.releaseNow()

        // (a) The query and 12 real passages, embedded the way the pipeline does it (query, then passages).
        val embedderFile = models.embedder
        if (intent.getBooleanExtra("skipEmbedder", false)) {
            log("(a) skipped")
        } else if (embedderFile == null) {
            log("(a) skipped: no embedder installed")
        } else {
            val question = ANSWERABLE.first()
            val keywords = Keywords.extract(question)
            // Preselect's limit without its per-source cap, so a small library still gives a full 12.
            val passages = Ranking.preselect(c.passageFinder.find(keywords), keywords, c.rankingConfig.copy(perSource = Int.MAX_VALUE))
            log("(a) ${passages.size} passages for \"$question\" keywords=$keywords")
            for (backend in LlmBackend.entries) {
                withContext(Dispatchers.IO) {
                    val embedder = LiteRtTextEmbedder(embedderFile.path, cacheDir.path, backend)
                    try {
                        val load = timed { embedder.load() }
                        log("(a) $backend load ${load} ms")
                        repeat(3) { round ->
                            val ms = timed {
                                embedder.embed(listOf(question), isQuery = true)
                                embedder.embed(passages.map { it.text }, isQuery = false)
                            }
                            log("(a) $backend embed query+${passages.size} run ${round + 1}: $ms ms")
                        }
                    } catch (t: Throwable) {
                        log("(a) $backend failed: $t")
                    } finally {
                        embedder.close()
                    }
                }
            }
        }

        // (b) and (c), with the app's own models and thresholds, the model already loaded.
        val recorder = Recorder(ModelsAdapter(c.modelManager))
        recorder.dump = java.io.File(getExternalFilesDir(null), "research-candidates.jsonl").apply { delete() }
        val pipeline = ResearchPipeline(recorder.finder(c.passageFinder), recorder, c.rankingConfig)
        val loadMs = timed { c.modelManager.engine(LlmSize.SMALL); c.modelManager.embedder() }
        log("(b) E2B${if (c.modelManager.embedder() != null) " + embedder" else ""} loaded in $loadMs ms")
        for ((question, prior) in ANSWERABLE.map { it to null } + FOLLOW_UPS + UNANSWERABLE.map { it to null }) {
            val kind = if (question in UNANSWERABLE) "unanswerable" else "answerable"
            recorder.reset()
            val start = SystemClock.elapsedRealtime()
            var firstChunk: Long? = null
            var outcome: Outcome? = null
            var reachedModel = false
            pipeline.run(ResearchRequest(question, priorQuestion = prior)).collect { event ->
                if (event is ResearchEvent.Progress && event.stage == Stage.WRITING) reachedModel = true
                if (event is ResearchEvent.Progress) Log.i(TAG, "Research: (b)    ${event.stage} at ${SystemClock.elapsedRealtime() - start} ms")
                if (event is ResearchEvent.Partial && firstChunk == null) firstChunk = SystemClock.elapsedRealtime() - start
                if (event is ResearchEvent.Done) outcome = event.outcome
            }
            val total = SystemClock.elapsedRealtime() - start
            val first = firstChunk?.let { "$it ms" } ?: "none"
            log("(b) [$kind] \"$question\" first chunk $first, total $total ms, ${outcome?.let { it::class.simpleName }}, ${if (reachedModel) "reached the model" else "declined in ranking"}, keywords=${recorder.keywords}")
            val top = recorder.cosines.sortedByDescending { it.first }
            log("(c) [$kind] \"$question\" top cosine ${top.firstOrNull()?.first ?: "n/a"} of ${top.size}")
            top.take(3).forEach { (cos, text) -> log("(c)    %.4f %s".format(cos, text.take(70).replace('\n', ' '))) }
            val lex = recorder.lexical.sortedByDescending { it.first }
            log("(c) [$kind] \"$question\" top lexical ${lex.firstOrNull()?.first ?: "n/a"} of ${lex.size} (whole keywords only: ${recorder.wholeKeywordTop})")
            lex.take(2).forEach { (score, text) -> log("(c)    %.2f %s".format(score, text.take(70).replace('\n', ' '))) }
            (outcome as? Outcome.Answered)?.let { log("(b)    answer: ${it.text.take(160).replace('\n', ' ')}") }
        }
        c.modelManager.releaseSoon()
    }

    /** Wraps the app's models so each question's keywords and passage cosines are recorded. */
    private class Recorder(private val inner: ResearchModels) : ResearchModels {
        var keywords: List<String> = emptyList()
        val cosines = mutableListOf<Pair<Float, String>>()
        private var query: FloatArray? = null

        fun reset() {
            keywords = emptyList()
            cosines.clear()
            lexical.clear()
            wholeKeywordTop = null
            query = null
        }

        val lexical = mutableListOf<Pair<Double, String>>()

        /** When set, each question's keywords and candidates are appended here as a JSON line, for tuning off the phone. */
        var dump: java.io.File? = null

        /** The top score under Plan 8's first scoring (a keyword counted only with all its words), for comparison. */
        var wholeKeywordTop: Double? = null

        fun finder(finder: PassageFinder) = PassageFinder { words ->
            keywords = words
            val start = SystemClock.elapsedRealtime()
            finder.find(words).also { found ->
                val ms = SystemClock.elapsedRealtime() - start
                Log.i(TAG, "Research: (b)    find $ms ms, ${found.size} passages, ${found.groupingBy { it.kind }.eachCount()}")
                val score = LexicalScore.scorer(words, corpus = found)
                found.forEach { lexical += score(it) to it.text }
                dump?.let { file ->
                    val rows = found.joinToString(",") { p ->
                        JSONObject(mapOf("title" to p.sourceTitle, "heading" to p.heading, "text" to p.text, "kind" to p.kind.name)).toString()
                    }
                    file.appendText(JSONObject(mapOf("keywords" to JSONArray(words))).toString().dropLast(1) + ",\"passages\":[$rows]}\n")
                }
                wholeKeywordTop = found.maxOfOrNull { wholeKeywordScore(it, words) }
                // The pipeline's own preselect cost is the SEARCHING-to-RANKING gap minus find and this.
                Log.i(TAG, "Research: (b)    recording took ${SystemClock.elapsedRealtime() - start - ms} ms")
            }
        }

        override suspend fun generator(deeper: Boolean) = inner.generator(deeper)

        override suspend fun embedder(): PassageEmbedder? {
            val embedder = inner.embedder() ?: return null
            return PassageEmbedder { texts, isQuery ->
                val vectors = embedder.embed(texts, isQuery)
                if (isQuery) {
                    query = vectors.single()
                } else {
                    query?.let { q -> texts.indices.forEach { cosines += Ranking.cosine(q, vectors[it]) to texts[it] } }
                }
                vectors
            }
        }
    }

    private companion object {
        const val TAG = "Granger"
        val ANSWERABLE = listOf(
            "Who wrote Hamlet?", "What is the boiling point of water?", "What does Portia say about mercy?",
            "Tell me about the life of Ray Charles", "Why was Ray banned from playing in Georgia?",
        )

        /** Follow-ups and the question before them. */
        val FOLLOW_UPS = listOf("When was it first performed?" to "Who wrote Hamlet?")
        val UNANSWERABLE = listOf(
            "What is my bank's routing number?", "Who won the 2031 World Cup?", "What is the Wi-Fi password at the library?",
        )

        fun wholeKeywordScore(passage: Passage, keywords: List<String>): Double {
            if (keywords.isEmpty()) return 0.0
            fun has(text: String, k: String) = Keywords.words(k).let { w ->
                w.isNotEmpty() && w.all { Regex("(?<![\\p{L}\\p{N}])${Regex.escape(it)}(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE).containsMatchIn(text) }
            }
            val body = "${passage.heading} ${passage.text}"
            return keywords.count { has(body, it) }.toDouble() / keywords.size + if (keywords.any { has(passage.sourceTitle, it) }) 0.1 else 0.0
        }

        inline fun timed(block: () -> Unit): Long {
            val start = SystemClock.elapsedRealtime()
            block()
            return SystemClock.elapsedRealtime() - start
        }
    }
}
