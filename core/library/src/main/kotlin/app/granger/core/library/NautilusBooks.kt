package app.granger.core.library

import androidx.room.withTransaction
import app.granger.core.zim.ZimArchive
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Kiwix's nautilus packs as a [PackBookSource] (Plan KB Decision 4): each file of each record of the pack's
 * `database.js` is a [BookOrigin.NAUTILUS] row, `book:nautilus:<pack name>/<fp>`, titled by its record ("Title (2 of 3)"
 * for a record with several files), PDF or EPUB by the file's type. A file the pack doesn't hold, or that is neither, is
 * left out. One pack's documents are its own: no two packs share a row.
 */
object NautilusSource : PackBookSource {
    override val origin = BookOrigin.NAUTILUS

    override val packOrder: Comparator<Pack> = compareBy<Pack>({ it.name }, { it.id })

    override fun list(pack: Pack, archive: ZimArchive): List<PackItem>? {
        val js = archive.read(NautilusDatabase.PATH)?.bytes ?: return null
        val items = ArrayList<PackItem>()
        for (record in NautilusDatabase.records(String(js, Charsets.UTF_8))) {
            val count = record.files.size
            for ((i, fp) in record.files.withIndex()) {
                val format = formatOf(fp) ?: continue
                val path = NautilusDatabase.FILES + fp
                val entry = archive.entry(path) ?: continue
                items += PackItem(
                    ref = "${pack.name}/$fp",
                    path = entry.path,
                    title = if (count > 1) "${record.title} (${i + 1} of $count)" else record.title,
                    author = record.author,
                    format = format,
                    sizeBytes = entry.sizeBytes,
                    description = record.description,
                )
            }
        }
        return items
    }

    override fun refOf(pack: Pack, path: String): String? =
        path.removePrefix("/").takeIf { it.startsWith(NautilusDatabase.FILES) && formatOf(it) != null }
            ?.let { "${pack.name}/${it.removePrefix(NautilusDatabase.FILES)}" }

    override fun fileName(ref: String, format: BookFormat): String =
        PackBooks.sha1(ref) + if (format == BookFormat.EPUB) ".epub" else ".pdf"

    /** A listed file's format by its name; null for a file the reader can't open. */
    fun formatOf(fp: String): BookFormat? = when {
        fp.endsWith(".pdf", ignoreCase = true) -> BookFormat.PDF
        fp.endsWith(".epub", ignoreCase = true) -> BookFormat.EPUB
        else -> null
    }
}

/**
 * The documents of the installed nautilus packs as shelf rows (Plan KB Decisions 4 and 5): [PackBooks] over
 * [NautilusSource], copied out to [dir] (`books/nautilus`) when first opened.
 *
 * A PDF read before Plan KB was a hidden pack document ([BookOrigin.PACK], `book:pack:<uuid>/files/<fp>`). Each sync
 * hands such a row's place, bookmarks, highlights, reading tabs and history to the pack's listed row for the same file,
 * moves its copy (and cover) over when the listed row has none, and removes it.
 */
class NautilusBooks(
    db: LibraryDatabase,
    archiveOf: (packId: String) -> ZimArchive?,
    dir: () -> File,
    coversDir: File,
    io: CoroutineDispatcher = Dispatchers.IO,
    now: () -> Long = System::currentTimeMillis,
    pdfCovers: PdfCovers = PlatformPdfCovers,
) : PackBooks(db, NautilusSource, archiveOf, dir, coversDir, io, now, pdfCovers) {
    /** How many pack documents the last [sync] adopted; for the log. */
    @Volatile var adopted: Int = 0
        private set

    override suspend fun afterSync(listed: List<Pack>) {
        if (listed.isEmpty()) return
        val documents = dao.byOrigin(BookOrigin.PACK)
        if (documents.isEmpty()) return
        var count = 0
        for (pack in listed) {
            val prefix = "${pack.id}/${NautilusDatabase.FILES}"
            for (old in documents) {
                if (!old.sourceRef.startsWith(prefix)) continue
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
        val sessions = db.sessionDao()
        val oldFile = File(old.filePath)
        val newFile = File(new.filePath)
        var moved: Book? = null
        if (!newFile.isFile && oldFile.isFile) {
            newFile.parentFile?.mkdirs()
            if (oldFile.renameTo(newFile)) {
                val cover = old.coverPath?.let(::File)?.takeIf { it.isFile }?.let { from ->
                    val to = File(from.parentFile, "${sha1(new.id)}.${from.extension}")
                    if (from.renameTo(to)) to.absolutePath else null
                } ?: if (new.format == BookFormat.PDF) writeCover(new.id, pdfCovers.firstPage(newFile, COVER_HEIGHT_PX), "png") else null
                moved = new.copy(
                    sizeBytes = newFile.length(), coverPath = cover ?: new.coverPath, status = PackStatus.OK,
                    indexedAt = null,
                )
            }
        }
        db.withTransaction {
            val oldPosition = dao.position(old.id)
            if (oldPosition != null && dao.position(new.id) == null) dao.upsertPosition(oldPosition.copy(bookId = new.id))
            dao.deletePosition(old.id)
            dao.moveMarks(old.id, new.id)
            sessions.moveTabsToPack(old.id, new.id)
            sessions.moveHistoryToPack(old.id, new.id)
            sessions.deleteHistoryForPack(old.id)
            dao.deleteChunks(old.id)
            dao.delete(old.id)
            moved?.let { dao.upsert(it) }
        }
        if (moved == null) oldFile.delete()
        if (moved?.coverPath != old.coverPath) old.coverPath?.let { File(it).takeIf(File::isFile)?.delete() }
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
