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
import app.granger.core.vectors.VectorFile
import app.granger.core.vectors.VectorHit
import app.granger.core.vectors.VectorSearch
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Debug-only, Plan LL2 Task 1: Related from the shipped vectors, timed on this phone. For each test article its chunk rows
 * are found (a linear key scan of its pack's file), averaged into one query, and searched over the page language's
 * Wikipedia files (k 128, probes 1024) and books (k 64); hits are grouped by article or book. Logs `Related:` lines.
 * Start with `adb shell am start -n app.granger/.research.RelatedSpikeActivity`.
 */
class RelatedSpikeActivity : ComponentActivity() {
    private val lines = mutableStateListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
                        Text("Related spike")
                        lines.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 10.sp) }
                    }
                }
            }
        }
        if (savedInstanceState == null) {
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) { run() }
                    log("done")
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.e(TAG, "Related: spike failed", t)
                    log("failed: $t")
                }
            }
        }
    }

    private fun log(line: String) {
        Log.i(TAG, "Related: $line")
        runOnUiThread { lines += line }
    }

    private fun run() {
        val dir = File(getExternalFilesDir(null), "vectors")
        var t = SystemClock.elapsedRealtime()
        val all = dir.listFiles { f -> f.name.endsWith(".gvec") }.orEmpty().sortedBy { it.name }.mapNotNull { VectorFile.open(it) }
        val dims = all.first { it.file.name.startsWith("wikipedia_") }.header.dims
        val files = all.filter { it.header.dims == dims }
        log("opened ${files.size} files in ${SystemClock.elapsedRealtime() - t} ms")
        val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
        val side = Executors.newSingleThreadExecutor()
        fun lang(f: VectorFile) = if ("_es_" in f.file.name || f.file.name.startsWith("gc-es-")) "es" else "en"
        fun isBook(f: VectorFile) = f.file.name.startsWith("gc-")
        val enTop = files.first { it.file.name.startsWith("wikipedia_en_top") }
        val esTop = files.first { it.file.name.startsWith("wikipedia_es_top") }

        // A key → rows map for one file, to compare with the per-article scan.
        t = SystemClock.elapsedRealtime()
        val map = HashMap<String, IntArray>()
        run {
            val tmp = HashMap<String, MutableList<Int>>()
            for (r in 0 until enTop.header.count) tmp.getOrPut(enTop.key(r).key) { ArrayList(4) }.add(r)
            tmp.forEach { (k, v) -> map[k] = v.toIntArray() }
        }
        log("key map for ${enTop.file.name.take(24)}: ${map.size} keys in ${SystemClock.elapsedRealtime() - t} ms")

        val tests = listOf(
            "London" to enTop, "Napoleon" to enTop, "Photosynthesis" to enTop, "Jane_Austen" to enTop, "Don_Quixote" to enTop,
            "Zaragoza" to esTop, "Insulin" to enTop, "Byzantine_Empire" to enTop, "Jazz" to enTop, "Volcano" to enTop,
        )
        for (pass in listOf("cold", "warm")) {
            for ((path, home) in tests) {
                val t0 = SystemClock.elapsedRealtime()
                val rows = home.rowsOf(path)
                val t1 = SystemClock.elapsedRealtime()
                if (rows.isEmpty()) { log("$pass $path: not found"); continue }
                val query = average(home, rows, dims)
                val language = lang(home)
                val wiki = files.filter { !isBook(it) && lang(it) == language }
                val books = files.filter { isBook(it) && lang(it) == language }
                val bookFuture = side.submit<List<VectorHit>> { VectorSearch.top(query, books, 64, pool, probes = 1024) }
                val hits = VectorSearch.top(query, wiki, 128, pool, probes = 1024)
                val t2 = SystemClock.elapsedRealtime()
                val bookHits = bookFuture.get()
                val t3 = SystemClock.elapsedRealtime()
                val groups = group(path, hits, bookHits)
                val t4 = SystemClock.elapsedRealtime()
                log("$pass $path: total ${t4 - t0} ms (scan ${t1 - t0}, wiki ${t2 - t1} over ${wiki.size}, books +${t3 - t2} after, over ${books.size}, group ${t4 - t3}); ${rows.size} chunks")
                if (pass == "warm") groups.forEach { log("   %.3f  %s".format(it.first, it.second)) }
            }
        }
        pool.shutdown()
        side.shutdown()
    }

    /** The rows whose key is [path], by a linear scan of the file's keys. */
    private fun rowsOf(file: VectorFile, path: String): IntArray {
        val out = ArrayList<Int>()
        for (r in 0 until file.header.count) if (file.key(r).key == path) out += r
        return out.toIntArray()
    }

    private fun average(file: VectorFile, rows: IntArray, dims: Int): FloatArray {
        val sum = FloatArray(dims)
        val row = ByteArray(dims)
        for (r in rows) {
            file.copyRow(r, row)
            val s = file.scale(r)
            for (i in 0 until dims) sum[i] += row[i] * s
        }
        for (i in 0 until dims) sum[i] /= rows.size
        return sum
    }

    /** Best hit per article (by path) or book (by file); the page itself dropped; 8 articles and up to 4 books ≥ 0.79. */
    private fun group(path: String, hits: List<VectorHit>, bookHits: List<VectorHit>): List<Pair<Float, String>> {
        val arts = LinkedHashMap<String, Float>()
        for (h in hits) {
            val key = h.file.key(h.row).key
            if (key == path) continue
            if (key !in arts) arts[key] = h.score
        }
        val books = LinkedHashMap<String, Pair<Float, String>>()
        for (h in bookHits) {
            val name = h.file.file.name.substringBeforeLast('_')
            if (name !in books && h.score >= BOOK_FLOOR) books[name] = h.score to "[book] $name · ${h.file.key(h.row).key}"
        }
        return (arts.entries.take(8).map { it.value to it.key } + books.values.take(4)).sortedByDescending { it.first }
    }

    companion object {
        private const val TAG = "Granger"
        private const val BOOK_FLOOR = 0.79f
    }
}
