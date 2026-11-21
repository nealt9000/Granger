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

/**
 * iOS can move the app's container between installs and updates (`…/Data/Application/<UUID>/` changes), so a book's
 * stored absolute path can point into a folder that no longer exists (Plan I5 Task 6). A stored path that's gone is
 * looked for again under the folder that holds it now: the same part after that folder's last two names
 * (`Documents/Books/imports/x.epub` under the current Documents/Books). Android keeps its paths as they are.
 */
object MovedContainer {
    /** [stored] under [currentDir], when [stored] is gone and the same file is there; else null. */
    fun rebase(stored: String, currentDir: String, exists: (String) -> Boolean = ::isFile): String? {
        if (stored.isBlank() || exists(stored)) return null
        val dir = currentDir.trimEnd('/')
        val tail = dir.split('/').filter { it.isNotEmpty() }.takeLast(2).joinToString("/")
        if (tail.isEmpty()) return null
        val at = stored.lastIndexOf("/$tail/")
        if (at < 0) return null
        val candidate = dir + stored.substring(at + tail.length + 1)
        return candidate.takeIf { it != stored && exists(it) }
    }

    /** [book] with its file and cover found again under [booksDir] and [coversDir]; null when nothing moved. */
    fun rebase(book: app.granger.core.library.Book, booksDir: String, coversDir: String, exists: (String) -> Boolean = ::isFile): app.granger.core.library.Book? {
        val file = rebase(book.filePath, booksDir, exists)
        val cover = book.coverPath?.let { rebase(it, coversDir, exists) }
        if (file == null && cover == null) return null
        return book.copy(filePath = file ?: book.filePath, coverPath = cover ?: book.coverPath)
    }

    fun isFile(path: String): Boolean = runCatching { SystemFileSystem.metadataOrNull(Path(path))?.isRegularFile == true }.getOrDefault(false)
}

/**
 * The music and audiobook rows the shelves file (Plan I5 Task 6): read from the library database; the iPhone has no
 * music library to scan until Plan I6, so [sync] and [forget] do nothing yet.
 */
class IosMediaLibrary(db: app.granger.core.library.LibraryDatabase) : app.granger.core.library.MediaLibrary {
    private val dao = db.mediaDao()
    override val tracks = dao.observeMusic()
    override val audiobooks = dao.observeAudiobooks()
    override fun chapters(audiobookId: String) = dao.observeChapters(audiobookId)
    override val allChapters = dao.observeAllChapters()
    override val albumNotes = dao.observeAlbumNotes()
    override suspend fun track(id: String) = dao.track(id)
    override suspend fun audiobook(id: String) = dao.audiobook(id)
    override suspend fun coverPaths(): Set<String> = dao.coverPaths().toSet()
    override suspend fun sync(origin: app.granger.core.library.MediaOrigin, result: app.granger.core.library.ScanResult, keep: (uri: String) -> Boolean) = Unit
    override suspend fun forget(trackIds: Collection<String>, audiobookIds: Collection<String>) = Unit
}

/**
 * A file picked in the Files app (Plan I5 Task 6), for the shared [app.granger.library.FileImports]: read from its
 * [path] (the picker's copy) under the [name] it had. A comic book archive (CBZ, CBR) is turned into a PDF of its
 * pictures first, in [scratch], as Android turns an archive.org comic into one; the shelf then imports that PDF. A
 * comic that can't be read imports as nothing, with the damaged-file words.
 */
fun pickedBook(path: String, name: String, scratch: String): app.granger.library.PickedFile =
    app.granger.library.PickedFile(name = { name }, fallbackName = path.substringAfterLast('/')) {
        if (!app.granger.core.books.ComicToPdf.isComicName(name)) return@PickedFile SystemFileSystem.source(Path(path)).buffered()
        SystemFileSystem.createDirectories(Path(scratch))
        val pdf = "${scratch.trimEnd('/')}/${platform.Foundation.NSUUID().UUIDString}.pdf"
        if (!app.granger.core.books.ComicToPdf.convert(path, pdf)) return@PickedFile null
        // Opened, then unlinked: the open file reads on (as on any Unix) and nothing is left in scratch.
        val source = SystemFileSystem.source(Path(pdf)).buffered()
        runCatching { SystemFileSystem.delete(Path(pdf), mustExist = false) }
        source
    }
