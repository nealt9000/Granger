package app.granger.core.library

import app.granger.core.zim.ZimArchive
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

// The kinds of book pack (Plan GB, Plan KB, Plan KB2), shared by both phones since Plan I5.

/**
 * Project Gutenberg packs as a [PackBookSource] (Plan GB Decision 3): each book of the pack's `full_by_title.js` is a
 * [BookOrigin.GUTENBERG] row, `book:gutenberg:<id>`, one row whichever pack or snapshot holds it, copied out to
 * `<id>.epub` when opened. Subject packs are read before the two everything-packs, so a shared book is filed under its
 * subject.
 */
object GutenbergSource : PackBookSource {
    override val origin = BookOrigin.GUTENBERG

    override val packOrder: Comparator<Pack> = compareBy<Pack>({ it.name.endsWith("_all") }, { it.name }, { it.id })

    override fun list(pack: Pack, archive: ZimArchive): List<PackItem>? = PackBookList.gutenberg(archive)

    override fun refOf(pack: Pack, path: String): String? = GutenbergList.idOfEpub(path)

    override fun fileName(ref: String, format: BookFormat): String = "$ref.epub"
}

/**
 * Kiwix packs made book collections by a manifest, or listed by their files' names (Plan KB2), as a [PackBookSource]:
 * each item is a [BookOrigin.KIWIX] row, `book:kiwix:<pack name>/<key>`, with its section, order and parts. The manifest
 * was checked against this exact pack file before [Pack.bookManifest] named it ([BookManifests]), so its items are
 * trusted without looking each one up in the pack.
 */
object ManifestSource : PackBookSource {
    override val origin = BookOrigin.KIWIX

    override val packOrder: Comparator<Pack> = compareBy<Pack>({ it.name }, { it.id })

    override fun list(pack: Pack, archive: ZimArchive): List<PackItem>? = PackBookList.manifest(pack, archive)

    override fun refOf(pack: Pack, path: String): String? {
        val manifest = pack.bookManifest ?: return null
        val p = path.removePrefix("/")
        if (manifest == Pack.BOOK_FILES) {
            val name = p.substringBefore('?')
            return p.takeIf { name.endsWith(".pdf", ignoreCase = true) || name.endsWith(".epub", ignoreCase = true) }
                ?.let { "${pack.name}/$it" }
        }
        return BookManifest.keyOf(manifest, p)?.let { "${pack.name}/$it" }
    }

    override fun fileName(ref: String, format: BookFormat): String =
        PackBooks.sha1(ref) + if (format == BookFormat.EPUB) ".epub" else ".pdf"
}

/**
 * Kiwix's nautilus packs as a [PackBookSource] (Plan KB Decision 4): each file of each record of the pack's
 * `database.js` is a [BookOrigin.NAUTILUS] row, `book:nautilus:<pack name>/<fp>`, titled by its record ("Title (2 of 3)"
 * for a record with several files), PDF or EPUB by the file's type. A file the pack doesn't hold, or that is neither, is
 * left out. One pack's documents are its own: no two packs share a row.
 */
object NautilusSource : PackBookSource {
    override val origin = BookOrigin.NAUTILUS

    override val packOrder: Comparator<Pack> = compareBy<Pack>({ it.name }, { it.id })

    override fun list(pack: Pack, archive: ZimArchive): List<PackItem>? = PackBookList.nautilus(pack, archive)

    override fun refOf(pack: Pack, path: String): String? =
        path.removePrefix("/").takeIf { it.startsWith(NautilusDatabase.FILES) && formatOf(it) != null }
            ?.let { "${pack.name}/${it.removePrefix(NautilusDatabase.FILES)}" }

    override fun fileName(ref: String, format: BookFormat): String =
        PackBooks.sha1(ref) + if (format == BookFormat.EPUB) ".epub" else ".pdf"

    /** A listed file's format by its name; null for a file the reader can't open. */
    fun formatOf(fp: String): BookFormat? = PackBookList.formatOfName(fp)
}

/**
 * The documents of the installed nautilus packs as shelf rows (Plan KB Decisions 4 and 5): [PackBooks] over
 * [NautilusSource], copied out to [dir] (`books/nautilus`) when first opened.
 *
 * A PDF read before Plan KB was a hidden pack document ([BookOrigin.PACK], `book:pack:<uuid>/files/<fp>`, or
 * `<name>/files/<fp>` once filed by name, Plan KB2 Task 2.4). Each sync
 * hands such a row's place, bookmarks, highlights, reading tabs and history to the pack's listed row for the same file,
 * moves its copy (and cover) over when the listed row has none, and removes it.
 *
 * Shared since Plan I5; Android's NautilusBooks adapts its `File`s.
 */
open class NautilusPackBooks(
    db: LibraryDatabase,
    archiveOf: (packId: String) -> ZimArchive?,
    dir: () -> String,
    coversDir: String,
    io: CoroutineDispatcher = Dispatchers.IO,
    now: () -> Long = ::epochMillis,
    pdfCovers: PdfCovers = PdfCovers.NONE,
    copyOut: (archive: ZimArchive, path: String, to: String) -> Boolean = ::copyEntry,
) : PackBooks(db, NautilusSource, archiveOf, dir, coversDir, io, now, pdfCovers, copyOut) {
    /** How many pack documents the last [sync] adopted; for the log. */
    @kotlin.concurrent.Volatile var adopted: Int = 0
        private set

    override suspend fun afterSync(listed: List<Pack>) {
        if (listed.isEmpty()) return
        val documents = dao.byOrigin(BookOrigin.PACK)
        if (documents.isEmpty()) return
        var count = 0
        for (pack in listed) {
            // Keyed by the pack's UUID (before Plan KB2) or its name (since Task 2.4).
            val prefixes = listOf("${pack.id}/${NautilusDatabase.FILES}", "${pack.name}/${NautilusDatabase.FILES}")
            for (old in documents) {
                val prefix = prefixes.firstOrNull { old.sourceRef.startsWith(it) } ?: continue
                val fp = old.sourceRef.removePrefix(prefix)
                val new = dao.get(BookIds.of(BookOrigin.NAUTILUS, "${pack.name}/$fp")) ?: continue
                adopt(old, new)
                count++
            }
        }
        adopted = count
    }

    /** [new] takes [old]'s place, marks, tabs and history, and its copy and cover if it has none; [old] goes. */
    private suspend fun adopt(old: Book, new: Book) {
        val oldFile = old.filePath
        val newFile = new.filePath
        var moved: Book? = null
        if (!Disk.isFile(newFile) && Disk.isFile(oldFile)) {
            Disk.parent(newFile)?.let { Disk.mkdirs(it) }
            if (Disk.renameTo(oldFile, newFile)) {
                val cover = old.coverPath?.takeIf { Disk.isFile(it) }?.let { from ->
                    val name = "${sha1(new.id)}.${extensionOf(from)}"
                    val to = Disk.parent(from)?.let { Disk.child(it, name) } ?: name
                    if (Disk.renameTo(from, to)) Disk.absolute(to) else null
                } ?: if (new.format == BookFormat.PDF) writeCover(new.id, pdfCovers.firstPage(newFile, COVER_HEIGHT_PX), "png") else null
                moved = new.copy(
                    sizeBytes = Disk.length(newFile), coverPath = cover ?: new.coverPath, status = PackStatus.OK,
                    indexedAt = null,
                )
            }
        }
        db.inTransaction {
            handOverBook(db, old.id, new.id)
            moved?.let { dao.upsert(it) }
        }
        if (moved == null) Disk.delete(oldFile)
        if (moved?.coverPath != old.coverPath) old.coverPath?.takeIf { Disk.isFile(it) }?.let { Disk.delete(it) }
    }
}

/**
 * A link tapped on a pack's own page (Plan KB Decision 4): in a book pack, the file it points to opens as that pack's
 * listed row, whatever its type; anywhere else, or for a file the pack doesn't list, null.
 */
class PackBookLinks(private val packs: suspend () -> List<Pack>, private val sources: List<PackBooks>) {
    /** The listed row for [path] in [packId], or null when [packId] isn't a book pack or doesn't list it. */
    suspend fun bookFor(packId: String, path: String): Book? {
        val pack = packs().firstOrNull { it.id == packId } ?: return null
        val books = sources.firstOrNull { it.source.origin == pack.bookSource } ?: return null
        return books.bookFor(packId, path)
    }

    /** Whether [packId] is a book pack: its links open its own rows ([bookFor]). */
    suspend fun isBookPack(packId: String): Boolean = packs().firstOrNull { it.id == packId }?.isBookPack == true
}
