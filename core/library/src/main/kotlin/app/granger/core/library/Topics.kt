package app.granger.core.library

import app.granger.core.vectors.top
import app.granger.core.vectors.file
import app.granger.core.research.SourceKind
import app.granger.core.vectors.VectorHit
import app.granger.core.vectors.VectorSearch
import java.util.concurrent.ExecutorService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * The closest articles and books to an article, from the vectors already installed (Plan LL2 Task 1, go on a Pixel 6 at
 * 0.7–0.96 s warm): the article's own chunk rows averaged into one query, searched over the page language's packs and,
 * at the same time, its books. Hits are grouped by article or book; the page itself, its copies in other packs and
 * front matter are dropped, and a book needs [bookFloor]. A pack without vectors has no Related.
 */
class RelatedFinder(
    private val sources: VectorSources,
    private val pool: ExecutorService,
    /** A source's language (`en`), or null when unknown; results stay in the page's language. */
    private val languageOf: suspend (VectorSource) -> String?,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val probes: Int = DensePassageFinder.PROBES,
    private val articles: Int = ARTICLES,
    private val books: Int = BOOKS,
    private val bookFloor: Float = BOOK_FLOOR,
) {
    /**
     * Related for [path] in the pack whose ZIM name is [target]. [sameItem] says whether a hit (pack name, path) is the
     * page's own subject under another title, as the QID index tells; same-path hits in other packs are dropped anyway.
     */
    suspend fun related(target: String, path: String, sameItem: (String, String) -> Boolean = { _, _ -> false }): Related =
        withContext(io) {
            val all = sources.current()
            val home = all.firstOrNull { it.kind == SourceKind.ARTICLE && it.target == target } ?: return@withContext Related.NONE
            val rows = home.file.rowsOf(path)
            if (rows.isEmpty()) return@withContext Related.NONE
            val dims = home.file.header.dims
            val query = average(home, rows, dims)
            val language = languageOf(home)
            val same = all.filter { it.file.header.dims == dims && (language == null || languageOf(it) == language) }
            val packs = same.filter { it.kind == SourceKind.ARTICLE }
            val shelf = same.filter { it.kind == SourceKind.BOOK }
            val byFile = same.associateBy { it.file }
            coroutineScope {
                val bookHits = async(io) { search(query, shelf, BOOK_POOL) }
                val articleHits = search(query, packs, ARTICLE_POOL)
                Related(
                    groupArticles(articleHits, byFile, path, sameItem),
                    groupBooks(bookHits.await(), byFile),
                )
            }
        }

    private fun search(query: FloatArray, from: List<VectorSource>, k: Int): List<VectorHit> =
        if (from.isEmpty()) emptyList() else VectorSearch.top(query, from.map { it.file }, k, pool, probes)

    private fun groupArticles(
        hits: List<VectorHit>,
        byFile: Map<app.granger.core.vectors.VectorFile, VectorSource>,
        path: String,
        sameItem: (String, String) -> Boolean,
    ): List<RelatedHit> {
        val seen = HashSet<String>()
        val out = ArrayList<RelatedHit>()
        for (h in hits) {
            val source = byFile[h.file] ?: continue
            val key = h.file.key(h.row).key
            if (key == path || !seen.add(key)) continue
            if (sameItem(source.target, key)) continue
            out += RelatedHit(SourceKind.ARTICLE, source.target, key, h.score)
            if (out.size == articles) break
        }
        return out
    }

    private fun groupBooks(hits: List<VectorHit>, byFile: Map<app.granger.core.vectors.VectorFile, VectorSource>): List<RelatedHit> {
        val seen = HashSet<String>()
        val out = ArrayList<RelatedHit>()
        for (h in hits) {
            if (h.score < bookFloor) break
            val source = byFile[h.file] ?: continue
            val key = h.file.key(h.row).key
            if (FRONT_MATTER.any { it in key.lowercase() } || !seen.add(source.target)) continue
            out += RelatedHit(SourceKind.BOOK, source.target, key, h.score)
            if (out.size == books) break
        }
        return out
    }

    private fun average(source: VectorSource, rows: IntArray, dims: Int): FloatArray {
        val sum = FloatArray(dims)
        val row = ByteArray(dims)
        for (r in rows) {
            source.file.copyRow(r, row)
            val s = source.file.scale(r)
            if (s.isNaN() || s.isInfinite()) continue
            for (i in 0 until dims) sum[i] += row[i] * s
        }
        for (i in 0 until dims) sum[i] /= rows.size
        return sum
    }

    companion object {
        const val ARTICLES = 8
        const val BOOKS = 4
        /** Below this a book shares a mood, not a subject (Metamorphoses for Photosynthesis scored 0.75; Task 1). */
        const val BOOK_FLOOR = 0.79f
        /** Wide enough that the page's own chunks and its copies leave room (k 48 left 2–3 results; Task 1). */
        private const val ARTICLE_POOL = 128
        private const val BOOK_POOL = 64
        private val FRONT_MATTER = listOf("cover", "colophon", "titlepage", "toc", "nav", "imprint", "copyright")
    }
}
