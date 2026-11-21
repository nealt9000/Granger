package app.granger.core.library

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

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

/**
 * A reading edition of a manifest item (Plan BK): an EPUB on Granger's release, and [id], the catalog book (in the
 * "Granger reading editions" set) the app reads it as, so Read now and Keep work as for any Granger book.
 */
data class ManifestEdition(val url: String, val sha256: String, val bytes: Long, val title: String, val id: String = "") {
    companion object {
        /** From the manifest's `edition` JSON; null when it's missing or malformed. */
        fun parse(json: String?): ManifestEdition? {
            if (json.isNullOrBlank()) return null
            return runCatching {
                val o = Json.parseToJsonElement(json).jsonObject
                ManifestEdition(
                    url = o.text("url") ?: error("no url"),
                    sha256 = o.text("sha256").orEmpty(),
                    bytes = o.text("bytes")?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() } ?: 0L,
                    title = o.text("title").orEmpty(),
                    id = o.text("id").orEmpty(),
                )
            }.getOrNull()?.takeIf { it.url.startsWith("https://") }
        }

        /** A key's value as text (a number as written), as org.json's `optString` gave it; null when absent or null. */
        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
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
 * valid for the exact ZIM whose sha256 is its `meta.sourceSha256` (Linked Library spec §5.2). Shared by both phones
 * (Plan I4b Task 8), read through [SqlFile]; Android has `File` overloads (`ManifestBooks.kt`).
 */
object BookManifest {
    const val KIND = "gbooks"
    const val SCHEMA_VERSION = 1

    private val verified = ConcurrentCache<String, Boolean>()
    private val titles = ConcurrentCache<String, String>()
    private val collections = ConcurrentCache<String, ManifestCollection>()

    /**
     * Whether the file at [path] is a book manifest this app reads, built from the pack file with sha256 [packSha256]. A
     * sidecar's file is named by its own sha256, so the answer is kept per file.
     */
    fun verify(path: String, packSha256: String): Boolean = verified.computeIfAbsent("$path|$packSha256") {
        open(path) { db ->
            val meta = SidecarMeta.read(db)
            meta != null && meta.kind == KIND && meta.schemaVersion in 1..SCHEMA_VERSION &&
                meta.sourceSha256.isNotEmpty() && meta.sourceSha256.equals(packSha256, ignoreCase = true)
        } ?: false
    }

    /** The manifest at [path]'s items in its order; null when it can't be read. */
    fun items(path: String): List<ManifestItem>? = open(path) { db ->
        val have = db.rawQuery("PRAGMA table_info(items)", null).use { c ->
            buildSet { while (c.moveToNext()) c.text(1)?.let(::add) }
        }
        val extra = listOf("zim_offset", "sha256", "edition").joinToString("") { if (it in have) ", $it" else ", NULL" }
        db.rawQuery(
            "SELECT key, path, format, title, author, description, language, group_name, ord, bytes, part_of, work_key, " +
                "work_title, part_no$extra FROM items ORDER BY ord, key",
            null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val format = when (c.text(2)?.lowercase()) {
                        "pdf" -> BookFormat.PDF
                        "epub" -> BookFormat.EPUB
                        else -> null
                    } ?: continue
                    add(
                        ManifestItem(
                            key = c.text(0).orEmpty(), path = c.text(1).orEmpty(), format = format, title = c.text(3).orEmpty(),
                            author = c.text(4).orEmpty(), description = c.text(5).orEmpty(),
                            language = c.text(6).orEmpty(), groupName = c.text(7).orEmpty(), ord = c.getInt(8),
                            bytes = c.getLong(9), partOf = c.text(10)?.ifBlank { null },
                            workKey = c.text(11)?.ifBlank { null }, workTitle = c.text(12)?.ifBlank { null },
                            partNo = if (c.isNull(13)) null else c.getInt(13),
                            zimOffset = if (c.isNull(14)) null else c.getLong(14),
                            sha256 = c.text(15)?.ifBlank { null },
                            edition = ManifestEdition.parse(c.text(16)),
                        ),
                    )
                }
            }
        }
    }

    /** The manifest at [path]'s collection: title, noun, provider and sections; null when it can't be read. */
    fun collection(path: String): ManifestCollection? = open(path) { db ->
        val values = db.rawQuery("SELECT key, value FROM collection", null).use { c ->
            buildMap { while (c.moveToNext()) c.text(0)?.let { put(it, c.text(1).orEmpty()) } }
        }
        val groups = db.rawQuery("SELECT name FROM groups ORDER BY ord, name", null).use { c ->
            buildList { while (c.moveToNext()) c.text(0)?.takeIf { it.isNotBlank() }?.let(::add) }
        }
        ManifestCollection(
            values["title"].orEmpty(), values["noun"].orEmpty().ifBlank { "books" }, values["provider"].orEmpty(), groups,
            origin = values["origin"].orEmpty(), zimUuid = values["zimUuid"].orEmpty(), zimName = values["zimName"].orEmpty(),
        )
    }

    /**
     * The key of the item at entry [entryPath] (or whose key is [entryPath], or [entryPath] without its query) in the
     * manifest at [path]; null when none is.
     */
    fun keyOf(path: String, entryPath: String): String? = open(path) { db ->
        val bare = entryPath.substringBefore('?')
        db.rawQuery(
            "SELECT key FROM items WHERE path = ? OR key = ? OR key = ? ORDER BY path = ? DESC LIMIT 1",
            arrayOf(entryPath, entryPath, bare, entryPath),
        ).use { c -> if (c.moveToFirst()) c.text(0) else null }
    }

    /** [collection] of the manifest at [path], kept per file (a verified manifest never changes; a new one has a new path). */
    fun collectionOf(path: String): ManifestCollection? {
        collections[path]?.let { return it }
        val read = collection(path) ?: return null
        collections[path] = read
        return read
    }

    /** The collection title of [pack]'s manifest, when it has one ([Pack.bookManifest]); kept per file. */
    fun titleOf(pack: Pack): String? {
        val path = pack.bookManifest?.takeIf { it != Pack.BOOK_FILES } ?: return null
        titles[path]?.let { return it.ifBlank { null } }
        val title = collection(path)?.title ?: return null
        titles[path] = title
        return title.ifBlank { null }
    }

    private fun <T> open(path: String, read: (SqlFile) -> T): T? {
        val isFile = runCatching { SystemFileSystem.metadataOrNull(Path(path))?.isRegularFile == true }.getOrDefault(false)
        if (!isFile) return null
        val db = SqlFile.openReadOnly(path, connections = 1) ?: return null
        return try {
            read(db)
        } catch (e: Exception) {
            null
        } finally {
            db.close()
        }
    }

    /** Column [i] as text, null for SQL NULL (as Android's `Cursor.getString`). */
    private fun Rows.text(i: Int): String? = if (isNull(i)) null else getString(i)
}
