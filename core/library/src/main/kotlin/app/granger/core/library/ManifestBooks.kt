package app.granger.core.library

import app.granger.core.zim.ZimArchive
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Android's `File` forms of the shared [BookManifest] (Plan I4b Task 8: the reader takes a path on both phones). */
fun BookManifest.verify(file: File, packSha256: String): Boolean = verify(file.path, packSha256)

fun BookManifest.items(file: File): List<ManifestItem>? = items(file.path)

fun BookManifest.collection(file: File): ManifestCollection? = collection(file.path)

fun BookManifest.keyOf(file: File, path: String): String? = keyOf(file.path, path)

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
 * The books of the installed manifest packs (Plan KB2) as shelf rows: [PackBooks] over [ManifestSource], copied out to
 * [dir] (`books/kiwix`) when first opened, read with the inspector of their format.
 */
class ManifestBooks(
    db: LibraryDatabase,
    archiveOf: (packId: String) -> ZimArchive?,
    dir: () -> File,
    coversDir: File,
    io: CoroutineDispatcher = Dispatchers.IO,
    now: () -> Long = System::currentTimeMillis,
    pdfCovers: PdfCovers = PlatformPdfCovers,
) : PackBooks(db, ManifestSource, archiveOf, dir, coversDir, io, now, pdfCovers)

/** An installed `.gbooks` and the sha256 of the pack file it must have been built from. */
data class ManifestFile(val file: File, val packSha256: String)

/**
 * Keeps [Pack.bookManifest] in step with the installed manifests (Plan KB2): a pack whose `.gbooks` checks out against
 * its file (Linked Library spec §5.2) gets its path; a pack with none that is a pure container ([BookFiles.isContainer])
 * gets [Pack.BOOK_FILES]; any other gets null. A pack that's missing or damaged keeps what it has, so its books stay
 * while its card is out. Gutenberg and nautilus packs have lists of their own and are left alone.
 */
class BookManifests(
    private val db: LibraryDatabase,
    private val archiveOf: (packId: String) -> ZimArchive?,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Whether each pack (a UUID names one snapshot) is a pure container, once read. */
    private val containers = ConcurrentHashMap<String, Boolean>()

    /** Brings every pack's [Pack.bookManifest] into line with [installed] (by library pack id); returns the rows changed. */
    suspend fun attach(installed: Map<String, ManifestFile>): Int = withContext(io) {
        var changed = 0
        for (pack in db.packDao().getAll()) {
            if (pack.status != PackStatus.OK) continue
            if (pack.copy(bookManifest = null).bookSource != null) {
                changed += db.packDao().setBookManifest(pack.id, null)
                continue
            }
            val manifest = installed[pack.id]?.takeIf { BookManifest.verify(it.file, it.packSha256) }?.file?.absolutePath
            val wanted = manifest ?: when (isContainer(pack)) {
                true -> Pack.BOOK_FILES
                false -> null
                null -> pack.bookManifest?.takeIf { it == Pack.BOOK_FILES }
            }
            changed += db.packDao().setBookManifest(pack.id, wanted)
        }
        changed
    }

    /** Null when the pack can't be read just now. */
    private fun isContainer(pack: Pack): Boolean? = containers[pack.id] ?: run {
        val counter = archiveOf(pack.id)?.metadata?.counter ?: return null
        BookFiles.isContainer(counter).also { containers[pack.id] = it }
    }
}
