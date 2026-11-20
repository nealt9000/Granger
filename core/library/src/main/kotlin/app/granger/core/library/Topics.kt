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
 * A topic page's place in time (Plan LL2 Decision 2): the people alive with its subject and the events of its span, picked
 * from the marks a time index gives for [window]. Pure, so the rules are tested without a `.gtime`.
 */
object TopicTime {
    /** The years to ask the time index for, around [subject]'s own span. */
    fun window(subject: TimeMark, now: Int): LongRange = when (subject.kind) {
        TimeKind.LIFE -> (subject.y0 - ELDER_YEARS)..subject.end(now)
        else -> (subject.y0 - ADULT_YEARS)..subject.end(now)
    }

    /**
     * Up to [n] people whose adult lives overlapped [subject], best known first. For a life: born no more than
     * [ELDER_YEARS] before, adult before it ended, and alive when the subject was [ADULT_YEARS]. For a span or a point: adult
     * and alive within it.
     */
    fun contemporaries(subject: TimeMark, overlapping: List<TimeMark>, now: Int, n: Int = 8): List<TimeMark> {
        val end = subject.end(now)
        val fits: (TimeMark) -> Boolean = when (subject.kind) {
            TimeKind.LIFE -> { p ->
                p.y0 >= subject.y0 - ELDER_YEARS && p.y0 <= end - ADULT_YEARS - 5 && p.end(now) >= subject.y0 + ADULT_YEARS
            }
            else -> { p -> p.y0 <= end - ADULT_YEARS && p.end(now) >= subject.y0 }
        }
        return overlapping.asSequence()
            .filter { it.kind == TimeKind.LIFE && it.qid != subject.qid && fits(it) }
            .distinctBy { it.qid }
            .sortedByDescending { it.rank }
            .take(n)
            .toList()
    }

    /**
     * Up to [n] events within [subject]'s span, best known first: spans starting in it, and points in the wars lane
     * (battles). Other points are mostly countries' founding years, which read as noise beside a life. For a life the
     * events start once the subject is [ADULT_YEARS] - 5.
     */
    fun events(subject: TimeMark, overlapping: List<TimeMark>, now: Int, n: Int = 6): List<TimeMark> {
        val from = if (subject.kind == TimeKind.LIFE) subject.y0 + ADULT_YEARS - 5 else subject.y0
        val to = subject.end(now)
        return overlapping.asSequence()
            .filter { it.qid != subject.qid && it.y0 in from..to }
            .filter { it.kind == TimeKind.SPAN || (it.kind == TimeKind.POINT && Lane.WARS in it.lanes) }
            .distinctBy { it.qid }
            .sortedByDescending { it.rank }
            .take(n)
            .toList()
    }

    const val ADULT_YEARS = 20
    const val ELDER_YEARS = 45
}

/** One thing near a topic in meaning: an article ([target] a pack's name, [key] its path) or a book passage ([key] its chapter). */
data class RelatedHit(val kind: SourceKind, val target: String, val key: String, val score: Float)

data class Related(val articles: List<RelatedHit>, val books: List<RelatedHit>) {
    val isEmpty: Boolean get() = articles.isEmpty() && books.isEmpty()

    companion object {
        val NONE = Related(emptyList(), emptyList())
    }
}

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
