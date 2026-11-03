package app.granger.core.library

import app.granger.core.books.EpubInspector
import app.granger.core.books.Inspection
import app.granger.core.books.PdfText
import app.granger.core.books.TextChunk
import app.granger.core.books.XhtmlText
import app.granger.core.research.SourceKind
import app.granger.core.text.Chunkers
import app.granger.core.vectors.ChunkKey
import app.granger.core.vectors.VectorFile
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** What [BookVectors] is doing: idle, or embedding [title] ([done] of [total] chunks), with [left] books after it. */
data class BookVectorsProgress(val title: String? = null, val done: Int = 0, val total: Int = 0, val left: Int = 0)

/**
 * Plan R2 Task 9: vectors for the books you add, made on the phone, so the search by meaning ([DensePassageFinder]) finds
 * them as it finds the catalog's. Each book's chunks are the keyword index's ([BookIndex]): an EPUB's chapters through
 * [XhtmlText], a PDF's pages through [PdfText], so a hit is rebuilt by href and ordinal like any other. They're embedded
 * as `tools/vectors/build.py` embeds a catalog book (`title: <book title> | text: <chunk>`), [batch] at a time through
 * [embed], which takes the model's turn for each batch, so a question asked meanwhile waits for one batch at most. The
 * file, `<dir>/<ref>.gvec` (the import's sha256), is written whole at the end; an interrupted book starts again.
 *
 * [embed] returns null when there's no embedder; [embedderSha] names the installed one, which a file must carry to be
 * used, so a new embedder means new vectors.
 */
class BookVectors(
    private val bookshelf: Bookshelf,
    private val dir: File,
    private val embed: suspend (List<Pair<String, String>>) -> List<FloatArray>?,
    private val embedderSha: suspend () -> String?,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val batch: Int = 8,
    private val dims: Int = DIMS,
    private val origins: Set<BookOrigin> = setOf(BookOrigin.IMPORT),
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val log: (String) -> Unit = {},
) {
    private val running = Mutex()
    private val state = MutableStateFlow(BookVectorsProgress())
    val progress: StateFlow<BookVectorsProgress> = state.asStateFlow()

    /** The books that have vectors made here, as a [VectorSources] part: only those of the installed embedder. */
    val sources: VectorSources = ListedVectorSources({ listed() }, embedderSha)

    /**
     * Embeds every added book that has no current vector file, one at a time; returns how many were written. A call
     * while one is running returns 0 at once: the running one picks up books added since it started.
     */
    suspend fun embedPending(): Int {
        if (!running.tryLock()) return 0
        try {
            var written = 0
            while (true) {
                val sha = embedderSha() ?: return written
                val pending = pending(sha)
                val book = pending.firstOrNull() ?: return written
                state.value = BookVectorsProgress(book.title, 0, 0, pending.size - 1)
                if (!embedBook(book, sha)) return written
                written++
            }
        } finally {
            state.value = BookVectorsProgress()
            running.unlock()
        }
    }

    /** The file [bookId]'s vectors go in, or null for a book that isn't one this class embeds. */
    fun fileFor(bookId: String): File? {
        val origin = origins.firstOrNull { bookId.startsWith(BookIds.of(it, "")) } ?: return null
        val ref = bookId.removePrefix(BookIds.of(origin, ""))
        if (ref.isEmpty() || ref.any { !it.isLetterOrDigit() && it != '-' && it != '_' }) return null
        return File(dir, "${origin.key}-$ref.gvec")
    }

    private suspend fun pending(sha: String): List<Book> = withContext(io) {
        bookshelf.books.first().filter { b ->
            b.status == PackStatus.OK && File(b.filePath).isFile && fileFor(b.id)?.let { f -> !current(f, sha) } == true
        }
    }

    private fun current(file: File, sha: String): Boolean {
        if (!file.isFile) return false
        val vf = VectorFile.open(file) ?: return false
        return vf.use { it.header.embedderSha256 == sha && it.header.chunkerVersion == Chunkers.VERSION }
    }

    private suspend fun listed(): List<ListedVectors> = withContext(io) {
        bookshelf.books.first().mapNotNull { b -> fileFor(b.id)?.takeIf { it.isFile }?.let { ListedVectors(it, SourceKind.BOOK, b.id) } }
    }

    /** False when the embedder went away midway (nothing is written; the book stays pending). */
    private suspend fun embedBook(book: Book, sha: String): Boolean {
        val target = fileFor(book.id) ?: return true
        val started = clock()
        val chunks = withContext(io) { chunks(book) }.filter { it.text.isNotBlank() }
        val vectors = ArrayList<FloatArray>(chunks.size)
        for (part in chunks.chunked(batch)) {
            state.value = state.value.copy(done = vectors.size, total = chunks.size)
            val got = try {
                embed(part.map { book.title to it.text })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Book vectors: ${book.id} failed after ${vectors.size} of ${chunks.size} chunks: $e")
                return false
            } ?: return false
            vectors += got
        }
        withContext(io) {
            dir.mkdirs()
            if (chunks.isEmpty()) {
                // A book with no text (a scan): an empty file marks it done, so it isn't tried again.
                VectorFile.write(target, emptyList(), dims, emptyList(), emptyList(), Chunkers.VERSION, sha, sourceSha(book))
            } else {
                VectorFile.write(
                    target, vectors, dims, chunks.map { Chunkers.hash(it.text) }, chunks.map { ChunkKey(it.href, it.ordinal) },
                    Chunkers.VERSION, sha, sourceSha(book),
                )
            }
        }
        log("Book vectors: ${book.id} (${book.title}), ${chunks.size} chunks in ${clock() - started} ms")
        return true
    }

    private fun chunks(book: Book): List<TextChunk> {
        val file = File(book.filePath)
        return try {
            if (book.format == BookFormat.PDF) {
                PdfText.open(file)?.use { r -> if (!r.hasTextLayer()) emptyList() else (1..r.pageCount).flatMap { r.chunks(PdfText.href(it)) } }.orEmpty()
            } else {
                val spine = (EpubInspector.inspect(file) as? Inspection.Ok)?.meta?.spine ?: return emptyList()
                XhtmlText.all(file, spine).toList()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** An import's id ends in its file's sha256; other books get zeros (the header only needs 64 hex digits). */
    private fun sourceSha(book: Book): String = book.id.substringAfterLast(':').takeIf { it.length == 64 && it.all { c -> c in HEX } } ?: "0".repeat(64)

    companion object {
        /** The catalog's vectors' width (`tools/vectors/build.py --dims`), which the search scans together. */
        const val DIMS = 256
        private const val HEX = "0123456789abcdef"
    }
}
