package app.granger.core.library

import androidx.room.withTransaction
import app.granger.core.books.EpubInspector
import app.granger.core.books.Inspection
import app.granger.core.books.PdfInspector
import app.granger.core.zim.ZimArchive
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One book a book pack lists. [ref] is its id within [PackBookSource.origin] (`book:<origin>:<ref>`), [path] its entry
 * in the pack. [description] null leaves a row's description as it is.
 */
data class PackItem(
    val ref: String,
    val path: String,
    val title: String,
    val author: String,
    val format: BookFormat,
    val sizeBytes: Long,
    val description: String? = null,
)

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
 */
open class PackBooks(
    private val db: LibraryDatabase,
    val source: PackBookSource,
    private val archiveOf: (packId: String) -> ZimArchive?,
    /** Where opened books are copied: the internal volume's `books/<source>`. */
    private val dir: () -> File,
    private val coversDir: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
    private val pdfCovers: PdfCovers = PlatformPdfCovers,
) {
    private val dao = db.bookDao()
    private val mutex = Mutex()
    private val origin = source.origin

    /** Each pack's books, by pack id (a UUID names one snapshot, whose contents never change); read once. */
    private val listed = ConcurrentHashMap<String, List<PackItem>>()

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
                val row = (old ?: newRow(id, ref, item.format, dirFile)).copy(
                    sourceRef = "${pack.name}/${item.path}",
                    title = item.title,
                    author = item.author,
                    description = item.description ?: old?.description.orEmpty(),
                    language = old?.language?.takeIf { it.isNotBlank() } ?: pack.language,
                    sizeBytes = if (old != null && File(old.filePath).isFile) old.sizeBytes else item.sizeBytes,
                    status = PackStatus.OK,
                )
                if (row != old) writes += row
            }
            val gone = ArrayList<Book>()
            val installedNames = ours.mapTo(HashSet()) { it.name }
            for (book in existing.values) {
                if (refOf(book) in holders) continue
                val packName = packNameOf(book)
                when {
                    // Its pack is there and readable but doesn't list it, or no pack of that name is installed.
                    packName in readableNames || packName !in installedNames -> gone += book
                    // Its pack is missing or damaged: a copy still reads; one never opened can't.
                    else -> {
                        val status = if (File(book.filePath).isFile) PackStatus.OK else PackStatus.MISSING
                        if (status != book.status) writes += book.copy(status = status)
                    }
                }
            }
            if (writes.isEmpty() && gone.isEmpty()) return@withContext emptyList()
            db.withTransaction {
                if (writes.isNotEmpty()) dao.upsertAll(writes)
                for (book in gone) {
                    dao.deleteChunks(book.id)
                    dao.delete(book.id)
                }
            }
            for (book in gone) {
                File(book.filePath).delete()
                book.coverPath?.let { File(it).delete() }
            }
            writes
        }
    }

    /**
     * Copies [book]'s file out of its pack when it isn't there (the first open, or storage cleared), then reads its
     * cover (an EPUB's own, a PDF's first page); false if it isn't this source's book or no installed pack can give it.
     * The pack its row names is asked first, then any other readable pack of the source with the same entry. The copy
     * goes through a `.incoming-` file, after a check for free space, and a file that isn't readable isn't kept.
     */
    suspend fun restore(book: Book): Boolean = withContext(io) {
        if (book.origin != origin) return@withContext false
        val target = File(book.filePath)
        if (target.isFile) return@withContext true
        val path = book.sourceRef.substringAfter('/', "")
        if (path.isEmpty()) return@withContext false
        val packName = packNameOf(book)
        val candidates = db.packDao().getAll()
            .filter { mine(it) && it.status == PackStatus.OK }
            .sortedByDescending { it.name == packName }
        for (pack in candidates) {
            val archive = archiveOf(pack.id) ?: continue
            val entry = archive.entry(path) ?: continue
            val parent = target.parentFile?.apply { mkdirs() } ?: return@withContext false
            if (parent.usableSpace < entry.sizeBytes) return@withContext false
            val partial = File(parent, ".incoming-${target.name}")
            if (!archive.copyTo(entry.path, partial) || !partial.renameTo(target)) {
                partial.delete()
                continue
            }
            val current = dao.get(book.id) ?: book
            val cover = when (book.format) {
                BookFormat.EPUB -> (EpubInspector.inspect(target, book.title) as? Inspection.Ok)?.meta
                    ?.let { Cover(it.cover, it.coverExtension) }
                BookFormat.PDF -> (PdfInspector.inspect(target, book.title) as? Inspection.Ok)
                    ?.let { Cover(pdfCovers.firstPage(target, COVER_HEIGHT_PX), "png") }
            }
            if (cover == null) {
                target.delete()
                return@withContext false
            }
            dao.upsert(
                current.copy(
                    sourceRef = "${pack.name}/$path",
                    sizeBytes = target.length(),
                    coverPath = writeCover(book.id, cover.bytes, cover.extension) ?: current.coverPath,
                    status = PackStatus.OK,
                    indexedAt = null,
                ),
            )
            return@withContext true
        }
        false
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
    private fun held(pack: Pack): List<PackItem>? = listed[pack.id] ?: run {
        val archive = archiveOf(pack.id) ?: return null
        val items = source.list(pack, archive) ?: return null
        listed[pack.id] = items
        items
    }

    private fun newRow(id: String, ref: String, format: BookFormat, dir: File) = Book(
        id = id, origin = origin, sourceRef = "", filePath = File(dir, source.fileName(ref, format)).absolutePath,
        title = "", author = "", translator = "", date = "", language = "", subjects = "", description = "",
        coverPath = null, sizeBytes = 0L, status = PackStatus.OK, indexedAt = null, addedAt = now(), format = format,
    )

    private class Cover(val bytes: ByteArray?, val extension: String?)

    /** Writes an opened book's own cover to `coversDir/<sha1 of id>.<ext>`; null if it has none or it can't be written. */
    private fun writeCover(id: String, bytes: ByteArray?, extension: String?): String? {
        if (bytes == null) return null
        val ext = extension?.lowercase()?.takeIf { SAFE_EXTENSION.matches(it) } ?: "img"
        val target = File(coversDir, "${sha1(id)}.$ext")
        return try {
            coversDir.mkdirs()
            target.writeBytes(bytes)
            target.absolutePath
        } catch (_: IOException) {
            target.delete()
            null
        }
    }

    companion object {
        /** The pack name in a pack book's `sourceRef` (`gutenberg_en_lcc-m/Title.123.epub`, `zimgit-water_en/files/…`). */
        fun packNameOf(book: Book): String = book.sourceRef.substringBefore('/')

        /** The ref of a pack book's row: its id after `book:<origin>:`. */
        fun refOf(book: Book): String = book.id.removePrefix(BookIds.of(book.origin, ""))

        /** A PDF's first-page cover, as tall as the shelf's other PDF covers ([RoomBookshelf]). */
        private const val COVER_HEIGHT_PX = 600

        private val SAFE_EXTENSION = Regex("[a-z0-9]{1,5}")

        internal fun sha1(text: String): String =
            MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
