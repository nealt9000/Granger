package app.granger.eval

import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import app.granger.DENSE_RETRIEVAL
import app.granger.GrangerApp
import app.granger.core.ai.LiteRtLlmEngine
import app.granger.core.ai.LlmBackend
import app.granger.core.ai.RssMemorySampler
import app.granger.core.research.FictionWeight
import app.granger.core.research.ResearchPipeline
import app.granger.research.ModelsAdapter
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Debug-only: runs an eval dataset through the real Research pipeline and writes one JSON record per question.
 * `tools/eval` pushes `files/eval/dataset.json` and starts this with string extras `runId`, `ids` and `groups`
 * (comma-joined, optional) and `guesses` (none|small|large|both). Results go to `files/eval/<runId>/results.jsonl`,
 * then an empty `done` file. Logs `Eval:` lines under the Granger tag.
 */
class EvalActivity : ComponentActivity() {
    private var status by mutableStateOf("starting")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // An unattended run starts over the lock screen and keeps the screen on, so the phone never puts it in the background.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().padding(16.dp)) {
                        Text("Eval")
                        Text(status)
                    }
                }
            }
        }
        lifecycleScope.launch { EvalSession.status.collect { status = it } }
        if (savedInstanceState == null) begin()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        begin()
    }

    private fun log(line: String) {
        Log.i(TAG, "Eval: $line")
        EvalSession.status.value = line
    }

    private fun begin() {
        val root = File(getExternalFilesDir(null), "eval")
        val runId = intent.getStringExtra("runId")?.takeIf { it.isNotBlank() && '/' !in it && ".." !in it }
        if (runId == null) {
            log("no valid runId extra")
            return
        }
        intent.getStringExtra("replay")?.let { replay(root, runId, it); return }
        intent.getStringExtra("embed")?.let { embed(root, runId, it); return }
        val c = (application as GrangerApp).container
        val ids = intent.getStringExtra("ids")
        val groups = intent.getStringExtra("groups")
        // Plan R0, Task 4: the rewrite variant that gives keywords alternates.
        val alternates = intent.getStringExtra("rewrite") == "alternates"
        // Plan R4, Task 5: first questions get the plain keywords and the lexicon's flag, with no model call.
        val plain = intent.getStringExtra("rewrite") in setOf("plain", "plain-all")
        // Plan R5, Task 6: follow-ups plain too, from the question and its prior one.
        val plainAll = intent.getStringExtra("rewrite") == "plain-all"
        // Plan R5, Task 4: each thing a date question names gets a place among the passages.
        val named = intent.getStringExtra("entities") == "on"
        // Plan R5, Task 4: the dates of those things, and the sums between them, worked out in code.
        val facts = intent.getStringExtra("facts") == "on"
        // Fiction weighting is the app's (the catalog's literature books, 0.7) unless `fiction` is `off`, or names a file
        // under eval/ of fiction book ids, one per line (Plan R0, Task 6); `fictionWeight` sets the weight.
        val fictionWeight = intent.getStringExtra("fictionWeight")?.toDoubleOrNull() ?: FictionWeight.DEFAULT
        val fiction = when (val name = intent.getStringExtra("fiction")) {
            null -> if (intent.hasExtra("fictionWeight")) FictionWeight(c::literatureBooks, fictionWeight) else c.fictionWeight
            "off" -> null
            else -> File(root, name).readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                .let { ids -> FictionWeight({ ids }, fictionWeight) }
        }
        // Plan R1: `dense` on or off; the app's DENSE_RETRIEVAL when not given.
        val denseOn = intent.getStringExtra("dense")?.let { it == "on" } ?: DENSE_RETRIEVAL
        val guesses = GuessModels.parse(intent.getStringExtra("guesses"))
        val result = EvalSession.start(runId, root, ::log) {
            // Model roles come from the catalog. MainActivity loads the cached one at launch; a cold start straight
            // into this screen must too, or no model is listed and every question fails with "won't load".
            c.catalog.load()
            val items = EvalSession.loadItems(root, ids, groups)
            val packNames = c.library.packs.first().associate { it.id to it.name }
            val sourceName = evalSourceName(packNames)
            val finder = RecordingFinder(c.passageFinder)
            val prompts = PromptRecorder(ModelsAdapter(c.modelManager))
            val clock = { android.os.SystemClock.elapsedRealtime() }
            val dense = if (denseOn) RecordingDenseFinder(c.denseFinder, clock) else null
            c.embedderWanted.set(denseOn)
            try {
                val pipeline = ResearchPipeline(finder, prompts, c.rankingConfig, rewriteAlternates = alternates, rewritePlain = plain, rewritePlainFollowUps = plainAll, reserveNamed = named, yearFacts = facts, sourceWeight = fiction, dense = dense)
                val runner = EvalRunner(pipeline, finder, sourceName, prompts, dense, clock)
                runner.onItem = { item, n, total -> log("$n / $total ${item.id}") }
                log("run $runId: ${items.size} items, guesses=$guesses${if (alternates) ", rewrite=alternates" else ""}${if (plainAll) ", rewrite=plain-all" else if (plain) ", rewrite=plain" else ""}${if (named) ", entities=on" else ""}${if (facts) ", facts=on" else ""}" +
                    "${if (fiction != null) ", fiction weighted" else ""}${if (denseOn) ", dense" else ""}")
                runner.run(items, File(root, "$runId/results.jsonl"), guesses, runId)
            } finally {
                c.embedderWanted.set(DENSE_RETRIEVAL)
            }
            File(root, "$runId/done").writeText("done\n")
            log("done $runId")
            c.modelManager.releaseSoon()
        }
        when (result) {
            EvalSession.Start.STARTED -> Unit
            EvalSession.Start.ATTACHED -> log("run $runId is already running; showing its progress")
            EvalSession.Start.BUSY -> log("another eval is running; $runId was not started")
        }
    }

    /**
     * Plan R0, Task 5: `replay` names a prompts file under `eval/`, `model` a .litertlm path (absolute, or under
     * `eval/models/`), `backend` gpu (default) or cpu. Writes `eval/<runId>/replay.jsonl`, then `done`.
     * Plan R4: `speculative` on|off, `thinking` off|config|template ([PromptReplay.options]), `--ei maxTokens`.
     */
    private fun replay(root: File, runId: String, prompts: String) {
        val modelName = intent.getStringExtra("model").orEmpty()
        val model = if (modelName.startsWith("/")) File(modelName) else File(root, "models/$modelName")
        val backend = if (intent.getStringExtra("backend").equals("cpu", ignoreCase = true)) LlmBackend.CPU else LlmBackend.GPU
        val speculative = intent.getStringExtra("speculative")
        val thinking = intent.getStringExtra("thinking")
        val maxTokens = intent.getIntExtra("maxTokens", PromptReplay.ANSWER_TOKENS)
        val result = EvalSession.start(runId, root, ::log) {
            require(model.canRead()) { "can't read model $model" }
            val options = PromptReplay.options(speculative, thinking)
            val replay = PromptReplay(
                { path, b -> LiteRtLlmEngine(path, b, cacheDir.path, options) }, RssMemorySampler(), maxTokens, ::meminfo,
            ) { android.os.SystemClock.elapsedRealtime() }
            replay.onItem = { id, n, total -> log("$n / $total $id") }
            log("replay $runId: ${model.name} on $backend, speculative ${speculative ?: "default"}, thinking ${thinking ?: "default"}, maxTokens $maxTokens")
            val dir = File(root, runId).apply { mkdirs() }
            replay.run(File(root, prompts), model.path, backend, File(dir, "replay.jsonl"))
            File(dir, "done").writeText("done\n")
            log("done $runId")
        }
        if (result != EvalSession.Start.STARTED) log("replay $runId not started: $result")
    }

    /**
     * Plan R5, Task 6: `embed` names a file under `eval/` of texts, one per line; writes `eval/<runId>/vectors.jsonl`,
     * each text's query vector from the app's own embedder ({"text", "v"}), then `done`. Task 7: each batch's time goes
     * to `eval/<runId>/timing.jsonl` ({"n", "ms"}), to cost trimming's sentences at question time.
     */
    private fun embed(root: File, runId: String, texts: String) {
        val c = (application as GrangerApp).container
        val result = EvalSession.start(runId, root, ::log) {
            c.catalog.load()
            c.embedderWanted.set(true)
            try {
                val embedder = checkNotNull(ModelsAdapter(c.modelManager).embedder()) { "no embedder installed" }
                val lines = File(root, texts).readLines().filter { it.isNotBlank() }
                val dir = File(root, runId).apply { mkdirs() }
                File(dir, "timing.jsonl").bufferedWriter().use { timing -> File(dir, "vectors.jsonl").bufferedWriter().use { out ->
                    lines.chunked(16).forEachIndexed { k, batch ->
                        log("${k * 16} / ${lines.size}")
                        val start = android.os.SystemClock.elapsedRealtime()
                        val vectors = embedder.embed(batch, isQuery = true)
                        timing.write("{\"n\":${batch.size},\"ms\":${android.os.SystemClock.elapsedRealtime() - start}}\n")
                        vectors.zip(batch).forEach { (v, t) ->
                            out.write(org.json.JSONObject().put("text", t).put("v", org.json.JSONArray(v.toList())).toString())
                            out.write("\n")
                        }
                    }
                } }
            } finally {
                c.embedderWanted.set(DENSE_RETRIEVAL)
            }
            File(root, "$runId/done").writeText("done\n")
            log("done $runId")
        }
        if (result != EvalSession.Start.STARTED) log("embed $runId not started: $result")
    }

    /** This process's memory as `dumpsys meminfo` totals it, in MB: PSS overall and the GPU's share of it. */
    private fun meminfo(): Map<String, Int> {
        val info = android.os.Debug.MemoryInfo().also { android.os.Debug.getMemoryInfo(it) }
        val graphicsKb = info.getMemoryStat("summary.graphics")?.toIntOrNull() ?: 0
        return mapOf("totalPssMb" to info.totalPss / 1024, "graphicsMb" to graphicsKb / 1024)
    }

    private companion object {
        const val TAG = "Granger"
    }
}
