package app.granger.core.library

import androidx.room.withTransaction
import app.granger.core.books.EpubInspector
import app.granger.core.books.Inspection
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
import org.json.JSONArray
import org.json.JSONException

/** One book in a Gutenberg pack's list: its title, author and Gutenberg id (`22636`). */
data class GutenbergListing(val title: String, val author: String, val gutenbergId: String)

/**
 * A Gutenberg pack's list of books, `full_by_title.js`: `var json_data = [[title, author, "110", id, "LCC"], …];`
 * (gutenberg2zim 3.0.1, inspected 2026-10-30). Author pages and per-shelf lists sit beside it; this one lists them all.
 */
object GutenbergList {
    const val PATH = "full_by_title.js"

    /** The books of [js], in its order; a malformed row is skipped, and a malformed file lists nothing. */
    fun parse(js: String): List<GutenbergListing> {
        val start = js.indexOf('[')
        val end = js.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val rows = try {
            JSONArray(js.substring(start, end + 1))
        } catch (_: JSONException) {
            return emptyList()
        }
        val out = ArrayList<GutenbergListing>(rows.length())
        for (i in 0 until rows.length()) {
            val row = rows.optJSONArray(i) ?: continue
            val title = row.optString(0).trim()
            val id = row.opt(3)?.toString()?.trim()?.removeSuffix(".0").orEmpty()
            if (title.isEmpty() || id.isEmpty() || !id.all(Char::isDigit)) continue
            out += GutenbergListing(title, row.optString(1).trim(), id)
        }
        return out
    }

    /**
     * Where [listing]'s EPUB is in [archive]: `{title}.{id}.epub`, built from the listed title exactly (titles hold `:`,
     * `'`, `(`). If that misses (the scraper changed the title for its file name), the title suggestions are asked for
     * the book's own page, `{title}.{id}` or `{title}_cover.{id}`, and its EPUB is the page's path plus `.epub`. Null
     * when the pack has no EPUB for it.
     */
    fun epubPath(archive: ZimArchive, listing: GutenbergListing): String? {
        val id = listing.gutenbergId
        val direct = "${listing.title}.$id.epub"
        if (isEpub(archive, direct)) return direct
        val suffix = ".$id"
        return archive.suggest(listing.title, SUGGESTIONS).asSequence()
            .map { it.path }
            .filter { it.endsWith(suffix) }
            .map { it.removeSuffix(suffix).removeSuffix("_cover") + "$suffix.epub" }
            .firstOrNull { isEpub(archive, it) }
    }

    /** The pack's cover image for book [gutenbergId], by the names the scraper gives them; null when it has none. */
    fun coverPath(archive: ZimArchive, gutenbergId: String): String? =
        COVER_NAMES.asSequence().map { it.replace("{id}", gutenbergId) }.firstNotNullOfOrNull { archive.resolvePath(it) }

    /** The Gutenberg id in an EPUB entry's path (`Some Title.22636.epub`), or null if it isn't one. */
    fun idOfEpub(path: String): String? =
        path.takeIf { it.endsWith(".epub") }?.removeSuffix(".epub")?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }

    private fun isEpub(archive: ZimArchive, path: String): Boolean =
        archive.entry(path)?.mimeType?.substringBefore(';')?.trim() == EPUB_MIME

    const val EPUB_MIME = "application/epub+zip"
    private const val SUGGESTIONS = 20

    /** Seen in the packs: `{id}_cover.jpg`, `{id}_{id}-cover.png`, `{id}_new-cover.jpg`; the rest are their siblings. */
    private val COVER_NAMES = listOf(
        "{id}_cover.jpg", "{id}_{id}-cover.png", "{id}_new-cover.jpg",
        "{id}_cover.png", "{id}_{id}-cover.jpg", "{id}_new-cover.png",
    )
}

/**
 * The books of the installed Project Gutenberg packs as shelf rows (Plan GB Decision 3). [sync] lists every book of
 * every installed Gutenberg pack as a [BookOrigin.GUTENBERG] row, `book:gutenberg:<id>`, from the pack's own list, and
 * nothing is copied; [restore] copies a book's EPUB out of its pack the first time it's opened.
 *
 * A row's `sourceRef` is `<pack name>/<epub path>`: the ZIM name, not its UUID, so a new snapshot keeps the row, and
 * with it the place and marks. Its `filePath` is `<dir>/<id>.epub`, there only once it has been opened.
 */
class GutenbergBooks(
    private val db: LibraryDatabase,
    private val archiveOf: (packId: String) -> ZimArchive?,
    /** Where opened books are copied: the internal volume's `books/gutenberg`. */
    private val dir: () -> File,
    private val coversDir: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.bookDao()
    private val mutex = Mutex()

    /** A book as one pack holds it. */
    private class Held(val listing: GutenbergListing, val path: String, val sizeBytes: Long)

    /** Each pack's books, by pack id (a UUID names one snapshot, whose contents never change); read once. */
    private val listed = ConcurrentHashMap<String, List<Held>>()

    /**
     * Brings the rows into line with [packs] (every installed pack; the Gutenberg ones are picked out). Each book of an
     * OK pack gets a row, unchanged rows aren't written, and a book in two packs is one row, filed with the pack it
     * already names when that pack still has it. A row whose pack is gone (no pack of that name) goes, with its copy,
     * cover and index text; its place and marks stay for a reinstall. A pack that's missing or won't open keeps its
     * rows, books that were copied out still reading. Blocking work runs on [io].
     */
    suspend fun sync(packs: List<Pack>) = mutex.withLock {
        withContext(io) {
            val gutenberg = packs.filter { it.isGutenberg }
            val readable = ArrayList<Pair<Pack, List<Held>>>()
            for (pack in gutenberg.sortedWith(PACK_ORDER)) {
                if (pack.status != PackStatus.OK) continue
                val held = held(pack) ?: continue
                readable += pack to held
            }
            val readableNames = readable.mapTo(HashSet()) { it.first.name }
            // Every pack holding each book, in pack order.
            val holders = LinkedHashMap<String, MutableList<Pair<Pack, Held>>>()
            for ((pack, books) in readable) for (book in books) {
                holders.getOrPut(book.listing.gutenbergId) { ArrayList(2) } += pack to book
            }
            val existing = dao.byOrigin(BookOrigin.GUTENBERG).associateBy { it.id }
            val dirFile = dir()
            val writes = ArrayList<Book>()
            for ((gutenbergId, holding) in holders) {
                val id = BookIds.of(BookOrigin.GUTENBERG, gutenbergId)
                val old = existing[id]
                val (pack, held) = holding.firstOrNull { old != null && packNameOf(old) == it.first.name } ?: holding.first()
                val row = (old ?: newRow(id, gutenbergId, dirFile)).copy(
                    sourceRef = "${pack.name}/${held.path}",
                    title = held.listing.title,
                    author = held.listing.author,
                    language = old?.language?.takeIf { it.isNotBlank() } ?: pack.language,
                    sizeBytes = if (old != null && File(old.filePath).isFile) old.sizeBytes else held.sizeBytes,
                    status = PackStatus.OK,
                )
                if (row != old) writes += row
            }
            val gone = ArrayList<Book>()
            val installedNames = gutenberg.mapTo(HashSet()) { it.name }
            for (book in existing.values) {
                if (gutenbergIdOf(book) in holders) continue
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
            if (writes.isEmpty() && gone.isEmpty()) return@withContext
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
        }
    }

    /**
     * Copies [book]'s EPUB out of its pack when it isn't there (the first open, or storage cleared), then reads its
     * cover; false if it isn't a Gutenberg book or no installed pack can give it. The pack its row names is asked
     * first, then any other readable Gutenberg pack with the same entry. The copy goes through a `.incoming-` file,
     * after a check for free space, and a file that isn't a readable EPUB isn't kept.
     */
    suspend fun restore(book: Book): Boolean = withContext(io) {
        if (book.origin != BookOrigin.GUTENBERG) return@withContext false
        val target = File(book.filePath)
        if (target.isFile) return@withContext true
        val path = book.sourceRef.substringAfter('/', "")
        if (path.isEmpty()) return@withContext false
        val packName = packNameOf(book)
        val candidates = db.packDao().getAll()
            .filter { it.isGutenberg && it.status == PackStatus.OK }
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
            val meta = (EpubInspector.inspect(target, book.title) as? Inspection.Ok)?.meta
            if (meta == null) {
                target.delete()
                return@withContext false
            }
            val current = dao.get(book.id) ?: book
            dao.upsert(
                current.copy(
                    sourceRef = "${pack.name}/$path",
                    sizeBytes = target.length(),
                    coverPath = writeCover(book.id, meta.cover, meta.coverExtension) ?: current.coverPath,
                    status = PackStatus.OK,
                    indexedAt = null,
                ),
            )
            return@withContext true
        }
        false
    }

    /**
     * The row for the EPUB at [path] in pack [packId], for a link tapped on the pack's own pages; synced first when the
     * pack's books aren't listed yet. Null when it isn't a Gutenberg book this pack lists.
     */
    suspend fun bookFor(packId: String, path: String): Book? {
        val gutenbergId = GutenbergList.idOfEpub(path) ?: return null
        val id = BookIds.of(BookOrigin.GUTENBERG, gutenbergId)
        dao.get(id)?.let { return it }
        sync(db.packDao().getAll())
        return dao.get(id)
    }

    /** The pack's books, from its list (read once per snapshot); null when the pack can't be read or has no list. */
    private fun held(pack: Pack): List<Held>? = listed[pack.id] ?: run {
        val archive = archiveOf(pack.id) ?: return null
        val js = archive.read(GutenbergList.PATH)?.bytes ?: return null
        val books = GutenbergList.parse(String(js, Charsets.UTF_8)).mapNotNull { listing ->
            val path = GutenbergList.epubPath(archive, listing) ?: return@mapNotNull null
            Held(listing, path, archive.entry(path)?.sizeBytes ?: 0L)
        }
        listed[pack.id] = books
        books
    }

    private fun newRow(id: String, gutenbergId: String, dir: File) = Book(
        id = id, origin = BookOrigin.GUTENBERG, sourceRef = "", filePath = File(dir, "$gutenbergId.epub").absolutePath,
        title = "", author = "", translator = "", date = "", language = "", subjects = "", description = "",
        coverPath = null, sizeBytes = 0L, status = PackStatus.OK, indexedAt = null, addedAt = now(),
    )

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
        /** The pack name in a Gutenberg row's `sourceRef` (`gutenberg_en_lcc-m/Title.123.epub`). */
        fun packNameOf(book: Book): String = book.sourceRef.substringBefore('/')

        /** The Gutenberg id of a [BookOrigin.GUTENBERG] row (`book:gutenberg:22636` → `22636`). */
        fun gutenbergIdOf(book: Book): String = book.id.removePrefix(BookIds.of(BookOrigin.GUTENBERG, ""))

        /** Subject packs before the two everything-packs, so a shared book is filed under its subject; then by name. */
        private val PACK_ORDER = compareBy<Pack>({ it.name.endsWith("_all") }, { it.name }, { it.id })

        private val SAFE_EXTENSION = Regex("[a-z0-9]{1,5}")

        private fun sha1(text: String): String =
            MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
