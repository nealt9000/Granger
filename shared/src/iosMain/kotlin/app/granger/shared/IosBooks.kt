package app.granger.shared

import app.granger.core.library.Book
import app.granger.core.library.BookOrigin
import app.granger.core.library.Bookshelf
import app.granger.core.library.NautilusDatabase
import app.granger.core.text.hex
import app.granger.core.text.sha1
import app.granger.core.zim.ZimArchive
import app.granger.ebook.BookSearcher
import app.granger.ebook.BookTextHit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.coroutines.resume

/**
 * Search in a book through Readium Swift's search service (Plan I5 Task 5). Swift's search is async; it answers
 * through [done], once, from any thread. A search dropped by the shared model (a new query) is dropped here too.
 */
class IosBookSearcher(
    private val find: (query: String, max: Int, done: (List<BookTextHit>) -> Unit) -> Unit,
) : BookSearcher {
    override suspend fun search(query: String, max: Int): List<BookTextHit> = suspendCancellableCoroutine { c ->
        find(query, max) { hits -> if (c.isActive) c.resume(hits) }
    }
}

/**
 * A pack archive that can write an entry to a file without holding it whole in memory (CoreKiwix reads a blob in
 * chunks; Swift's KiwixArchive is one). The book copies (pack documents, book packs' books) use it when they can.
 */
interface StreamingCopy {
    /** Writes [path]'s content (after redirects) to the file [to]; false when there's no such entry or it failed. */
    fun writeEntry(path: String, to: String): Boolean
}

/** [path] of [archive] written to [to]: streamed when the archive can, else read whole and written (Android's rule). */
fun copyOutOfPack(archive: ZimArchive, path: String, to: String): Boolean {
    if (archive is StreamingCopy) return archive.writeEntry(path, to)
    return try {
        val item = archive.read(path) ?: return false
        SystemFileSystem.sink(Path(to)).buffered().use { it.write(item.bytes) }
        true
    } catch (_: Exception) {
        runCatching { SystemFileSystem.delete(Path(to), mustExist = false) }
        false
    }
}

/** Why a pack's PDF couldn't be opened (Android's `reader_pdf_*` words; Swift says them). */
enum class PackDocumentProblem { UNREADABLE_PACK, NOT_IN_PACK, NO_SPACE, COPY_FAILED, DAMAGED }

/** A pack's PDF opened as a pack document: its book, or why not. */
sealed interface PackDocumentOpened {
    data class Ready(val book: Book) : PackDocumentOpened
    data class Failed(val problem: PackDocumentProblem) : PackDocumentOpened
}

/**
 * PDFs inside Kiwix packs on the iPhone (Android's PackDocuments, Plan P2): a tapped PDF is copied out of its pack,
 * streamed, to `<dir>/<packId>/<sha1 of path>.pdf` and kept as a pack document, a hidden book, so the e-book reader
 * opens it and it keeps its place. Removing the pack removes it ([Bookshelf.removePackDocuments]).
 */
class IosPackDocuments(
    private val bookshelf: Bookshelf,
    private val archiveOf: (packId: String) -> ZimArchive?,
    /** `Documents/Books/packs`. */
    private val dir: () -> String,
    private val io: CoroutineDispatcher,
    private val freeBytes: (String) -> Long,
) {
    private val listed = HashMap<String, Map<String, String>>()

    /** The pack document for [path] in [packId], copied out first unless it already is; [linkText] may name it. */
    suspend fun open(packId: String, path: String, linkText: String = ""): PackDocumentOpened = withContext(io) {
        val archive = archiveOf(packId) ?: return@withContext PackDocumentOpened.Failed(PackDocumentProblem.UNREADABLE_PACK)
        val entry = archive.entry(path) ?: return@withContext PackDocumentOpened.Failed(PackDocumentProblem.NOT_IN_PACK)
        val folder = "${dir().trimEnd('/')}/${packId.replace(UNSAFE, "-")}"
        val file = "$folder/${sha1(entry.path.encodeToByteArray()).hex()}.pdf"
        val there = SystemFileSystem.metadataOrNull(Path(file))
        if (!(there?.isRegularFile == true && there.size == entry.sizeBytes)) {
            SystemFileSystem.createDirectories(Path(folder))
            if (freeBytes(folder) < entry.sizeBytes) return@withContext PackDocumentOpened.Failed(PackDocumentProblem.NO_SPACE)
            val partial = "$folder/.incoming-${file.substringAfterLast('/')}"
            val copied = copyOutOfPack(archive, entry.path, partial) &&
                runCatching { SystemFileSystem.atomicMove(Path(partial), Path(file)) }.isSuccess
            if (!copied) {
                runCatching { SystemFileSystem.delete(Path(partial), mustExist = false) }
                return@withContext PackDocumentOpened.Failed(PackDocumentProblem.COPY_FAILED)
            }
        }
        val listedTitle = listedTitles(packId, archive)[entry.path.removePrefix(NautilusDatabase.FILES)].orEmpty()
        val book = bookshelf.registerPackDocument(packId, entry.path, entry.title, file, linkText, listedTitle)
            ?: return@withContext PackDocumentOpened.Failed(PackDocumentProblem.DAMAGED)
        PackDocumentOpened.Ready(book)
    }

    /** Copies a pack document's file out again when it's gone; false if its pack can't give it. */
    suspend fun restore(book: Book): Boolean {
        if (book.origin != BookOrigin.PACK) return false
        val (packId, path) = bookshelf.packDocumentSource(book) ?: return false
        return open(packId, path, book.title) is PackDocumentOpened.Ready
    }

    private fun listedTitles(packId: String, archive: ZimArchive): Map<String, String> = listed.getOrPut(packId) {
        runCatching { archive.read(NautilusDatabase.PATH)?.let { NautilusDatabase.parse(it.bytes.decodeToString()) } }.getOrNull().orEmpty()
    }

    private companion object {
        val UNSAFE = Regex("[^A-Za-z0-9._-]+")
    }
}

/**
 * The reading preferences in UserDefaults under `reading.` (Android's "reading" SharedPreferences file). Every value is
 * kept as its text: the shared store reads switches and the zoom back from text as well.
 */
fun defaultsReadingPrefs(): app.granger.ebook.ReadingPrefsStore {
    val d = platform.Foundation.NSUserDefaults.standardUserDefaults
    return app.granger.ebook.ReadingPrefsStore(
        read = { key -> d.stringForKey("reading.$key") },
        write = { values -> for ((key, value) in values) d.setObject(value.toString(), "reading.$key") },
    )
}

/** What PDFKit made of a file: the open [file], or that it needs a password, or why it can't be read. */
class PdfOpening(val file: app.granger.core.books.PdfFile?, val needsPassword: Boolean, val reason: String?)

/**
 * The shared PDF rules' opener over PDFKit (Plan I5 call 6): Swift opens the file and says what happened; this throws
 * as the shared rules expect ([app.granger.core.books.PdfPasswordException], else an IOException). Set it with
 * `BookEngines.pdf = IosPdfOpener { … }` at launch.
 */
class IosPdfOpener(private val opening: (path: String) -> PdfOpening) : app.granger.core.books.PdfOpener {
    override fun open(path: String): app.granger.core.books.PdfFile {
        val o = opening(path)
        o.file?.let { return it }
        if (o.needsPassword) throw app.granger.core.books.PdfPasswordException(o.reason ?: "needs a password")
        throw kotlinx.io.IOException(o.reason ?: "can't read $path")
    }
}

/** Sets the iPhone's PDF engine for the shared book rules (import, covers, outlines). */
fun usePdfEngine(opener: app.granger.core.books.PdfOpener) {
    app.granger.core.books.BookEngines.pdf = opener
}
