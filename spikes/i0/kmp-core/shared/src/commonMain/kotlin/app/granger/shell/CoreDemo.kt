package app.granger.shell

import app.granger.core.research.ArticleText
import app.granger.core.research.YearFacts
import app.granger.core.vectors.ChunkKey
import app.granger.core.vectors.VectorFile
import app.granger.core.vectors.VectorSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.time.TimeSource

/** I0 Task 5: what both UI shells call in the shared core. Plain Kotlin types, so the Swift view of it is the plain export. */
class CoreDemo(private val dir: String) {
    val sampleHtml = SAMPLE_HTML

    fun blocks(html: String): List<String> = ArticleText.blocks(html).map { "${it.tag}: ${it.text}" }

    fun lifespan(text: String): String? = YearFacts.lifespan(text)?.let { "${it.first} – ${it.second}" }

    /** Writes [rows] random 256-dim vectors once, then searches them: a stand-in for a pack's `.gvec`. */
    suspend fun search(query: String, rows: Int = 20_000): String = withContext(Dispatchers.IO) {
        val path = "$dir/demo-$rows.gvec"
        val file = VectorFile.open(path) ?: run {
            val rnd = Random(7)
            val vectors = List(rows) { FloatArray(256) { rnd.nextFloat() - 0.5f } }
            VectorFile.write(path, vectors, 256, List(rows) { "0".repeat(16) }, List(rows) { ChunkKey("A/Row_$it", 0) }, 1, "0".repeat(64), "0".repeat(64))
            VectorFile.open(path)!!
        }
        val seed = query.hashCode()
        val q = Random(seed).let { r -> FloatArray(256) { r.nextFloat() - 0.5f } }
        val mark = TimeSource.Monotonic.markNow()
        val hits = VectorSearch.top(q, listOf(file), 5, Dispatchers.Default)
        val ms = mark.elapsedNow().inWholeMilliseconds
        "\"$query\": ${hits.size} hits over $rows rows in $ms ms\n" + hits.joinToString("\n") { "${file.key(it.row).key}  ${(it.score * 1000).toInt() / 1000.0}" }
    }

    companion object {
        val SAMPLE_HTML = """
            <html><head><meta name="viewport" content="width=device-width, initial-scale=1">
            <style>body{font-family:Georgia,serif;background:#F3EEE4;color:#1F1813;padding:16px;line-height:1.5}
            h1{font-weight:500}a{color:#2E4A45}.infobox{float:right;border:1px solid #A87B52;padding:8px;margin:0 0 8px 8px;font-size:.85em}</style></head>
            <body><h1>Kurt Gödel</h1>
            <div class="infobox">Born 28 April 1906<br>Died 14 January 1978</div>
            <p><b>Kurt Gödel</b> (April 28, 1906 – January 14, 1978) was a logician, mathematician and philosopher. He is best known for his
            <a href="A/Incompleteness_theorems">incompleteness theorems</a>, published in 1931.</p>
            <h2 id="Life">Life</h2><p>Gödel was born in Brno and studied at the University of Vienna. In 1940 he emigrated to the United States,
            where he worked at the Institute for Advanced Study in Princeton alongside Albert Einstein.</p>
            <h2 id="Work">Work</h2><p>His completeness theorem (1929) and the constructible universe (1938) shaped modern set theory.
            Select this text to see the selection menu.</p></body></html>
        """.trimIndent()
    }
}
