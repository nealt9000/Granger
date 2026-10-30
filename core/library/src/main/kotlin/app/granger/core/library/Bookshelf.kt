package app.granger.core.library

import androidx.room.withTransaction
import app.granger.core.books.BookMeta
import app.granger.core.books.ComicToPdf
import app.granger.core.books.DrmKind
import app.granger.core.books.EpubInspector
import app.granger.core.books.Inspection
import app.granger.core.books.PdfInspector
import app.granger.core.books.TextDecoding
import app.granger.core.books.TextToEpub
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** What happened to a file the user chose to import. */
sealed interface ImportResult {
    data class Imported(val bookId: String) : ImportResult
    data class AlreadyThere(val bookId: String) : ImportResult
    data class Protected(val kind: DrmKind) : ImportResult
    data object Unsupported : ImportResult
    data class Damaged(val reason: String) : ImportResult

    /** The message to show the user about the file called [name]; null when nothing went wrong. */
    fun message(name: String): String? = when (this) {
        is Imported, is AlreadyThere -> null
        is Protected -> if (kind == DrmKind.PDF_ENCRYPTED) {
            "$name is password-protected or encrypted. Granger can't open it and won't remove the protection."
        } else {
            "$name is copy-protected (DRM). Granger can't open it and won't remove the protection."
        }
        Unsupported -> "$name isn't an EPUB, PDF, TXT or Markdown file."
        is Damaged -> "$name is damaged or isn't a complete e-book."
    }
}

/** What a source knows about a book; its non-blank fields win over the EPUB's own metadata. */
data class BookDetails(
    val title: String,
    val author: String,
    val date: String,
    val subjects: List<String>,
    val description: String,
    val language: String,
)

/** The e-books on the device, where each reader left off, and its bookmarks and highlights. */
interface Bookshelf {
    val books: Flow<List<Book>>
    val positions: Flow<Map<String, BookPosition>>
    suspend fun book(id: String): Book?

    /** Re-checks every book's file and registers EPUBs and PDFs found in the books folders that have no row. */
    suspend fun refresh()

    /**
     * Registers a finished download of [origin] with source reference [ref]; null if the file isn't a readable EPUB
     * or PDF.
     * A `.txt` file is converted to an EPUB beside it (null if it is over the size limit), and a comic (`.cbz`,
     * `.cbr`) to a PDF of its pages. Non-blank [details] fields win over the book's own metadata.
     */
    suspend fun addDownloaded(file: File, origin: BookOrigin, ref: String, details: BookDetails? = null): Book?

    /**
     * Copies a user's file into the library, converting TXT and Markdown to EPUB. A file whose book is already here
     * and readable is [ImportResult.AlreadyThere]; one whose book is missing or damaged is imported again. Failing to
     * read [open] gives [ImportResult.Damaged]; other exceptions propagate. No partial file is left behind either way.
     */
    suspend fun import(displayName: String, open: () -> InputStream): ImportResult

    /**
     * Deletes the file, cover and search text; the position and marks are kept. A pack book's row stays while its
     * pack lists it, to be copied out again when opened. False if the file can't be deleted.
     */
    suspend fun remove(id: String): Boolean

    /**
     * Registers [file], a PDF copied out of pack [zimId] at [path], as a pack document: a hidden book
     * ([BookOrigin.PACK]) so it keeps its place and shows on the rail. It is called by its [listedTitle] in the pack's
     * own list of documents ([NautilusDatabase]), the entry's [title], the PDF's own title, the [linkText] tapped in the
     * article or its file name, whichever first reads as a title ([PackDocumentTitle]). Registering it again renames
     * it. Null if the file isn't a readable PDF.
     */
    suspend fun registerPackDocument(
        zimId: String, path: String, title: String, file: File, linkText: String = "", listedTitle: String = "",
    ): Book?

    /** Removes every pack document of [zimId], with its file and cover (the pack itself is going). */
    suspend fun removePackDocuments(zimId: String)

    suspend fun savePosition(bookId: String, locatorJson: String, totalProgression: Double, chapterTitle: String)
    suspend fun position(bookId: String): BookPosition?
    fun marks(bookId: String): Flow<List<BookAnnotation>>
    suspend fun addMark(mark: BookAnnotation): Long
    suspend fun updateMark(mark: BookAnnotation)
    suspend fun deleteMark(id: Long)
}

/**
 * @param bookDirs each volume's `books/` folder; downloads live in `granger/` and imports in `imports/` under it.
 * @param importDir where imports are copied (the internal volume's `books/imports`).
 * @param maxTextBytes the largest TXT or Markdown file imported; conversion holds the whole text in memory.
 */
class RoomBookshelf(
    private val db: LibraryDatabase,
    private val bookDirs: () -> List<File>,
    private val importDir: () -> File,
    private val coversDir: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxTextBytes: Long = 20L * 1024 * 1024,
    private val pdfCovers: PdfCovers = PlatformPdfCovers,
) : Bookshelf {
    private val dao = db.bookDao()

    override val books: Flow<List<Book>> = dao.observeAll().ignoringIndexedAt()

    override val positions: Flow<Map<String, BookPosition>> =
        dao.observePositions().map { list -> list.associateBy { it.bookId } }

    override suspend fun book(id: String): Book? = dao.get(id)

    override suspend fun addDownloaded(file: File, origin: BookOrigin, ref: String, details: BookDetails?): Book? =
        withContext(io) {
            val pdf = if (ComicToPdf.isComicName(file.name)) convertComic(file) ?: return@withContext null else file
            if (isPdfName(pdf.name)) {
                val meta = (PdfInspector.inspect(pdf) as? Inspection.Ok)?.meta ?: return@withContext null
                return@withContext register(BookIds.of(origin, ref), origin, ref, pdf, meta, details, BookFormat.PDF)
            }
            val epub = if (file.extension.equals("txt", ignoreCase = true)) {
                convertText(file, details?.title?.takeIf { it.isNotBlank() }) ?: return@withContext null
            } else {
                file
            }
            val meta = (EpubInspector.inspect(epub) as? Inspection.Ok)?.meta
            if (meta == null) {
                if (epub != file) epub.delete()
                return@withContext null
            }
            register(BookIds.of(origin, ref), origin, ref, epub, meta, details)
        }

    /**
     * Converts the text file [txt] to `<name>.epub` beside it (via a `.incoming-` partial); null if it is too large
     * or can't be read or written. Leaves the text file in place.
     */
    private fun convertText(txt: File, title: String?): File? {
        val target = File(txt.parentFile, txt.nameWithoutExtension + ".epub")
        val partial = File(txt.parentFile, ".incoming-${UUID.randomUUID()}.epub")
        return try {
            if (txt.length() > maxTextBytes) return null
            val text = TextDecoding.decode(txt.readBytes())
            partial.outputStream().use { TextToEpub.convert(text, title ?: txt.nameWithoutExtension, false, it) }
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            target
        } catch (_: IOException) {
            null
        } finally {
            partial.delete()
        }
    }

    /** Converts the comic [archive] to `<name>.pdf` beside it; null if it can't be read. Leaves the archive in place. */
    private fun convertComic(archive: File): File? {
        val target = File(archive.parentFile, archive.nameWithoutExtension + ".pdf")
        return target.takeIf { ComicToPdf.convert(archive, it) }
    }

    override suspend fun import(displayName: String, open: () -> InputStream): ImportResult = withContext(io) {
        val dir = importDir().apply { mkdirs() }
        val incoming = File(dir, ".incoming-${UUID.randomUUID()}")
        val converted = File(dir, "${incoming.name}.epub")
        try {
            val sha = try {
                open().use { input -> incoming.outputStream().use { out -> sha256(input, out) } }
            } catch (_: IOException) {
                return@withContext ImportResult.Damaged("Couldn't read the file")
            }
            val id = BookIds.of(BookOrigin.IMPORT, sha)
            val existing = dao.get(id)
            if (existing != null && existing.status == PackStatus.OK && File(existing.filePath).exists()) {
                return@withContext ImportResult.AlreadyThere(id)
            }
            if (incoming.length() == 0L) return@withContext ImportResult.Damaged("Empty file")

            val title = titleOf(displayName)
            val textName = isTextName(displayName)
            val head = incoming.inputStream().use { input ->
                ByteArray(SNIFF_BYTES).let { buf -> buf.copyOf(input.read(buf).coerceAtLeast(0)) }
            }
            if (head.startsWith(PDF_MAGIC)) {
                val meta = when (val inspection = PdfInspector.inspect(incoming, title)) {
                    is Inspection.Ok -> inspection.meta
                    is Inspection.Protected -> return@withContext ImportResult.Protected(inspection.kind)
                    is Inspection.Invalid -> return@withContext ImportResult.Damaged(inspection.reason)
                }
                val target = File(dir, "${sha.take(12)}-${sanitise(meta.title)}.pdf")
                return@withContext place(incoming, target, id, sha, meta, BookFormat.PDF, existing)
            }
            val zip = head.startsWith(ZIP_MAGIC)
            // Not a zip: only a text name, or no extension at all, is worth reading as text (a .epub, .pdf, .html isn't).
            if (!zip && !textName && hasExtension(displayName)) return@withContext ImportResult.Unsupported

            var epub: File? = null
            var meta: BookMeta? = null
            if (zip) {
                when (val inspection = EpubInspector.inspect(incoming, title)) {
                    is Inspection.Ok -> { epub = incoming; meta = inspection.meta }
                    is Inspection.Protected -> return@withContext ImportResult.Protected(inspection.kind)
                    // A text file can happen to start with "PK"; only its name says so.
                    is Inspection.Invalid -> if (!textName) return@withContext ImportResult.Damaged(inspection.reason)
                }
            }
            if (epub == null || meta == null) {
                if (!zip && !textName && head.any { it == 0.toByte() }) return@withContext ImportResult.Unsupported
                if (incoming.length() > maxTextBytes) return@withContext ImportResult.Damaged("Too large")
                val markdown = displayName.lowercase().let { it.endsWith(".md") || it.endsWith(".markdown") }
                val text = TextDecoding.decode(incoming.readBytes())
                converted.outputStream().use { TextToEpub.convert(text, title, markdown, it) }
                epub = converted
                meta = when (val inspection = EpubInspector.inspect(converted, title)) {
                    is Inspection.Ok -> inspection.meta
                    is Inspection.Protected -> return@withContext ImportResult.Protected(inspection.kind)
                    is Inspection.Invalid -> return@withContext ImportResult.Damaged(inspection.reason)
                }
            }

            place(epub, File(dir, "${sha.take(12)}-${sanitise(title)}.epub"), id, sha, meta, BookFormat.EPUB, existing)
        } finally {
            incoming.delete()
            converted.delete()
        }
    }

    /** Moves an inspected import to [target] and registers it. */
    private suspend fun place(
        file: File, target: File, id: String, sha: String, meta: BookMeta, format: BookFormat, existing: Book?,
    ): ImportResult {
        Files.move(file.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        try {
            register(id, BookOrigin.IMPORT, sha, target, meta, format = format)
        } catch (e: Throwable) {
            target.delete()
            throw e
        }
        // A re-import replaces a damaged or misplaced copy; don't leave the old one as a stray.
        if (existing != null && existing.filePath != target.absolutePath) File(existing.filePath).delete()
        return ImportResult.Imported(id)
    }

    override suspend fun refresh(): Unit = withContext(io) {
        forgetRemovedPackDocuments()
        val known = dao.getAll()
        for (book in known) {
            val status = when {
                // A pack book is copied out of its pack only when opened; its pack decides ([PackBooks.sync]).
                book.origin.isPackBook && !File(book.filePath).exists() -> book.status
                !File(book.filePath).exists() -> PackStatus.MISSING
                // A damaged file stays damaged until it inspects cleanly; a returning file is otherwise OK.
                book.status == PackStatus.CORRUPT && inspect(File(book.filePath)) !is Inspection.Ok ->
                    PackStatus.CORRUPT
                else -> PackStatus.OK
            }
            if (status != book.status) dao.setStatus(book.id, status)
        }

        val knownPaths = known.map { File(it.filePath).absolutePath }.toSet()
        val dirs = bookDirs()
        val grangerFiles = dirs.flatMap { strays(File(it, "granger"), knownPaths) }
        val importFiles = (dirs.map { File(it, "imports") } + importDir())
            .distinctBy { it.absolutePath }
            .flatMap { strays(it, knownPaths) }
        val archiveDirs = dirs.map { File(it, "archive-org") }
        // A verified text or comic download whose conversion never happened (the process died) is converted now.
        for (dir in archiveDirs) {
            for (txt in dir.listFiles().orEmpty()) {
                if (!txt.isFile || txt.name.startsWith(".incoming-")) continue
                val comic = ComicToPdf.isComicName(txt.name)
                if (!comic && !txt.extension.equals("txt", ignoreCase = true)) continue
                if (File(dir, txt.nameWithoutExtension + if (comic) ".pdf" else ".epub").exists()) continue
                try {
                    if (comic) convertComic(txt) else convertText(txt, null)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("Granger", "Converting ${txt.name} failed", e)
                }
            }
        }
        val archiveFiles = archiveDirs.flatMap { strays(it, knownPaths) }
        val candidates =
            grangerFiles.map { Triple(BookOrigin.GRANGER, it.nameWithoutExtension.substringBeforeLast('_'), it) } +
                archiveFiles.map { Triple(BookOrigin.ARCHIVE_ORG, it.nameWithoutExtension, it) } +
                importFiles.map { file -> Triple(BookOrigin.IMPORT, file.inputStream().use { sha256(it) }, file) }

        for ((key, group) in candidates.groupBy { (origin, ref, _) -> origin to ref }) {
            val (origin, ref) = key
            val id = BookIds.of(origin, ref)
            val existing = dao.get(id)
            // Older snapshots of a download can be left behind: keep a row whose file is still there, else take the newest.
            if (existing != null && File(existing.filePath).exists()) continue
            val file = group.maxBy { it.third.name }.third
            when (val inspection = inspect(file)) {
                is Inspection.Ok -> register(id, origin, ref, file, inspection.meta, format = formatOf(file))
                else -> dao.upsert(
                    (existing ?: unreadable(id, origin, ref, file)).copy(
                        filePath = file.absolutePath, sizeBytes = file.length(), status = PackStatus.CORRUPT,
                    ),
                )
            }
        }
    }

    override suspend fun remove(id: String): Boolean = withContext(io) {
        val book = dao.get(id) ?: return@withContext true
        val file = File(book.filePath)
        if (file.exists() && !file.delete()) return@withContext false
        // A converted text or comic book's source stays beside it; left alone, the next refresh() would convert it again.
        if (book.origin == BookOrigin.ARCHIVE_ORG) {
            val sources = if (book.format == BookFormat.EPUB) listOf("txt") else listOf("cbz", "cbr")
            for (extension in sources) {
                val source = File(file.parentFile, file.nameWithoutExtension + ".$extension")
                if (source.exists() && !source.delete()) return@withContext false
            }
        }
        book.coverPath?.let { File(it).delete() }
        db.withTransaction {
            dao.deleteChunks(id)
            // A pack book stays listed while its pack is installed: only the copy and its search text go.
            if (book.origin.isPackBook) dao.upsert(book.copy(coverPath = null, indexedAt = null)) else dao.delete(id)
        }
        true
    }

    override suspend fun registerPackDocument(
        zimId: String, path: String, title: String, file: File, linkText: String, listedTitle: String,
    ): Book? =
        withContext(io) {
            // zimit packs title a PDF entry with its path, and a PDF's own title is often its maker's junk
            // ("file://C:\WINDOWS\Desktop\…"): the pack's own list of documents first, then them, the link's words
            // and the file name, whichever reads as one.
            val meta = (PdfInspector.inspect(file, "") as? Inspection.Ok)?.meta ?: return@withContext null
            val ref = "$zimId/$path"
            val details = BookDetails(PackDocumentTitle.choose(title, meta.title, linkText, path, listedTitle), "", "", emptyList(), "", "")
            register(BookIds.of(BookOrigin.PACK, ref), BookOrigin.PACK, ref, file, meta, details, BookFormat.PDF)
        }

    /**
     * A pack document can't come back without its pack, so unlike a removed book it leaves nothing behind: no tab on
     * the rail or card in Continue saying "Removed book", no History row, place, bookmarks or index text.
     */
    override suspend fun removePackDocuments(zimId: String): Unit = withContext(io) {
        val prefix = "$zimId/"
        val sessions = db.sessionDao()
        for (book in dao.getAll()) {
            if (book.origin != BookOrigin.PACK || !book.sourceRef.startsWith(prefix)) continue
            File(book.filePath).delete()
            book.coverPath?.let { File(it).delete() }
            db.withTransaction {
                sessions.deleteTabsForPack(book.id)
                sessions.deleteHistoryForPack(book.id)
                dao.deletePosition(book.id)
                dao.deleteMarks(book.id)
                dao.deleteChunks(book.id)
                dao.delete(book.id)
            }
        }
    }

    /**
     * What [removePackDocuments] leaves when it didn't run to the end (or ran before it cleared tabs and places): rows
     * for pack documents whose book is gone.
     */
    private suspend fun forgetRemovedPackDocuments() {
        val prefix = BookIds.of(BookOrigin.PACK, "")
        val sessions = db.sessionDao()
        db.withTransaction {
            sessions.deleteOrphanTabs(prefix)
            sessions.deleteOrphanHistory(prefix)
            dao.deleteOrphanPositions(prefix)
            dao.deleteOrphanMarks(prefix)
        }
    }

    override suspend fun savePosition(bookId: String, locatorJson: String, totalProgression: Double, chapterTitle: String) {
        dao.upsertPosition(BookPosition(bookId, locatorJson, totalProgression, chapterTitle, now()))
    }

    override suspend fun position(bookId: String): BookPosition? = dao.position(bookId)

    override fun marks(bookId: String): Flow<List<BookAnnotation>> = dao.observeMarks(bookId)

    override suspend fun addMark(mark: BookAnnotation): Long = dao.insertMark(mark)

    override suspend fun updateMark(mark: BookAnnotation) = dao.updateMark(mark)

    override suspend fun deleteMark(id: Long) = dao.deleteMarkById(id)

    /** Upserts the book with fresh metadata and cover; an existing row keeps its id and `addedAt`, and is re-indexed. */
    private suspend fun register(
        id: String, origin: BookOrigin, ref: String, file: File, meta: BookMeta, details: BookDetails? = null,
        format: BookFormat = BookFormat.EPUB,
    ): Book {
        val existing = dao.get(id)
        val book = Book(
            id = id,
            origin = origin,
            sourceRef = ref,
            filePath = file.absolutePath,
            title = details?.title.orElse(meta.title),
            author = details?.author.orElse(meta.authors.joinToString(", ")),
            translator = meta.translators.joinToString(", "),
            date = details?.date.orElse(meta.date),
            language = details?.language.orElse(meta.language),
            subjects = details?.subjects?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }?.joinToString("; ")
                ?: meta.subjects.joinToString("; "),
            description = details?.description.orElse(meta.description),
            coverPath = writeCover(id, if (format == BookFormat.PDF) pdfCover(file) else meta.cover,
                if (format == BookFormat.PDF) "png" else meta.coverExtension, existing?.coverPath),
            sizeBytes = file.length(),
            status = PackStatus.OK,
            indexedAt = null,
            addedAt = existing?.addedAt ?: now(),
            format = format,
        )
        dao.upsert(book)
        return book
    }

    private fun pdfCover(file: File): ByteArray? = pdfCovers.firstPage(file, COVER_HEIGHT_PX)

    /** The inspector for [file]'s format, by its name. */
    private fun inspect(file: File): Inspection =
        if (isPdfName(file.name)) PdfInspector.inspect(file) else EpubInspector.inspect(file)

    private fun formatOf(file: File) = if (isPdfName(file.name)) BookFormat.PDF else BookFormat.EPUB

    /** Writes the cover's raw bytes to `coversDir/<sha1 of id>.<ext>` and removes any older cover; null if none. */
    private fun writeCover(id: String, bytes: ByteArray?, coverExtension: String?, oldPath: String?): String? {
        val extension = coverExtension?.lowercase()?.takeIf { SAFE_EXTENSION.matches(it) } ?: "img"
        val target = bytes?.let { File(coversDir, "${sha1(id)}.$extension") }
        if (oldPath != null && oldPath != target?.absolutePath) File(oldPath).delete()
        if (target == null) return null
        return try {
            coversDir.mkdirs()
            target.writeBytes(bytes)
            target.absolutePath
        } catch (_: IOException) {
            target.delete()
            null
        }
    }

    private fun unreadable(id: String, origin: BookOrigin, ref: String, file: File) = Book(
        id = id, origin = origin, sourceRef = ref, filePath = file.absolutePath, title = file.nameWithoutExtension,
        author = "", translator = "", date = "", language = "", subjects = "", description = "", coverPath = null,
        sizeBytes = file.length(), status = PackStatus.CORRUPT, indexedAt = null, addedAt = now(),
    )

    /** `.epub` and `.pdf` files in [dir] that no row points at; partial `.incoming-*` files are skipped. */
    private fun strays(dir: File, knownPaths: Set<String>): List<File> =
        dir.listFiles().orEmpty().filter {
            it.isFile && (it.name.endsWith(".epub", ignoreCase = true) || isPdfName(it.name)) &&
                !it.name.startsWith(".incoming-") &&
                it.absolutePath !in knownPaths
        }

    private companion object {
        const val SNIFF_BYTES = 4096
        val TEXT_EXTENSIONS = listOf(".txt", ".md", ".markdown", ".text")
        val ZIP_MAGIC = "PK".toByteArray()
        val PDF_MAGIC = "%PDF".toByteArray()
        val UNSAFE = Regex("[^A-Za-z0-9._-]+")
        val SAFE_EXTENSION = Regex("[a-z0-9]{1,5}")
        const val COVER_HEIGHT_PX = 600

        fun isPdfName(name: String) = name.endsWith(".pdf", ignoreCase = true)

        fun String?.orElse(fallback: String) = this?.takeIf { it.isNotBlank() } ?: fallback

        fun isTextName(name: String) = TEXT_EXTENSIONS.any { name.lowercase().endsWith(it) }

        /** Whether the file name (not a folder in it) has an extension; a leading dot alone isn't one. */
        fun hasExtension(name: String) = name.substringAfterLast('/').trim().lastIndexOf('.') > 0

        fun ByteArray.startsWith(prefix: ByteArray) = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

        /** The display name without its extension, or the whole name if that leaves nothing. */
        fun titleOf(displayName: String): String =
            displayName.substringAfterLast('/').substringBeforeLast('.').trim().ifEmpty { displayName.trim() }

        /** A filename-safe stem: runs of other characters become `-`, at most 60 characters, `book` if empty. */
        fun sanitise(title: String): String = title.replace(UNSAFE, "-").take(60).ifEmpty { "book" }

        /** Reads [input] to the end, copying it to [out] if given; returns its sha256 in hex. */
        fun sha256(input: InputStream, out: OutputStream? = null): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
                out?.write(buffer, 0, n)
            }
            return digest.digest().toHex()
        }

        fun sha1(text: String): String = MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).toHex()

        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    }
}

/**
 * Drops an emission that differs from the last only in `indexedAt`: indexing a book touches its row, and nothing that
 * watches the list needs to redraw for that. A list emitted for any other change carries the current values. A
 * pack book (Gutenberg, nautilus) becoming indexed, or no longer, is a change: its shelf marks the books searchable in
 * full (Plan GB, Plan KB).
 */
internal fun Flow<List<Book>>.ignoringIndexedAt(): Flow<List<Book>> =
    distinctUntilChanged { old, new -> old.size == new.size && old.indices.all { sameButIndexedAt(old[it], new[it]) } }

private fun sameButIndexedAt(old: Book, new: Book): Boolean =
    old.copy(indexedAt = null) == new.copy(indexedAt = null) &&
        (!old.origin.isPackBook || (old.indexedAt == null) == (new.indexedAt == null))
