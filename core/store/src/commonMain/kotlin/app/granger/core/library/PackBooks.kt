package app.granger.core.library

import app.granger.core.books.EpubInspector
import app.granger.core.books.Inspection
import app.granger.core.books.PdfInspector
import app.granger.core.text.hex
import app.granger.core.text.sha1
import app.granger.core.zim.ZimArchive
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.io.IOException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * What [PackBooks] needs to know about one kind of book pack (Plan KB Decision 4): which packs are its, how to list a
 * pack's books, and where an opened book's copy goes.
 */
interface PackBookSource {
    /** The origin of its rows; a pack is this source's when its [bookSource] is this. */
    val origin: BookOrigin

    /** The order packs are read in, so a book two packs hold is filed with the first. */
    val packOrder: Comparator<Pack>

    /** [pack]'s books from [archive], in the pack's order; null when it can't be read or has no list. */
    fun list(pack: Pack, archive: ZimArchive): List<PackItem>?

    /** The [PackItem.ref] of the book at entry [path] in [pack], for a tapped link; null when it isn't one. */
    fun refOf(pack: Pack, path: String): String?

    /** The copy's file name in the books folder, once the book with [ref] is opened. */
    fun fileName(ref: String, format: BookFormat): String
}

/**
 * The books of the installed book packs of one [source] as shelf rows (Plan GB Decision 3, generalised by Plan KB
 * Decision 4). [sync] lists every book of every installed pack of the source from the pack's own list, and nothing is
 * copied; [restore] copies a book's file out of its pack the first time it's opened, and reads it with the inspector
 * for its format.
 *
 * A row's `sourceRef` is `<pack name>/<entry path>`: the ZIM name, not its UUID, so a new snapshot keeps the row, and
 * with it the place and marks. Its `filePath` is in [dir], there only once it has been opened.
 *
 * Shared by both phones since Plan I5 (Android's GutenbergBooks, ManifestBooks and NautilusBooks adapt their `File`s).
 */
open class PackBooks(
    protected val db: LibraryDatabase,
    val source: PackBookSource,
    private val archiveOf: (packId: String) -> ZimArchive?,
    /** Where opened books are copied: the internal volume's `books/<source>`. */
    private val dir: () -> String,
    protected val coversDir: String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = ::epochMillis,
    protected val pdfCovers: PdfCovers = PdfCovers.NONE,
    /** Copies the entry [path] of [archive] to the file [to]; false when it can't (Android: libkiwix's own copy). */
    private val copyOut: (archive: ZimArchive, path: String, to: String) -> Boolean = ::copyEntry,
) {
    protected val dao = db.bookDao()
    private val mutex = Mutex()
    private val origin = source.origin

    /**
     * Each pack's books, by pack id (a UUID names one snapshot, whose contents never change) and book manifest (which can
     * arrive after the pack, Plan KB2); read once.
     */
    private val listed = ConcurrentCache<String, List<PackItem>>()

    private fun mine(pack: Pack) = pack.bookSource == origin

    /**
     * Brings the rows into line with [packs] (every installed pack; this source's are picked out). Each book of an OK
     * pack gets a row, unchanged rows aren't written, and a book in two packs is one row, filed with the pack it already
     * names when that pack still has it. A row whose pack is gone (no pack of that name) goes, with its copy, cover and
     * index text; its place and marks stay for a reinstall. A pack that's missing or won't open keeps its rows, books
     * that were copied out still reading. Blocking work runs on [io]. Returns the rows written.
     */
    suspend fun sync(packs: List<Pack>): List<Book> = mutex.withLock {
        withContext(io) {
            val ours = packs.filter(::mine)
            val readable = ArrayList<Pair<Pack, List<PackItem>>>()
            for (pack in ours.sortedWith(source.packOrder)) {
                if (pack.status != PackStatus.OK) continue
                val held = held(pack) ?: continue
                readable += pack to held
            }
            val readableNames = readable.mapTo(HashSet()) { it.first.name }
            // Every pack holding each book, in pack order.
            val holders = LinkedHashMap<String, MutableList<Pair<Pack, PackItem>>>()
            for ((pack, items) in readable) for (item in items) {
                holders.getOrPut(item.ref) { ArrayList(2) } += pack to item
            }
            val existing = dao.byOrigin(origin).associateBy { it.id }
            val dirFile = dir()
            val writes = ArrayList<Book>()
            for ((ref, holding) in holders) {
                val id = BookIds.of(origin, ref)
                val old = existing[id]
                val (pack, item) = holding.firstOrNull { old != null && packNameOf(old) == it.first.name } ?: holding.first()
                // A book read online (Plan BK) is the pack's own once the pack is here: its copy comes from the pack.
                val base = if (old?.loose == Loose.SAVED) {
                    old.copy(loose = null, hidden = false, filePath = Disk.absolute(Disk.child(dirFile, source.fileName(ref, item.format))))
                } else {
                    old ?: newRow(id, ref, item.format, dirFile)
                }
                val row = base.copy(
                    sourceRef = "${pack.name}/${item.path}",
                    title = item.title,
                    author = item.author,
                    description = item.description ?: old?.description.orEmpty(),
                    language = item.language?.takeIf { it.isNotBlank() } ?: old?.language?.takeIf { it.isNotBlank() } ?: pack.language,
                    sizeBytes = if (Disk.isFile(base.filePath)) base.sizeBytes else item.sizeBytes,
                    status = PackStatus.OK,
                    groupName = item.groupName,
                    ord = item.ord,
                    workKey = item.workKey,
                    workTitle = item.workTitle,
                    partNo = item.partNo,
                    // Every pack listing it, so each one's collection holds it (Plan KB2 Task 4.7).
                    memberOf = holding.map { it.first.name }.distinct().joinToString("\n"),
                )
                if (row != old) writes += row
            }
            val gone = ArrayList<Book>()
            val installedNames = ours.mapTo(HashSet()) { it.name }
            for (book in existing.values) {
                if (refOf(book) in holders) continue
                val packName = packNameOf(book)
                // Kept on its own (Plan BK): never removed by a pack's sync. Read online: it doesn't need its pack, unless
                // the pack is here and no longer lists it.
                if (book.loose == Loose.KEPT || (book.loose == Loose.SAVED && packName !in readableNames)) continue
                when {
                    // Its pack is there and readable but doesn't list it, or no pack of that name is installed.
                    packName in readableNames || packName !in installedNames -> gone += book
                    // Its pack is missing or damaged: a copy still reads; one never opened can't.
                    else -> {
                        val status = if (Disk.isFile(book.filePath)) PackStatus.OK else PackStatus.MISSING
                        if (status != book.status) writes += book.copy(status = status)
                    }
                }
            }
            if (writes.isNotEmpty() || gone.isNotEmpty()) {
                // A book read as an ebook goes with its original pages: the PDF beside it and its hidden PAGES row,
                // whose place and marks stay for a reinstall as the book's do.
                val goneCovers = gone.mapNotNull { dao.get(BookIds.pagesOf(it.id))?.coverPath }
                db.inTransaction {
                    if (writes.isNotEmpty()) dao.upsertAll(writes)
                    for (book in gone) {
                        dao.deleteChunks(book.id)
                        dao.delete(book.id)
                        val pages = BookIds.pagesOf(book.id)
                        dao.deleteChunks(pages)
                        dao.delete(pages)
                        // Its tabs would say "Removed book" on the rail (Plan KB2 Task 4.3); its place and marks stay.
                        db.sessionDao().deleteTabsForPack(book.id)
                        db.sessionDao().deleteTabsForPack(pages)
                    }
                }
                for (book in gone) {
                    Disk.delete(book.filePath)
                    PdfEbooks.pagesOf(book.filePath)?.let { Disk.delete(it) }
                    book.coverPath?.let { Disk.delete(it) }
                }
                for (cover in goneCovers) Disk.delete(cover)
            }
            afterSync(readable.map { it.first })
            writes
        }
    }

    /** Runs at the end of [sync], under its lock and on [io], with the packs whose books were listed. */
    protected open suspend fun afterSync(listed: List<Pack>) {}

    /**
     * Copies [book]'s file out of its pack when it isn't there (the first open, or storage cleared), then reads its
     * cover (an EPUB's own, a PDF's first page); false if it isn't this source's book or no installed pack can give it.
     * The pack its row names is asked first, then any other readable pack of the source with the same entry. The copy
     * goes through a `.incoming-` file, after a check for free space, and a file that isn't readable isn't kept. A PDF
     * read as an ebook comes back as its PDF, made an ebook again ([ebookAgain]).
     */
    suspend fun restore(book: Book): Boolean = withContext(io) {
        if (book.origin != origin) return@withContext false
        val target = book.filePath
        if (Disk.isFile(target)) return@withContext true
        val path = book.sourceRef.substringAfter('/', "")
        if (path.isEmpty()) return@withContext false
        val packName = packNameOf(book)
        // A PDF read as an ebook ([RoomBookshelf.readAsEbook]) points at `x.pdf.epub`: the pack's PDF goes back to
        // `x.pdf`, its original pages, and the ebook is made from it again (Plan KB2 Task 2.5).
        val pages = PdfEbooks.pagesOf(target)
            ?.takeIf { book.format == BookFormat.EPUB && path.endsWith(".pdf", ignoreCase = true) }
        if (pages != null && Disk.isFile(pages) && PdfInspector.inspect(pages, book.title) is Inspection.Ok) {
            return@withContext ebookAgain(dao.get(book.id) ?: book, pages, "$packName/$path")
        }
        val copyTo = pages ?: target
        val candidates = db.packDao().getAll()
            .filter { mine(it) && it.status == PackStatus.OK }
            .sortedByDescending { it.name == packName }
        for (pack in candidates) {
            val archive = archiveOf(pack.id) ?: continue
            val entry = archive.entry(path) ?: continue
            val parent = Disk.parent(copyTo)?.also { Disk.mkdirs(it) } ?: return@withContext false
            if (Disk.freeBytes(parent) < entry.sizeBytes) return@withContext false
            val partial = Disk.child(parent, ".incoming-${nameOf(copyTo)}")
            if (!copyOut(archive, entry.path, partial) || !Disk.renameTo(partial, copyTo)) {
                Disk.delete(partial)
                continue
            }
            val current = dao.get(book.id) ?: book
            if (pages != null) {
                if (PdfInspector.inspect(pages, book.title) !is Inspection.Ok) {
                    Disk.delete(pages)
                    return@withContext false
                }
                return@withContext ebookAgain(current, pages, "${pack.name}/$path")
            }
            val cover = when (book.format) {
                BookFormat.EPUB -> (EpubInspector.inspect(target, book.title) as? Inspection.Ok)?.meta
                    ?.let { Cover(it.cover, it.coverExtension) }
                BookFormat.PDF -> (PdfInspector.inspect(target, book.title) as? Inspection.Ok)
                    ?.let { Cover(pdfCovers.firstPage(target, COVER_HEIGHT_PX), "png") }
            }
            if (cover == null) {
                Disk.delete(target)
                return@withContext false
            }
            // Opening a removed book brings it back to the shelf (Plan KB2 Task 4.3).
            dao.upsert(
                current.copy(
                    sourceRef = "${pack.name}/$path",
                    sizeBytes = Disk.length(target),
                    coverPath = writeCover(book.id, cover.bytes, cover.extension) ?: current.coverPath,
                    status = PackStatus.OK,
                    indexedAt = null,
                    hidden = false,
                ),
            )
            return@withContext true
        }
        false
    }

    /**
     * Makes [book]'s ebook from [pdf] again, as "Read as ebook" did, keeping its place and marks. If the conversion no
     * longer passes, the book goes back to being the PDF rather than a row that won't open: the PDF's place and marks,
     * kept on its original pages, come back to it, and the pages' row goes.
     */
    private suspend fun ebookAgain(book: Book, pdf: String, sourceRef: String): Boolean {
        val (report, meta) = try {
            PdfEbooks.make(pdf, book.title, book.author, book.language)
        } catch (_: OutOfMemoryError) {
            Disk.delete(PdfEbooks.ebookOf(pdf))
            null to null
        }
        if (meta != null) {
            val ebook = PdfEbooks.ebookOf(pdf)
            dao.upsert(
                book.copy(
                    sourceRef = sourceRef,
                    filePath = Disk.absolute(ebook),
                    sizeBytes = Disk.length(ebook),
                    coverPath = writeCover(book.id, meta.cover, meta.coverExtension) ?: book.coverPath,
                    status = PackStatus.OK,
                    indexedAt = null,
                    hidden = false,
                ),
            )
            return true
        }
        logWarn("Making ${book.id} an ebook again failed (${report?.why ?: "out of memory"}); reading its PDF")
        val pagesId = BookIds.pagesOf(book.id)
        val pagesCover = dao.get(pagesId)?.coverPath
        val cover = writeCover(book.id, pdfCovers.firstPage(pdf, COVER_HEIGHT_PX), "png")
        db.inTransaction {
            if (dao.position(pagesId) != null) dao.movePosition(pagesId, book.id)
            dao.moveMarks(pagesId, book.id)
            dao.deleteChunks(book.id)
            dao.delete(pagesId)
            dao.upsert(
                book.copy(
                    sourceRef = sourceRef,
                    filePath = Disk.absolute(pdf),
                    format = BookFormat.PDF,
                    sizeBytes = Disk.length(pdf),
                    coverPath = cover ?: book.coverPath,
                    status = PackStatus.OK,
                    indexedAt = null,
                    hidden = false,
                ),
            )
        }
        pagesCover?.takeIf { it != cover }?.let { Disk.delete(it) }
        return true
    }

    /**
     * The row for the entry at [path] in pack [packId], for a link tapped on the pack's own pages; synced first when
     * the pack's books aren't listed yet. Null when it isn't a book this pack lists.
     */
    suspend fun bookFor(packId: String, path: String): Book? {
        val pack = db.packDao().get(packId)?.takeIf(::mine) ?: return null
        val ref = source.refOf(pack, path) ?: return null
        val id = BookIds.of(origin, ref)
        dao.get(id)?.let { return it }
        sync(db.packDao().getAll())
        return dao.get(id)
    }

    /** The pack's books, from its list (read once per snapshot); null when the pack can't be read or has no list. */
    private fun held(pack: Pack): List<PackItem>? {
        val key = "${pack.id}|${pack.bookManifest.orEmpty()}"
        listed[key]?.let { return it }
        val archive = archiveOf(pack.id) ?: return null
        val items = source.list(pack, archive) ?: return null
        listed[key] = items
        return items
    }

    private fun newRow(id: String, ref: String, format: BookFormat, dir: String) = Book(
        id = id, origin = origin, sourceRef = "", filePath = Disk.absolute(Disk.child(dir, source.fileName(ref, format))),
        title = "", author = "", translator = "", date = "", language = "", subjects = "", description = "",
        coverPath = null, sizeBytes = 0L, status = PackStatus.OK, indexedAt = null, addedAt = now(), format = format,
    )

    private class Cover(val bytes: ByteArray?, val extension: String?)

    /** Writes an opened book's own cover to `coversDir/<sha1 of id>.<ext>`; null if it has none or it can't be written. */
    protected fun writeCover(id: String, bytes: ByteArray?, extension: String?): String? {
        if (bytes == null) return null
        val ext = extension?.lowercase()?.takeIf { SAFE_EXTENSION.matches(it) } ?: "img"
        val target = Disk.child(coversDir, "${sha1(id)}.$ext")
        return try {
            Disk.mkdirs(coversDir)
            Disk.writeBytes(target, bytes)
            Disk.absolute(target)
        } catch (_: IOException) {
            Disk.delete(target)
            null
        }
    }

    companion object {
        /** The pack name in a pack book's `sourceRef` (`gutenberg_en_lcc-m/Title.123.epub`, `zimgit-water_en/files/…`). */
        fun packNameOf(book: Book): String = book.sourceRef.substringBefore('/')

        /** The ref of a pack book's row: its id after `book:<origin>:`. */
        fun refOf(book: Book): String = book.id.removePrefix(BookIds.of(book.origin, ""))

        /** A PDF's first-page cover, as tall as the shelf's other PDF covers ([RoomBookshelf]). */
        const val COVER_HEIGHT_PX = 600

        private val SAFE_EXTENSION = Regex("[a-z0-9]{1,5}")

        /** SHA-1 of [text]'s UTF-8 in lower-case hex: covers and copies are named by it. */
        fun sha1(text: String): String = sha1(text.encodeToByteArray()).hex()
    }
}

/** [PackBooks]' copy of a pack entry when the phone has none of its own: the entry's bytes, written out. */
internal fun copyEntry(archive: ZimArchive, path: String, to: String): Boolean = try {
    val item = archive.read(path)
    if (item == null) false else { Disk.writeBytes(to, item.bytes); true }
} catch (_: IOException) {
    false
}
