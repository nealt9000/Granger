package app.granger.eval

import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Holds the one running eval at process level, so rotating the screen or starting the activity again neither
 * stops it nor starts a second loop on the same file.
 */
object EvalSession {
    enum class Start { STARTED, ATTACHED, BUSY }

    /** The latest progress line, for whichever activity is showing. */
    val status = MutableStateFlow("")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var running: String? = null

    /** Starts [body] under [runId]; the same id already running attaches to it, another id is refused. */
    fun start(runId: String, root: File, log: (String) -> Unit, body: suspend () -> Unit): Start {
        synchronized(this) {
            val current = running
            if (current != null) return if (current == runId) Start.ATTACHED else Start.BUSY
            running = runId
        }
        scope.launch {
            try {
                guarded(root, runId, log, body)
            } finally {
                synchronized(this@EvalSession) { running = null }
            }
        }
        return Start.STARTED
    }

    /** Runs [body]; on any failure (including Errors) writes `eval/<runId>/failed` with the message so the desktop stops waiting. */
    suspend fun guarded(root: File, runId: String, log: (String) -> Unit, body: suspend () -> Unit) {
        try {
            body()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            val message = "${t::class.simpleName}: ${t.message}"
            log("failed: $message")
            try {
                File(root, runId).apply { mkdirs() }.resolve("failed").writeText(message + "\n")
            } catch (io: Exception) {
                log("could not write the failed marker: $io")
            }
        }
    }

    /** The dataset at `<root>/dataset.json`, filtered; throws with a readable message when it is missing. */
    fun loadItems(root: File, ids: String?, groups: String?): List<EvalItem> {
        val dataset = File(root, "dataset.json")
        check(dataset.exists()) { "no dataset at $dataset" }
        return EvalJson.filter(EvalJson.parseDataset(dataset.readText()), ids, groups)
    }
}
