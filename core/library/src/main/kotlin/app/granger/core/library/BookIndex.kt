package app.granger.core.library

import android.database.SQLException
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import app.granger.core.books.EpubInspector
import app.granger.core.books.Inspection
import app.granger.core.books.PdfText
import app.granger.core.books.Snippets
import app.granger.core.books.TextChunk
import app.granger.core.books.TextQuote
import app.granger.core.books.XhtmlText
import app.granger.core.research.Keywords
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A place in a book where the search words occur. [snippet] is HTML with `<b>` highlights; empty if unavailable. */
data class BookHit(
    val bookId: String,
    val bookTitle: String,
    val author: String,
    val href: String,
    val progression: Double,
    val snippet: String,
    /** The match itself, for the reader to find and mark on the page; null if the text couldn't be read. */
    val quote: TextQuote? = null,
    /** How many places in the book match, of the first few hundred found; Search shows it when one book has several. */
    val matches: Int = 1,
)

/**
 * What a chunk of [book] at [href] is called on a card: "Page 12" in a PDF; in an EPUB, "Chapter n" by the order of
 * the chapters with text among [chunks] (the book's chunks in reading order).
 */
fun chunkLabel(book: Book, href: String, chunks: List<BookChunk>): String =
    PdfText.pageOf(href)?.takeIf { book.format == BookFormat.PDF }?.let { "Page $it" }
        ?: "Chapter ${chunks.map { it.href }.distinct().indexOf(href) + 1}"

/** A book chunk that matches some of the words asked about, with its text. */
data class BookPassage(val book: Book, val href: String, val progression: Double, val text: String)

/**
 * Full-text search over books. Each `book_chunks` row has a `book_text` row with the same id holding its text;
 * the text isn't stored, so snippets re-read the book. `book_text` is contentless and can't delete rows: a
 * re-indexed or removed book leaves orphans that the join drops, and [compactIfNeeded] clears them out.
 *
 * @param compactThreshold the fewest orphans that trigger a rebuild, if they also outnumber the live chunks.
 */
class BookIndex(
    private val db: LibraryDatabase,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
    private val compactThreshold: Int = 50_000,
) {
    private val dao = db.bookDao()
    private val mutex = Mutex()

    /** Test hook: runs inside each book's transaction just before it commits. */
    internal var beforeCommit: (bookId: String) -> Unit = {}

    /** A book's indexed chunks, in reading order; empty until the book is indexed. */
    suspend fun chunks(bookId: String): List<BookChunk> = dao.chunksOf(bookId)

    /**
     * Indexes every OK book not yet indexed whose file is on the phone (a Gutenberg book once opened). A book that
     * fails stays pending for the next call.
     */
    suspend fun indexPending() = mutex.withLock {
        withContext(io) {
            indexAll()
            compact()
        }
    }

    /**
     * The places in books matching [query], best first: its words as a phrase, the last a prefix ("peter pan*"), rank
     * above chunks holding the words apart, each tier in BM25 order (the ranking [passages] uses). The top [limit]
     * chunks are kept, at most [perBook] from one book, grouped under the book of each one's best chunk.
     */
    suspend fun search(query: String, limit: Int = LIMIT, perBook: Int = 3): List<BookHit> {
        // Quotes would end the phrase early; a typed trailing `*` is dropped since the last word gets one anyway.
        val words = query.split(WHITESPACE).map { it.replace("\"", "").trimEnd('*') }
            .filter { word -> word.any { it.isLetterOrDigit() } }
        if (words.isEmpty()) return emptyList()
        val phrase = "\"${words.joinToString(" ")}*\""
        val apart = words.mapIndexed { i, w -> if (i == words.lastIndex) "\"$w*\"" else "\"$w\"" }.joinToString(" ")
        return withContext(io) {
            val inPhrase = scores(listOf(phrase), bookId = null)
            val all = if (words.size == 1) inPhrase else scores(listOf(apart), bookId = null)
            val ranked = all.keys.sortedWith(
                compareByDescending<Long> { it in inPhrase }
                    .thenByDescending { (inPhrase[it] ?: 0.0) + all.getValue(it) }
                    .thenBy { it },
            )
            val kept = topChunks(ranked, limit, perBook)
            val counts = matchCounts(apart, kept.map { it.bookId }.distinct())
            val snippetQuery = words.joinToString(" ")
            kept.groupBy { it.bookId }.flatMap { (bookId, hits) ->
                val book = dao.get(bookId) ?: return@flatMap emptyList()
                val texts = chunkTexts(book, hits)
                hits.map { chunk ->
                    BookHit(
                        bookId = book.id,
                        bookTitle = book.title,
                        author = book.author,
                        href = chunk.href,
                        progression = chunk.progression,
                        snippet = texts[chunk.id]?.let { Snippets.around(it, snippetQuery) }.orEmpty(),
                        quote = texts[chunk.id]?.let { Snippets.quote(it, snippetQuery) },
                        matches = counts[book.id] ?: hits.size,
                    )
                }
            }
        }
    }

    /** How many chunks of each of [bookIds] match [match]; empty for a bad query. */
    private fun matchCounts(match: String, bookIds: List<String>): Map<String, Int> {
        if (bookIds.isEmpty()) return emptyMap()
        val counts = HashMap<String, Int>()
        try {
            db.openHelper.readableDatabase.query(
                SimpleSQLiteQuery(
                    "SELECT c.bookId, COUNT(*) FROM book_text t JOIN book_chunks c ON c.id = t.docid " +
                        "WHERE book_text MATCH ? AND c.bookId IN (${bookIds.joinToString(",") { "?" }}) GROUP BY c.bookId",
                    arrayOf<Any>(match, *bookIds.toTypedArray()),
                ),
            ).use { c -> while (c.moveToNext()) counts[c.getString(0)] = c.getInt(1) }
        } catch (_: SQLException) {
            // No counts; each hit counts its own book's listed chunks.
        }
        return counts
    }

    /**
     * Chunks matching any of [keywords], best first, with their text. A keyword matches a chunk holding all its words,
     * in any order, so the rewrite's phrases ("portia mercy") find text that doesn't say them verbatim. A chunk scores
     * the sum of its BM25 over every keyword it matches, so chunks matching several keywords, or rare ones, rise. At
     * most [perBook] come from one book and [limit] in all; a bad query gives none.
     */
    suspend fun passages(keywords: List<String>, limit: Int = 40, perBook: Int = 4): List<BookPassage> =
        rankedPassages(keywords, bookId = null, limit = limit, perBook = perBook)

    /** [passages] within one book, with no per-book cap: the best [limit] chunks of [bookId], best first. */
    suspend fun passagesIn(bookId: String, keywords: List<String>, limit: Int = 8): List<BookPassage> =
        rankedPassages(keywords, bookId = bookId, limit = limit, perBook = Int.MAX_VALUE)

    private suspend fun rankedPassages(keywords: List<String>, bookId: String?, limit: Int, perBook: Int): List<BookPassage> {
        // Each word quoted, so query syntax (OR, NEAR, -) inside a keyword is just words; spaces AND them.
        val matches = keywords.map { k -> Keywords.words(k) }.filter { it.isNotEmpty() }.distinct()
            .map { words -> words.joinToString(" ") { "\"$it\"" } }
        if (matches.isEmpty()) return emptyList()
        return withContext(io) {
            val scores = scores(matches, bookId)
            val ranked = scores.entries.sortedWith(compareByDescending<Map.Entry<Long, Double>> { it.value }.thenBy { it.key })
            val chunks = topChunks(ranked.map { it.key }, limit, perBook)
            val texts = HashMap<Long, BookPassage>()
            chunks.groupBy { it.bookId }.forEach { (id, hits) ->
                val book = dao.get(id) ?: return@forEach
                // One damaged book drops only its own passages.
                val read = try {
                    chunkTexts(book, hits)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return@forEach
                }
                for (chunk in hits) read[chunk.id]?.let { texts[chunk.id] = BookPassage(book, chunk.href, chunk.progression, it) }
            }
            chunks.mapNotNull { texts[it.id] }
        }
    }

    /**
     * Each chunk matching any of [matches] (FTS4 queries), scored by the sum of its BM25 over the ones it matches;
     * within [bookId] when it's set. A bad query adds nothing.
     */
    private fun scores(matches: List<String>, bookId: String?): Map<Long, Double> {
        val scores = HashMap<Long, Double>()
        val sql = db.openHelper.readableDatabase
        val scope = if (bookId == null) "" else " AND docid IN (SELECT id FROM book_chunks WHERE bookId = ?)"
        for (match in matches) {
            try {
                // Rows come in docid order, so a word matching more than 3000 chunks scores only its first 3000.
                sql.query(
                    SimpleSQLiteQuery(
                        "SELECT docid, matchinfo(book_text, 'pcnx') FROM book_text WHERE book_text MATCH ?$scope LIMIT 3000",
                        if (bookId == null) arrayOf<Any>(match) else arrayOf<Any>(match, bookId),
                    ),
                ).use { c ->
                    while (c.moveToNext()) scores.merge(c.getLong(0), bm25(c.getBlob(1)), Double::plus)
                }
            } catch (_: SQLException) {
                // No candidates for this query.
            }
        }
        return scores
    }

    /** The chunks of [ranked] ids, in that order, until [limit] are kept, at most [perBook] from one book. */
    private suspend fun topChunks(ranked: List<Long>, limit: Int, perBook: Int): List<BookChunk> {
        // Rows load in rank order, 200 ids at a time. Orphans of re-indexed books have no row.
        val perBookCount = HashMap<String, Int>()
        val chunks = ArrayList<BookChunk>()
        for (ids in ranked.chunked(200)) {
            if (chunks.size >= limit) break
            val rows = try {
                dao.chunksMatching(SimpleSQLiteQuery("SELECT * FROM book_chunks WHERE id IN (${ids.joinToString(",")})"))
            } catch (_: SQLException) {
                break
            }.associateBy { it.id }
            for (id in ids) {
                if (chunks.size >= limit) break
                val chunk = rows[id] ?: continue
                if (perBookCount.merge(chunk.bookId, 1, Int::plus)!! <= perBook) chunks.add(chunk)
            }
        }
        return chunks
    }

    /** Clears out `book_text` when orphaned rows dominate it, then re-indexes every book. */
    internal suspend fun compactIfNeeded() = mutex.withLock { withContext(io) { compact() } }

    private suspend fun compact() {
        val count = dao.chunkCount()
        val orphans = chunkSequence() - count
        if (orphans <= maxOf(count, compactThreshold)) return
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("DROP TABLE IF EXISTS book_text")
            LibraryDatabase.createBookText(sql)
            sql.execSQL("DELETE FROM book_chunks")
            // Safe now that book_text is empty, and without it the orphan count would never fall.
            sql.execSQL("DELETE FROM sqlite_sequence WHERE name = 'book_chunks'")
            dao.clearIndexed()
        }
        indexAll()
    }

    private suspend fun indexAll() {
        for (book in dao.notIndexed()) {
            // A Gutenberg book is a row long before it's a file (Plan GB): its text joins once it's been opened.
            if (!File(book.filePath).isFile) continue
            try {
                index(book)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Left pending; the next call tries again.
            }
        }
    }

    /**
     * All or nothing: the book's chunks and text go in, and it's marked indexed, in one transaction. It's marked only
     * if the row is unchanged since [book] was read.
     */
    private suspend fun index(book: Book) {
        val file = File(book.filePath)
        if (!file.isFile) return
        if (book.format == BookFormat.PDF) return indexPdf(book, file)
        val spine = (EpubInspector.inspect(file) as? Inspection.Ok)?.meta?.spine ?: return
        write(book, XhtmlText.all(file, spine), searchable = true)
    }

    /**
     * A PDF's text is read before the transaction: a long scan takes tens of seconds to extract, and holding the
     * write lock that long would stall every save of a reading place. A PDF with no text layer is marked unsearchable.
     */
    private suspend fun indexPdf(book: Book, file: File) {
        val started = System.nanoTime()
        var pages = 0
        val chunks = PdfText.open(file)?.use { reader ->
            pages = reader.pageCount
            if (!reader.hasTextLayer()) {
                emptyList()
            } else {
                (1..reader.pageCount).flatMap { reader.chunks(PdfText.href(it)) }
            }
        } ?: return
        write(book, chunks.asSequence(), searchable = chunks.isNotEmpty())
        val ms = (System.nanoTime() - started) / 1_000_000
        runCatching { android.util.Log.i("Granger", "Indexed PDF ${book.id}: $pages pages, ${chunks.size} chunks in $ms ms") }
    }

    private suspend fun write(book: Book, chunks: Sequence<TextChunk>, searchable: Boolean) {
        db.withTransaction {
            dao.deleteChunks(book.id)
            val insert = db.openHelper.writableDatabase.compileStatement("INSERT INTO book_text(docid, text) VALUES(?, ?)")
            insert.use { statement ->
                for (chunk in chunks) {
                    val id = dao.insertChunk(BookChunk(0, book.id, chunk.href, chunk.ordinal, chunk.progression))
                    statement.clearBindings()
                    statement.bindLong(1, id)
                    statement.bindString(2, chunk.text)
                    statement.executeInsert()
                }
            }
            beforeCommit(book.id)
            // A re-download or refresh while the file was being read means this text may be stale: stay pending.
            if (dao.get(book.id) == book) dao.setIndexedSearchable(book.id, now(), searchable)
        }
    }

    /** The highest `book_chunks` id ever handed out. */
    private fun chunkSequence(): Long =
        db.query("SELECT seq FROM sqlite_sequence WHERE name = 'book_chunks'", null).use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        }

    /** Re-extracts the text of [hits], opening the book's file once; a missing file or chunk gives no entry. */
    private fun chunkTexts(book: Book, hits: List<BookChunk>): Map<Long, String> {
        if (book.format == BookFormat.PDF) {
            val reader = PdfText.open(File(book.filePath)) ?: return emptyMap()
            return reader.use {
                val byHref = hits.map { it.href }.distinct().associateWith { href -> reader.chunks(href) }
                hits.mapNotNull { chunk -> byHref[chunk.href]?.getOrNull(chunk.ordinal)?.let { chunk.id to it.text } }.toMap()
            }
        }
        val zip = try { ZipFile(book.filePath) } catch (_: IOException) { return emptyMap() }
        return zip.use {
            val byHref = hits.map { it.href }.distinct().associateWith { href -> XhtmlText.chunks(zip, href) }
            hits.mapNotNull { chunk -> byHref[chunk.href]?.getOrNull(chunk.ordinal)?.let { chunk.id to it.text } }.toMap()
        }
    }

    companion object {
        /** The most hits [search] returns by default. */
        const val LIMIT = 30
        private val WHITESPACE = Regex("\\s+")
    }
}

/**
 * BM25 (k1 = 1.2, no length normalisation) of one row from FTS4 `matchinfo(..., 'pcnx')`: unsigned little-endian ints
 * `p c n`, then `hits-in-row, hits-in-all-rows, rows-with-hits` per phrase and column. Sums the phrases (one column).
 */
internal fun bm25(matchinfo: ByteArray): Double {
    val ints = IntArray(matchinfo.size / 4) { i ->
        (matchinfo[4 * i].toInt() and 0xFF) or ((matchinfo[4 * i + 1].toInt() and 0xFF) shl 8) or
            ((matchinfo[4 * i + 2].toInt() and 0xFF) shl 16) or ((matchinfo[4 * i + 3].toInt() and 0xFF) shl 24)
    }
    if (ints.size < 3) return 0.0
    val phrases = ints[0]
    val n = ints[2].toLong() and 0xFFFFFFFFL
    var score = 0.0
    for (i in 0 until phrases) {
        if (3 + 3 * i + 2 >= ints.size) break
        val tf = (ints[3 + 3 * i].toLong() and 0xFFFFFFFFL).toDouble()
        val df = (ints[3 + 3 * i + 2].toLong() and 0xFFFFFFFFL).toDouble()
        val idf = Math.log((n - df + 0.5) / (df + 0.5) + 1)
        score += idf * tf * 2.2 / (tf + 1.2)
    }
    return score
}
