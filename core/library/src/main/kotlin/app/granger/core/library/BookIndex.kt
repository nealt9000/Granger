package app.granger.core.library

import android.database.SQLException
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import app.granger.core.books.EpubInspector
import app.granger.core.books.Inspection
import app.granger.core.books.Snippets
import app.granger.core.books.XhtmlText
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
)

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

    /** Indexes every OK book not yet indexed. A book that fails stays pending for the next call. */
    suspend fun indexPending() = mutex.withLock {
        withContext(io) {
            indexAll()
            compact()
        }
    }

    suspend fun search(query: String, limit: Int = 30, perBook: Int = 3): List<BookHit> {
        val words = query.split(WHITESPACE).map { it.replace("\"", "") }.filter { word -> word.any { it.isLetterOrDigit() } }
        if (words.isEmpty()) return emptyList()
        val match = words.mapIndexed { i, w -> if (i == words.lastIndex) "\"$w*\"" else "\"$w\"" }.joinToString(" ")
        return withContext(io) {
            val chunks = try {
                dao.chunksMatching(
                    SimpleSQLiteQuery(
                        "SELECT c.* FROM book_text t JOIN book_chunks c ON c.id = t.docid WHERE book_text MATCH ? LIMIT 400",
                        arrayOf(match),
                    ),
                )
            } catch (_: SQLException) {
                emptyList()
            }
            val kept = chunks.groupBy { it.bookId }.values
                .sortedByDescending { it.size }
                .flatMap { it.take(perBook) }
                .take(limit)
            val snippetQuery = words.joinToString(" ")
            kept.groupBy { it.bookId }.flatMap { (bookId, hits) ->
                val book = dao.get(bookId) ?: return@flatMap emptyList()
                val texts = chunkTexts(book.filePath, hits)
                hits.map { chunk ->
                    BookHit(
                        bookId = book.id,
                        bookTitle = book.title,
                        author = book.author,
                        href = chunk.href,
                        progression = chunk.progression,
                        snippet = texts[chunk.id]?.let { Snippets.around(it, snippetQuery) }.orEmpty(),
                    )
                }
            }
        }
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
            try {
                index(book)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Left pending; the next call tries again.
            }
        }
    }

    /** All or nothing: the book's chunks and text go in, and it's marked indexed, in one transaction. */
    private suspend fun index(book: Book) {
        val file = File(book.filePath)
        if (!file.isFile) return
        val spine = (EpubInspector.inspect(file) as? Inspection.Ok)?.meta?.spine ?: return
        db.withTransaction {
            dao.deleteChunks(book.id)
            val insert = db.openHelper.writableDatabase.compileStatement("INSERT INTO book_text(docid, text) VALUES(?, ?)")
            insert.use { statement ->
                for (chunk in XhtmlText.all(file, spine)) {
                    val id = dao.insertChunk(BookChunk(0, book.id, chunk.href, chunk.ordinal, chunk.progression))
                    statement.clearBindings()
                    statement.bindLong(1, id)
                    statement.bindString(2, chunk.text)
                    statement.executeInsert()
                }
            }
            beforeCommit(book.id)
            dao.setIndexed(book.id, now())
        }
    }

    /** The highest `book_chunks` id ever handed out. */
    private fun chunkSequence(): Long =
        db.query("SELECT seq FROM sqlite_sequence WHERE name = 'book_chunks'", null).use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        }

    /** Re-extracts the text of [hits], opening the book's zip once; a missing file or chunk gives no entry. */
    private fun chunkTexts(path: String, hits: List<BookChunk>): Map<Long, String> {
        val zip = try { ZipFile(path) } catch (_: IOException) { return emptyMap() }
        return zip.use {
            val byHref = hits.map { it.href }.distinct().associateWith { href -> XhtmlText.chunks(zip, href) }
            hits.mapNotNull { chunk -> byHref[chunk.href]?.getOrNull(chunk.ordinal)?.let { chunk.id to it.text } }.toMap()
        }
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
