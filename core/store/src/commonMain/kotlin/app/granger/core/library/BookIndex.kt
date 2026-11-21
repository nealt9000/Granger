package app.granger.core.library

import androidx.room.RoomRawQuery
import androidx.room.execSQL
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteException
import app.granger.core.books.EpubInspector
import app.granger.core.books.Inspection
import app.granger.core.books.PdfText
import app.granger.core.books.Snippets
import app.granger.core.books.TextChunk
import app.granger.core.books.XhtmlText
import app.granger.core.books.openBookZip
import app.granger.core.research.Keywords
import app.granger.core.text.Spellings
import kotlin.math.ln
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.io.IOException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Full-text search over books. Each `book_chunks` row has a `book_text` row with the same id holding its text;
 * the text isn't stored, so snippets re-read the book. `book_text` is contentless and can't delete rows: a
 * re-indexed or removed book leaves orphans that the join drops, and [compactIfNeeded] clears them out.
 *
 * Shared by both phones since Plan I5: `book_text` is read and written through Room's connections (Android's framework
 * SQLite in Room's compatibility mode, as before; the bundled SQLite on the iPhone), inside the same transactions.
 *
 * @param compactThreshold the fewest orphans that trigger a rebuild, if they also outnumber the live chunks.
 */
class BookIndex(
    private val db: LibraryDatabase,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = ::epochMillis,
    private val compactThreshold: Int = 50_000,
) {
    private val dao = db.bookDao()
    private val mutex = Mutex()

    /** Test hook: runs inside each book's transaction just before it commits. Public for Android's tests in :core:library. */
    var beforeCommit: (bookId: String) -> Unit = {}

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
        // Each spelling of the query ("grey" and "gray") is matched on its own; a chunk keeps its best score of them.
        val forms = Spellings.forms(words)
        val phrases = forms.map { f -> "\"${f.joinToString(" ")}*\"" }
        val aparts = forms.map { f -> f.mapIndexed { i, w -> if (i == f.lastIndex) "\"$w*\"" else "\"$w\"" }.joinToString(" ") }
        return withContext(io) {
            val inPhrase = bestScores(phrases)
            val all = if (words.size == 1) inPhrase else bestScores(aparts)
            val ranked = all.keys.sortedWith(
                compareByDescending<Long> { it in inPhrase }
                    .thenByDescending { (inPhrase[it] ?: 0.0) + all.getValue(it) }
                    .thenBy { it },
            )
            val kept = topChunks(ranked, limit, perBook)
            val ids = kept.map { it.bookId }.distinct()
            val counts = HashMap<String, Int>()
            for (apart in aparts) matchCounts(apart, ids).forEach { (id, n) -> counts.mergeWith(id, n, ::maxOf) }
            kept.groupBy { it.bookId }.flatMap { (bookId, hits) ->
                val book = dao.get(bookId) ?: return@flatMap emptyList()
                val texts = chunkTexts(book, hits)
                hits.map { chunk ->
                    val snippetQuery = texts[chunk.id]?.let { formIn(it, forms) } ?: words.joinToString(" ")
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

    /**
     * Plan SQ Step 4: the chunks that hold [query] as a quote, best first, one per book, at most [limit]: its words as a
     * phrase, else (a word misremembered or left out) each word within [NEAR] words of the next. Empty for fewer than
     * [QUOTE_WORDS] words, or when the words are so common that more than [QUOTE_CHUNKS] chunks hold them: then the query
     * isn't pointing at one place ("thought he had been").
     */
    suspend fun quoted(query: String, limit: Int = 2): List<BookPassage> {
        val words = Keywords.words(query)
        if (words.size < QUOTE_WORDS) return emptyList()
        val forms = Spellings.forms(words)
        val phrases = forms.map { f -> "\"${f.joinToString(" ")}\"" }
        val nears = forms.map { f -> f.joinToString(" NEAR/$NEAR ") { "\"$it\"" } }
        return withContext(io) {
            // The near form is only scored when the phrase finds nothing.
            var first: Map<Long, Double>? = null
            for (form in listOf(phrases, nears)) {
                first = bestScores(form).takeIf { it.isNotEmpty() } ?: continue
                break
            }
            val found = first?.takeIf { it.size <= QUOTE_CHUNKS } ?: return@withContext emptyList()
            val ranked = found.entries.sortedWith(compareByDescending<Map.Entry<Long, Double>> { it.value }.thenBy { it.key })
            // The oldest book first: famous lines are quoted by later books ("Alas, poor Yorick" is in Tristram Shandy,
            // Hard Times and The Brothers Karamazov too, each once, so BM25 can't tell them from Hamlet).
            val books = topChunks(ranked.map { it.key }, QUOTE_CHUNKS, perBook = 1).mapNotNull { c -> dao.get(c.bookId)?.let { c to it } }
            books.sortedBy { (_, book) -> year(book.date) ?: Int.MAX_VALUE }.take(limit).mapNotNull { (chunk, book) ->
                val text = try {
                    chunkTexts(book, listOf(chunk))[chunk.id]
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                text?.let { BookPassage(book, chunk.href, chunk.progression, it) }
            }
        }
    }

    /** The year a book's date starts with (negative for BCE dates written "-0429"); null when it has none. */
    private fun year(date: String?): Int? = date?.let { YEAR.find(it)?.value?.toIntOrNull() }

    /** How many chunks of each of [bookIds] match [match]; empty for a bad query. */
    private suspend fun matchCounts(match: String, bookIds: List<String>): Map<String, Int> {
        if (bookIds.isEmpty()) return emptyMap()
        val counts = HashMap<String, Int>()
        try {
            // CROSS JOIN keeps book_text first, so the match runs once. With a plain JOIN SQLite may walk the books'
            // chunks and re-run the whole match for each one: minutes for a quote of common words ("does not in").
            db.useReaderConnection { connection ->
                connection.usePrepared(
                    "SELECT c.bookId, COUNT(*) FROM book_text t CROSS JOIN book_chunks c ON c.id = t.docid " +
                        "WHERE book_text MATCH ? AND c.bookId IN (${bookIds.joinToString(",") { "?" }}) GROUP BY c.bookId",
                ) { st ->
                    st.bindText(1, match)
                    bookIds.forEachIndexed { i, id -> st.bindText(i + 2, id) }
                    while (st.step()) counts[st.getText(0)] = st.getLong(1).toInt()
                }
            }
        } catch (_: SQLiteException) {
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
        // Each spelling of a keyword ("grey horse", "gray horse") is a keyword of its own.
        val matches = keywords.map { k -> Keywords.words(k) }.filter { it.isNotEmpty() }.flatMap { Spellings.forms(it) }.distinct()
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
    private suspend fun scores(matches: List<String>, bookId: String?): Map<Long, Double> {
        val scores = HashMap<Long, Double>()
        val scope = if (bookId == null) "" else " AND docid IN (SELECT id FROM book_chunks WHERE bookId = ?)"
        for (match in matches) {
            try {
                // Rows come in docid order, so a word matching more than 3000 chunks scores only its first 3000.
                db.useReaderConnection { connection ->
                    connection.usePrepared(
                        "SELECT docid, matchinfo(book_text, 'pcnx') FROM book_text WHERE book_text MATCH ?$scope LIMIT 3000",
                    ) { st ->
                        st.bindText(1, match)
                        if (bookId != null) st.bindText(2, bookId)
                        while (st.step()) scores.mergeWith(st.getLong(0), bm25(st.getBlob(1)), Double::plus)
                    }
                }
            } catch (_: SQLiteException) {
                // No candidates for this query.
            }
        }
        return scores
    }

    /** Each chunk matching any of [matches], scored by its best BM25 among them (the spellings of one query). */
    private suspend fun bestScores(matches: List<String>): Map<Long, Double> {
        if (matches.size == 1) return scores(matches, bookId = null)
        val best = HashMap<Long, Double>()
        for (match in matches) scores(listOf(match), bookId = null).forEach { (id, s) -> best.mergeWith(id, s, ::maxOf) }
        return best
    }

    /** The spelling of the query that [text] holds, every word of it (case aside), for its snippet; else the first. */
    private fun formIn(text: String, forms: List<List<String>>): String {
        val lower = text.lowercase()
        val form = forms.firstOrNull { f -> f.all { w -> Keywords.words(w).all { it.lowercase() in lower } } } ?: forms.first()
        return form.joinToString(" ")
    }

    /** The chunks of [ranked] ids, in that order, until [limit] are kept, at most [perBook] from one book. */
    private suspend fun topChunks(ranked: List<Long>, limit: Int, perBook: Int): List<BookChunk> {
        // Rows load in rank order, 200 ids at a time. Orphans of re-indexed books have no row.
        val perBookCount = HashMap<String, Int>()
        val chunks = ArrayList<BookChunk>()
        for (ids in ranked.chunked(200)) {
            if (chunks.size >= limit) break
            val rows = try {
                dao.chunksMatching(RoomRawQuery("SELECT * FROM book_chunks WHERE id IN (${ids.joinToString(",")})"))
            } catch (_: SQLiteException) {
                break
            }.associateBy { it.id }
            for (id in ids) {
                if (chunks.size >= limit) break
                val chunk = rows[id] ?: continue
                if (perBookCount.mergeWith(chunk.bookId, 1, Int::plus) <= perBook) chunks.add(chunk)
            }
        }
        return chunks
    }

    /** Clears out `book_text` when orphaned rows dominate it, then re-indexes every book. Public for Android's tests. */
    suspend fun compactIfNeeded() = mutex.withLock { withContext(io) { compact() } }

    private suspend fun compact() {
        val count = dao.chunkCount()
        val orphans = chunkSequence() - count
        if (orphans <= maxOf(count, compactThreshold)) return
        db.inTransaction {
            db.useWriterConnection { sql ->
                sql.execSQL("DROP TABLE IF EXISTS book_text")
                sql.execSQL(LibraryDatabase.BOOK_TEXT_SQL)
                sql.execSQL("DELETE FROM book_chunks")
                // Safe now that book_text is empty, and without it the orphan count would never fall.
                sql.execSQL("DELETE FROM sqlite_sequence WHERE name = 'book_chunks'")
            }
            dao.clearIndexed()
        }
        indexAll()
    }

    private suspend fun indexAll() {
        for (book in dao.notIndexed()) {
            // A Gutenberg book is a row long before it's a file (Plan GB): its text joins once it's been opened.
            if (!Disk.isFile(book.filePath)) continue
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
        val file = book.filePath
        if (!Disk.isFile(file)) return
        if (book.format == BookFormat.PDF) return indexPdf(book, file)
        val spine = (EpubInspector.inspect(file) as? Inspection.Ok)?.meta?.spine ?: return
        write(book, XhtmlText.all(file, spine), searchable = true)
    }

    /**
     * A PDF's text is read before the transaction: a long scan takes tens of seconds to extract, and holding the
     * write lock that long would stall every save of a reading place. A PDF with no text layer is marked unsearchable.
     */
    private suspend fun indexPdf(book: Book, file: String) {
        val started = TimeSource.Monotonic.markNow()
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
        val ms = started.elapsedNow().inWholeMilliseconds
        logInfo("Indexed PDF ${book.id}: $pages pages, ${chunks.size} chunks in $ms ms")
    }

    private suspend fun write(book: Book, chunks: Sequence<TextChunk>, searchable: Boolean) {
        db.inTransaction {
            dao.deleteChunks(book.id)
            // Each batch's chunks go in as rows, then their text into book_text under the same ids, on the transaction's
            // connection (a statement's block can't call the DAO, so they can't alternate one by one).
            for (batch in chunks.chunked(TEXT_BATCH)) {
                val ids = batch.map { chunk -> dao.insertChunk(BookChunk(0, book.id, chunk.href, chunk.ordinal, chunk.progression)) }
                db.useWriterConnection { connection ->
                    connection.usePrepared("INSERT INTO book_text(docid, text) VALUES(?, ?)") { statement ->
                        batch.forEachIndexed { i, chunk ->
                            statement.reset()
                            statement.clearBindings()
                            statement.bindLong(1, ids[i])
                            statement.bindText(2, chunk.text)
                            statement.step()
                        }
                    }
                }
            }
            beforeCommit(book.id)
            // A re-download or refresh while the file was being read means this text may be stale: stay pending.
            if (dao.get(book.id) == book) dao.setIndexedSearchable(book.id, now(), searchable)
        }
    }

    /** The highest `book_chunks` id ever handed out. */
    private suspend fun chunkSequence(): Long = db.useReaderConnection { connection ->
        connection.usePrepared("SELECT seq FROM sqlite_sequence WHERE name = 'book_chunks'") { if (it.step()) it.getLong(0) else 0L }
    }

    /** Re-extracts the text of [hits], opening the book's file once; a missing file or chunk gives no entry. */
    private fun chunkTexts(book: Book, hits: List<BookChunk>): Map<Long, String> {
        if (book.format == BookFormat.PDF) {
            val reader = PdfText.open(book.filePath) ?: return emptyMap()
            return reader.use {
                val byHref = hits.map { it.href }.distinct().associateWith { href -> reader.chunks(href) }
                hits.mapNotNull { chunk -> byHref[chunk.href]?.getOrNull(chunk.ordinal)?.let { chunk.id to it.text } }.toMap()
            }
        }
        val zip = try { openBookZip(book.filePath) } catch (_: IOException) { return emptyMap() }
        return zip.use {
            val byHref = hits.map { it.href }.distinct().associateWith { href -> XhtmlText.chunks(zip, href) }
            hits.mapNotNull { chunk -> byHref[chunk.href]?.getOrNull(chunk.ordinal)?.let { chunk.id to it.text } }.toMap()
        }
    }

    companion object {
        /** The most hits [search] returns by default. */
        const val LIMIT = 30
        /** The fewest words [quoted] takes for a quote, the most each may stray from the next, and the most chunks. */
        const val QUOTE_WORDS = 3
        const val NEAR = 2
        const val QUOTE_CHUNKS = 20
        private val WHITESPACE = Regex("\\s+")
        private val YEAR = Regex("^-?\\d{1,4}")

        /** How many chunks go in between switches from the DAO to `book_text`'s statement. */
        private const val TEXT_BATCH = 256
    }
}

/** `HashMap.merge` (JVM only): [value] if [key] has none, else [combine] of the two; returns what's stored. */
private inline fun <K, V : Any> MutableMap<K, V>.mergeWith(key: K, value: V, combine: (V, V) -> V): V {
    val merged = get(key)?.let { combine(it, value) } ?: value
    put(key, merged)
    return merged
}

/**
 * BM25 (k1 = 1.2, no length normalisation) of one row from FTS4 `matchinfo(..., 'pcnx')`: unsigned little-endian ints
 * `p c n`, then `hits-in-row, hits-in-all-rows, rows-with-hits` per phrase and column. Sums the phrases (one column).
 */
fun bm25(matchinfo: ByteArray): Double {
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
        val idf = ln((n - df + 0.5) / (df + 0.5) + 1)
        score += idf * tf * 2.2 / (tf + 1.2)
    }
    return score
}
