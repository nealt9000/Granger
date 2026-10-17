package app.granger.core.library

import androidx.room.withTransaction
import app.granger.core.books.DrmKind
import app.granger.core.books.EpubInspector
import app.granger.core.books.EpubMeta
import app.granger.core.books.Inspection
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
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
        is Protected -> "$name is copy-protected (DRM). Granger can't open it and won't remove the protection."
        Unsupported -> "$name isn't an EPUB, TXT or Markdown file."
        is Damaged -> "$name is damaged or isn't a complete e-book."
    }
}

/** The e-books on the device, where each reader left off, and its bookmarks and highlights. */
interface Bookshelf {
    val books: Flow<List<Book>>
    val positions: Flow<Map<String, BookPosition>>
    suspend fun book(id: String): Book?

    /** Re-checks every book's file and registers EPUBs found in the books folders that have no row. */
    suspend fun refresh()

    /** Registers a finished catalog download; null if the file isn't a readable EPUB. */
    suspend fun addDownloaded(file: File, catalogId: String): Book?

    /**
     * Copies a user's file into the library, converting TXT and Markdown to EPUB. A file whose book is already here
     * and readable is [ImportResult.AlreadyThere]; one whose book is missing or damaged is imported again. Failing to
     * read [open] gives [ImportResult.Damaged]; other exceptions propagate. No partial file is left behind either way.
     */
    suspend fun import(displayName: String, open: () -> InputStream): ImportResult

    /** Deletes the file, cover and search text; the position and marks are kept. False if the file can't be deleted. */
    suspend fun remove(id: String): Boolean

    suspend fun savePosition(bookId: String, locatorJson: String, totalProgression: Double, chapterTitle: String)
    suspend fun position(bookId: String): BookPosition?
    fun marks(bookId: String): Flow<List<BookMark>>
    suspend fun addMark(mark: BookMark): Long
    suspend fun updateMark(mark: BookMark)
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
) : Bookshelf {
    private val dao = db.bookDao()

    override val books: Flow<List<Book>> = dao.observeAll()

    override val positions: Flow<Map<String, BookPosition>> =
        dao.observePositions().map { list -> list.associateBy { it.bookId } }

    override suspend fun book(id: String): Book? = dao.get(id)

    override suspend fun addDownloaded(file: File, catalogId: String): Book? = withContext(io) {
        val meta = (EpubInspector.inspect(file) as? Inspection.Ok)?.meta ?: return@withContext null
        register(BookIds.of(BookOrigin.GRANGER, catalogId), BookOrigin.GRANGER, catalogId, file, meta)
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
            val zip = head.size >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()

            var epub: File? = null
            var meta: EpubMeta? = null
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

            val target = File(dir, "${sha.take(12)}-${sanitise(title)}.epub")
            Files.move(epub.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            try {
                register(id, BookOrigin.IMPORT, sha, target, meta)
            } catch (e: Throwable) {
                target.delete()
                throw e
            }
            // A re-import replaces a damaged or misplaced copy; don't leave the old one as a stray.
            if (existing != null && existing.filePath != target.absolutePath) File(existing.filePath).delete()
            ImportResult.Imported(id)
        } finally {
            incoming.delete()
            converted.delete()
        }
    }

    override suspend fun refresh(): Unit = withContext(io) {
        val known = dao.getAll()
        for (book in known) {
            val status = when {
                !File(book.filePath).exists() -> PackStatus.MISSING
                // A damaged file stays damaged until it inspects cleanly; a returning file is otherwise OK.
                book.status == PackStatus.CORRUPT && EpubInspector.inspect(File(book.filePath)) !is Inspection.Ok ->
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
        val candidates =
            grangerFiles.map { Triple(BookOrigin.GRANGER, it.nameWithoutExtension.substringBefore('_'), it) } +
                importFiles.map { file -> Triple(BookOrigin.IMPORT, file.inputStream().use { sha256(it) }, file) }

        for ((key, group) in candidates.groupBy { (origin, ref, _) -> origin to ref }) {
            val (origin, ref) = key
            val id = BookIds.of(origin, ref)
            val existing = dao.get(id)
            // Older snapshots of a download can be left behind: keep a row whose file is still there, else take the newest.
            if (existing != null && File(existing.filePath).exists()) continue
            val file = group.maxBy { it.third.name }.third
            when (val inspection = EpubInspector.inspect(file)) {
                is Inspection.Ok -> register(id, origin, ref, file, inspection.meta)
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
        book.coverPath?.let { File(it).delete() }
        db.withTransaction {
            dao.deleteChunks(id)
            dao.delete(id)
        }
        true
    }

    override suspend fun savePosition(bookId: String, locatorJson: String, totalProgression: Double, chapterTitle: String) {
        dao.upsertPosition(BookPosition(bookId, locatorJson, totalProgression, chapterTitle, now()))
    }

    override suspend fun position(bookId: String): BookPosition? = dao.position(bookId)

    override fun marks(bookId: String): Flow<List<BookMark>> = dao.observeMarks(bookId)

    override suspend fun addMark(mark: BookMark): Long = dao.insertMark(mark)

    override suspend fun updateMark(mark: BookMark) = dao.updateMark(mark)

    override suspend fun deleteMark(id: Long) = dao.deleteMarkById(id)

    /** Upserts the book with fresh metadata and cover; an existing row keeps its id and `addedAt`, and is re-indexed. */
    private suspend fun register(id: String, origin: BookOrigin, ref: String, file: File, meta: EpubMeta): Book {
        val existing = dao.get(id)
        val book = Book(
            id = id,
            origin = origin,
            sourceRef = ref,
            filePath = file.absolutePath,
            title = meta.title,
            author = meta.authors.joinToString(", "),
            translator = meta.translators.joinToString(", "),
            date = meta.date,
            language = meta.language,
            subjects = meta.subjects.joinToString("; "),
            description = meta.description,
            coverPath = writeCover(id, meta, existing?.coverPath),
            sizeBytes = file.length(),
            status = PackStatus.OK,
            indexedAt = null,
            addedAt = existing?.addedAt ?: now(),
        )
        dao.upsert(book)
        return book
    }

    /** Writes the cover's raw bytes to `coversDir/<sha1 of id>.<ext>` and removes any older cover; null if none. */
    private fun writeCover(id: String, meta: EpubMeta, oldPath: String?): String? {
        val bytes = meta.cover
        val extension = meta.coverExtension?.lowercase()?.takeIf { SAFE_EXTENSION.matches(it) } ?: "img"
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

    /** `.epub` files in [dir] that no row points at; partial `.incoming-*` files are skipped. */
    private fun strays(dir: File, knownPaths: Set<String>): List<File> =
        dir.listFiles().orEmpty().filter {
            it.isFile && it.name.endsWith(".epub", ignoreCase = true) && !it.name.startsWith(".incoming-") &&
                it.absolutePath !in knownPaths
        }

    private companion object {
        const val SNIFF_BYTES = 4096
        val TEXT_EXTENSIONS = listOf(".txt", ".md", ".markdown")
        val UNSAFE = Regex("[^A-Za-z0-9._-]+")
        val SAFE_EXTENSION = Regex("[a-z0-9]{1,5}")

        fun isTextName(name: String) = TEXT_EXTENSIONS.any { name.lowercase().endsWith(it) }

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
