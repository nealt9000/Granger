package app.granger.core.library

import android.database.sqlite.SQLiteDatabase
import app.granger.core.zim.ZimArchive
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One item of a `.gbooks` manifest (`tools/bookpacks/schema.md`). */
data class ManifestItem(
    val key: String,
    val path: String,
    val format: BookFormat,
    val title: String,
    val author: String,
    val description: String,
    val language: String,
    val groupName: String,
    val ord: Int,
    val bytes: Long,
    val partOf: String?,
    val workKey: String?,
    val workTitle: String?,
    val partNo: Int?,
    /** Plan BK: where its bytes start in the pack file (a stored cluster), [bytes] then exact; null when unknown. */
    val zimOffset: Long? = null,
    /** Plan BK: the sha256 of those bytes, when the builder read them. */
    val sha256: String? = null,
    /** Plan BK: the reading edition Granger made of it, when there is one. */
    val edition: ManifestEdition? = null,
)

/** A reading edition of a manifest item (Plan BK): an EPUB on Granger's release. */
data class ManifestEdition(val url: String, val sha256: String, val bytes: Long, val title: String) {
    companion object {
        /** From the manifest's `edition` JSON; null when it's missing or malformed. */
        fun parse(json: String?): ManifestEdition? {
            if (json.isNullOrBlank()) return null
            return runCatching {
                val o = org.json.JSONObject(json)
                ManifestEdition(o.getString("url"), o.optString("sha256"), o.optLong("bytes"), o.optString("title"))
            }.getOrNull()?.takeIf { it.url.startsWith("https://") }
        }
    }
}

/**
 * What a book collection says about itself: its [title], what it holds ([noun], "books" or "documents"), who made it
 * ([provider]), its sections in order ([groups]), and whether its titles are only its files' names ([fromFileNames],
 * Plan KB2 Task 2.2).
 */
data class ManifestCollection(
    val title: String,
    val noun: String,
    val provider: String,
    val groups: List<String>,
    val fromFileNames: Boolean = false,
    /** Plan BK: "nautilus" when its keys are a nautilus pack's `fp`s (phone ids `book:nautilus:…`); blank otherwise. */
    val origin: String = "",
    /** Plan BK: the UUID of the ZIM it was built from, checked before reading books from the pack file by range. */
    val zimUuid: String = "",
    /** The pack's ZIM name, from the manifest. */
    val zimName: String = "",
) {
    /** Whether it counts documents rather than books. */
    val documents: Boolean get() = noun == NOUN_DOCUMENTS

    companion object {
        const val NOUN_DOCUMENTS = "documents"
    }
}

/**
 * A Kiwix pack's book manifest, the `.gbooks` sidecar (Plan KB2, `tools/bookpacks/schema.md`): a read-only SQLite file
 * valid for the exact ZIM whose sha256 is its `meta.sourceSha256` (Linked Library spec §5.2).
 */
object BookManifest {
    const val KIND = "gbooks"
    const val SCHEMA_VERSION = 1

    private val verified = ConcurrentHashMap<String, Boolean>()
    private val titles = ConcurrentHashMap<String, String>()

    /**
     * Whether [file] is a book manifest this app reads, built from the pack file with sha256 [packSha256]. A sidecar's
     * file is named by its own sha256, so the answer is kept per file.
     */
    fun verify(file: File, packSha256: String): Boolean = verified.getOrPut("${file.path}|$packSha256") {
        open(file) { db ->
            val meta = SidecarMeta.read(db)
            meta != null && meta.kind == KIND && meta.schemaVersion in 1..SCHEMA_VERSION &&
                meta.sourceSha256.isNotEmpty() && meta.sourceSha256.equals(packSha256, ignoreCase = true)
        } ?: false
    }

    /** [file]'s items in its order; null when it can't be read. */
    fun items(file: File): List<ManifestItem>? = open(file) { db ->
        val have = db.rawQuery("PRAGMA table_info(items)", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(1)) }
        }
        val extra = listOf("zim_offset", "sha256", "edition").joinToString("") { if (it in have) ", $it" else ", NULL" }
        db.rawQuery(
            "SELECT key, path, format, title, author, description, language, group_name, ord, bytes, part_of, work_key, " +
                "work_title, part_no$extra FROM items ORDER BY ord, key",
            null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val format = when (c.getString(2)?.lowercase()) {
                        "pdf" -> BookFormat.PDF
                        "epub" -> BookFormat.EPUB
                        else -> null
                    } ?: continue
                    add(
                        ManifestItem(
                            key = c.getString(0), path = c.getString(1), format = format, title = c.getString(3).orEmpty(),
                            author = c.getString(4).orEmpty(), description = c.getString(5).orEmpty(),
                            language = c.getString(6).orEmpty(), groupName = c.getString(7).orEmpty(), ord = c.getInt(8),
                            bytes = c.getLong(9), partOf = c.getString(10)?.ifBlank { null },
                            workKey = c.getString(11)?.ifBlank { null }, workTitle = c.getString(12)?.ifBlank { null },
                            partNo = if (c.isNull(13)) null else c.getInt(13),
                            zimOffset = if (c.isNull(14)) null else c.getLong(14),
                            sha256 = c.getString(15)?.ifBlank { null },
                            edition = ManifestEdition.parse(c.getString(16)),
                        ),
                    )
                }
            }
        }
    }

    /** [file]'s collection: title, noun, provider and sections; null when it can't be read. */
    fun collection(file: File): ManifestCollection? = open(file) { db ->
        val values = db.rawQuery("SELECT key, value FROM collection", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1).orEmpty()) }
        }
        val groups = db.rawQuery("SELECT name FROM groups ORDER BY ord, name", null).use { c ->
            buildList { while (c.moveToNext()) c.getString(0)?.takeIf { it.isNotBlank() }?.let(::add) }
        }
        ManifestCollection(
            values["title"].orEmpty(), values["noun"].orEmpty().ifBlank { "books" }, values["provider"].orEmpty(), groups,
            origin = values["origin"].orEmpty(), zimUuid = values["zimUuid"].orEmpty(), zimName = values["zimName"].orEmpty(),
        )
    }

    /** The key of the item at entry [path] (or whose key is [path], or [path] without its query); null when none is. */
    fun keyOf(file: File, path: String): String? = open(file) { db ->
        val bare = path.substringBefore('?')
        db.rawQuery(
            "SELECT key FROM items WHERE path = ? OR key = ? OR key = ? ORDER BY path = ? DESC LIMIT 1",
            arrayOf(path, path, bare, path),
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }

    /** [collection] of the manifest at [path], kept per file (a verified manifest never changes; a new one has a new path). */
    fun collectionOf(path: String): ManifestCollection? {
        collections[path]?.let { return it }
        val read = collection(File(path)) ?: return null
        collections[path] = read
        return read
    }

    private val collections = ConcurrentHashMap<String, ManifestCollection>()

    /** The collection title of [pack]'s manifest, when it has one ([Pack.bookManifest]); kept per file. */
    fun titleOf(pack: Pack): String? {
        val path = pack.bookManifest?.takeIf { it != Pack.BOOK_FILES } ?: return null
        titles[path]?.let { return it.ifBlank { null } }
        val title = collection(File(path))?.title ?: return null
        titles[path] = title
        return title.ifBlank { null }
    }

    private fun <T> open(file: File, read: (SQLiteDatabase) -> T): T? {
        if (!file.isFile) return null
        val db = runCatching { SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY) }.getOrNull() ?: return null
        return try {
            read(db)
        } catch (e: Exception) {
            android.util.Log.w("Granger", "Couldn't read the book manifest ${file.name}", e)
            null
        } finally {
            db.close()
        }
    }
}

/**
 * A pack with no manifest that is plainly a container of books (Plan KB2 Task 2.2): listed by its files, each titled by
 * its file name.
 */
object BookFiles {
    /** What a pack's books can be. */
    val MIMES = setOf("application/pdf", "application/epub+zip")

    /**
     * The audit's rule for a pure container, from a ZIM's `Counter` metadata: at least 3 PDFs and EPUBs, and no more than
     * 10 HTML pages, which only wrap the files. Deliberately not looser: the World Factbook (839 PDFs, 830 pages) is a
     * website.
     */
    fun isContainer(counter: String): Boolean {
        val counts = parseCounter(counter)
        val docs = MIMES.sumOf { counts[it] ?: 0 }
        val html = counts.filterKeys { it.startsWith("text/html") }.values.sum()
        return docs >= 3 && html <= 10
    }

    /** `text/html=12;image/png=3` as MIME type to count; a malformed part is skipped. */
    fun parseCounter(counter: String): Map<String, Int> = buildMap {
        for (part in counter.split(';')) {
            val at = part.lastIndexOf('=')
            if (at <= 0) continue
            val mime = part.substring(0, at).trim()
            part.substring(at + 1).trim().toIntOrNull()?.let { put(mime, (get(mime) ?: 0) + it) }
        }
    }

    /**
     * A title from an entry's path: its file name without query or extension, `%xx` decoded, underscores as spaces
     * (`files/A_Treatise%20on_Hedges.pdf` → "A Treatise on Hedges").
     */
    fun titleOf(path: String): String {
        val name = path.substringBefore('?').substringAfterLast('/')
        val stem = name.substringBeforeLast('.', name).ifBlank { name }
        return percentDecode(stem).replace('_', ' ').replace(Regex("\\s+"), " ").trim().ifBlank { name }
    }

    /** `%xx` sequences as UTF-8; a `+` stays a `+` (a path, not a form), and a broken sequence is kept as it is. */
    private fun percentDecode(text: String): String {
        if ('%' !in text) return text
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '%' && i + 2 < text.length) {
                val byte = text.substring(i + 1, i + 3).toIntOrNull(16)
                if (byte != null) {
                    out.write(byte)
                    i += 3
                    continue
                }
            }
            out.write(ch.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return out.toString(Charsets.UTF_8.name())
    }

    fun formatOf(mime: String): BookFormat? = when (mime) {
        "application/pdf" -> BookFormat.PDF
        "application/epub+zip" -> BookFormat.EPUB
        else -> null
    }
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

    override fun list(pack: Pack, archive: ZimArchive): List<PackItem>? {
        val manifest = pack.bookManifest ?: return null
        if (manifest == Pack.BOOK_FILES) {
            val items = ArrayList<PackItem>()
            val walked = archive.list(BookFiles.MIMES) { e ->
                val format = BookFiles.formatOf(e.mimeType)
                if (format != null) {
                    items += PackItem(
                        ref = "${pack.name}/${e.path}", path = e.path, title = BookFiles.titleOf(e.path), author = "",
                        format = format, sizeBytes = e.sizeBytes, ord = items.size,
                    )
                }
                true
            }
            return items.takeIf { walked }
        }
        val items = BookManifest.items(File(manifest)) ?: return null
        // A whole file with parts is marked as the head of its own work, so its parts fold under it.
        val wholes = items.mapNotNullTo(HashSet()) { it.partOf }
        return items.map { item ->
            PackItem(
                ref = "${pack.name}/${item.key}",
                path = item.path,
                title = item.title.ifBlank { BookFiles.titleOf(item.path) },
                author = item.author,
                format = item.format,
                sizeBytes = item.bytes,
                description = item.description,
                language = item.language.ifBlank { null },
                groupName = item.groupName,
                ord = item.ord,
                workKey = item.partOf ?: item.workKey ?: item.key.takeIf { it in wholes },
                workTitle = item.workTitle,
                partNo = item.partNo,
            )
        }
    }

    override fun refOf(pack: Pack, path: String): String? {
        val manifest = pack.bookManifest ?: return null
        val p = path.removePrefix("/")
        if (manifest == Pack.BOOK_FILES) {
            val name = p.substringBefore('?')
            return p.takeIf { name.endsWith(".pdf", ignoreCase = true) || name.endsWith(".epub", ignoreCase = true) }
                ?.let { "${pack.name}/$it" }
        }
        return BookManifest.keyOf(File(manifest), p)?.let { "${pack.name}/$it" }
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
