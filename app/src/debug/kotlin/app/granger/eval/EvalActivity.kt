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
import app.granger.GrangerApp
import app.granger.core.research.Passage
import app.granger.core.research.ResearchPipeline
import app.granger.core.research.SourceKind
import app.granger.research.ModelsAdapter
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
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
        if (savedInstanceState == null) {
            lifecycleScope.launch {
                try {
                    run()
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.e(TAG, "Eval: failed", t)
                    status = "failed: $t"
                }
            }
        }
    }

    private fun log(line: String) {
        Log.i(TAG, "Eval: $line")
        status = line
    }

    private suspend fun run() {
        val runId = intent.getStringExtra("runId")?.takeIf { it.isNotBlank() && '/' !in it && ".." !in it }
        if (runId == null) {
            log("no valid runId extra")
            return
        }
        val root = File(getExternalFilesDir(null), "eval")
        val dataset = File(root, "dataset.json")
        if (!dataset.exists()) {
            log("no dataset at $dataset")
            return
        }
        val items = EvalJson.filter(EvalJson.parseDataset(dataset.readText()), intent.getStringExtra("ids"), intent.getStringExtra("groups"))
        val guesses = GuessModels.parse(intent.getStringExtra("guesses"))
        val c = (application as GrangerApp).container
        val packNames = c.library.packs.first().associate { it.id to it.name }
        val sourceName = { p: Passage -> if (p.kind == SourceKind.ARTICLE) packNames[p.sourceId]?.ifBlank { null } ?: p.sourceId else p.sourceId }
        val finder = RecordingFinder(c.passageFinder)
        val pipeline = ResearchPipeline(finder, ModelsAdapter(c.modelManager), c.rankingConfig)
        val runner = EvalRunner(pipeline, finder, sourceName) { android.os.SystemClock.elapsedRealtime() }
        runner.onItem = { item, n, total -> log("$n / $total ${item.id}") }
        val results = File(root, "$runId/results.jsonl")
        log("run $runId: ${items.size} items, guesses=$guesses")
        runner.run(items, results, guesses, runId)
        File(root, "$runId/done").writeText("done\n")
        log("done $runId")
        c.modelManager.releaseSoon()
    }

    private companion object {
        const val TAG = "Granger"
    }
}
