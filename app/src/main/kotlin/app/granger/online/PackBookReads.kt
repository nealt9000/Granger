package app.granger.online

import app.granger.core.catalog.PackEntry
import app.granger.core.catalog.SidecarEntry
import app.granger.core.library.BookFormat
import app.granger.core.library.BookIds
import app.granger.core.library.BookManifest
import app.granger.core.library.BookOrigin
import app.granger.core.library.ManifestCollection
import app.granger.core.library.ManifestItem
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * A book pack's collection read online, before (or without) the pack (Plan BK): its `.gbooks` list, checked against the
 * catalog, and the items in it.
 */
class RemoteCollection(val pack: PackEntry, val file: File, val collection: ManifestCollection, val items: List<ManifestItem>) {
    /** The origin of its books' rows: the ids an installed pack would give them. */
    val origin: BookOrigin get() = if (collection.origin == NAUTILUS) BookOrigin.NAUTILUS else BookOrigin.KIWIX

    /** The library id of [item]: `book:nautilus:<pack name>/<fp>` or `book:kiwix:<pack name>/<key>`. */
    fun bookId(item: ManifestItem): String = BookIds.of(origin, ref(item))

    /** [item]'s ref within its origin. */
    fun ref(item: ManifestItem): String = "${pack.zimName}/${item.key}"

    /** The item whose library id is [bookId], or null. */
    fun itemOf(bookId: String): ManifestItem? = items.firstOrNull { bookId(it) == bookId }

    companion object {
        const val NAUTILUS = "nautilus"
    }
}

/**
 * Reads book packs' books one at a time from the pack file on Kiwix's mirrors (Plan BK; spike 2026-11-20): every book
 * in a book pack sits in a stored cluster, so it is one contiguous byte range of the ZIM, and the pack's `.gbooks` says
 * where (`zim_offset`, `bytes`).
 *
 * - **The list** is the pack's `.gbooks` sidecar, fetched from its catalog URL, checked against the catalog's sha256 and
 *   against the pack's own sha256 ([BookManifest.verify]), and kept in Saved for now (kind BOOK).
 * - **A book** is one `Range` GET on the pack's URL, after a check (once per pack a session) that the file there is the
 *   one the list was built from: its header UUID. The bytes are checked by length, by their first bytes (`%PDF`, `PK`)
 *   and by sha256 when the list has one, then go into Saved for now under [SavedStore.bookKey].
 * - `download.kiwix.org` redirects to a mirror: the mirror's address is remembered for the session, and asked again
 *   once when a read fails.
 *
 * Every request goes through [http], the app's one client, so Local only refuses them. Blocking work runs on IO.
 */
class PackBookReads(
    private val http: OkHttpClient,
    private val saved: SavedStore,
    /** Where a book is written while it comes down. */
    private val partDir: File,
    /** Keeps Saved for now under its cap after something is added. */
    private val trim: () -> Unit = {},
) {
    private val resolved = ConcurrentHashMap<String, String>()
    private val checkedUuid = ConcurrentHashMap<String, Boolean>()
    private val lists = ConcurrentHashMap<String, RemoteCollection>()

    /** [pack]'s collection, from Saved for now or fetched; null when it has no list, or it can't be had or checked. */
    suspend fun collection(pack: PackEntry, canFetch: Boolean): RemoteCollection? = withContext(Dispatchers.IO) {
        val side = pack.sidecars.firstOrNull { it.kind == SidecarEntry.GBOOKS } ?: return@withContext null
        lists[side.sha256]?.let { return@withContext it }
        val key = listKey(side)
        val file = saved.file(key) ?: run {
            if (!canFetch) return@withContext null
            val tmp = File(partDir, "list-${side.sha256.take(16)}.part").apply { parentFile?.mkdirs() }
            val ok = runCatching {
                http.newCall(Request.Builder().url(side.url).build()).execute().use { r ->
                    if (!r.isSuccessful) return@use false
                    tmp.outputStream().use { out -> r.body.byteStream().copyTo(out) }
                    sha256(tmp).equals(side.sha256, ignoreCase = true)
                }
            }.getOrDefault(false)
            if (!ok || !saved.putFile(key, SavedStore.Kind.BOOK, tmp, GBOOKS_MIME)) {
                tmp.delete()
                return@withContext null
            }
            trim()
            saved.file(key) ?: return@withContext null
        }
        if (!BookManifest.verify(file, pack.sha256)) {
            saved.remove(key)
            return@withContext null
        }
        val collection = BookManifest.collection(file) ?: return@withContext null
        val items = BookManifest.items(file) ?: return@withContext null
        RemoteCollection(pack, file, collection, items).also { lists[side.sha256] = it }
    }

    /** Whether [item] can be read online: the list says where it is. */
    fun readable(item: ManifestItem): Boolean = item.zimOffset != null && item.bytes > 0

    /**
     * [item]'s file from Saved for now, or read from the pack by range into it; null when it can't be had, or the bytes
     * don't check out. [progress] gets (bytes so far, of bytes). Cancelling the caller stops the read and leaves nothing.
     */
    suspend fun book(
        remote: RemoteCollection,
        item: ManifestItem,
        canFetch: Boolean,
        progress: (Long, Long) -> Unit = { _, _ -> },
    ): File? = withContext(Dispatchers.IO) {
        val key = SavedStore.bookKey(remote.bookId(item))
        saved.file(key)?.let { return@withContext it }
        val offset = item.zimOffset ?: return@withContext null
        if (!canFetch || item.bytes <= 0) return@withContext null
        val url = remote.pack.urls.firstOrNull() ?: return@withContext null
        if (!sameFile(url, remote.collection.zimUuid.ifBlank { remote.pack.zimUuid })) return@withContext null
        val tmp = File(partDir, "book-${SavedStore.sha1(key)}.part").apply { parentFile?.mkdirs() }
        try {
            var got = read(url, offset, item.bytes, tmp, progress, retried = false)
            if (!got) got = read(url, offset, item.bytes, tmp, progress, retried = true)
            if (!got || !checks(tmp, item)) return@withContext null
            if (!saved.putFile(key, SavedStore.Kind.BOOK, tmp, mimeOf(item.format))) return@withContext null
            trim()
            saved.file(key)
        } finally {
            tmp.delete()
        }
    }

    /** Whether the file at [url] is the pack the list was built from (its header's UUID); asked once a session. */
    private fun sameFile(url: String, uuid: String): Boolean {
        if (uuid.isBlank()) return true   // a list built before Plan BK: the length and first bytes still check
        checkedUuid[url]?.let { return it }
        val header = range(url, 8, 16, retried = false) ?: range(url, 8, 16, retried = true) ?: return false
        val same = header.joinToString("") { "%02x".format(it) }.equals(uuid, ignoreCase = true)
        checkedUuid[url] = same
        return same
    }

    private suspend fun read(url: String, offset: Long, length: Long, to: File, progress: (Long, Long) -> Unit, retried: Boolean): Boolean {
        if (retried) resolved.remove(url)
        val target = resolved[url] ?: resolve(url) ?: return false
        val request = Request.Builder().url(target).header("Range", "bytes=$offset-${offset + length - 1}").build()
        return runCatching {
            noRedirects.newCall(request).execute().use { r ->
                if (r.code != 206 || r.body.contentLength().let { it >= 0 && it != length }) return@use false
                var done = 0L
                to.outputStream().use { out ->
                    val input = r.body.byteStream()
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done > length) return@use false
                        progress(done, length)
                    }
                }
                done == length
            }
        }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it else false }
    }

    private fun range(url: String, offset: Long, length: Int, retried: Boolean): ByteArray? {
        if (retried) resolved.remove(url)
        val target = resolved[url] ?: resolve(url) ?: return null
        val request = Request.Builder().url(target).header("Range", "bytes=$offset-${offset + length - 1}").build()
        return runCatching {
            noRedirects.newCall(request).execute().use { r -> if (r.code == 206) r.body.bytes().takeIf { it.size == length } else null }
        }.getOrNull()
    }

    /** Where [url] redirects (download.kiwix.org to a mirror), remembered for the session. */
    private fun resolve(url: String): String? = runCatching {
        var at = url
        repeat(MAX_HOPS) {
            val next = noRedirects.newCall(Request.Builder().url(at).head().build()).execute().use { r ->
                val location = r.header("Location")
                when {
                    r.isRedirect && location != null -> r.request.url.resolve(location)?.toString()
                    r.isSuccessful -> return@runCatching at
                    else -> return@runCatching null
                }
            } ?: return@runCatching null
            at = next
        }
        null
    }.getOrNull()?.also { resolved[url] = it }

    private val noRedirects: OkHttpClient by lazy { http.newBuilder().followRedirects(false).followSslRedirects(false).build() }

    companion object {
        private const val MAX_HOPS = 4
        private const val GBOOKS_MIME = "application/vnd.sqlite3"

        /** A pack's list in Saved for now, by its checksum (a new list has a new one). */
        fun listKey(side: SidecarEntry) = "gbooks:${side.sha256}"

        fun mimeOf(format: BookFormat) = if (format == BookFormat.EPUB) "application/epub+zip" else "application/pdf"

        private val PDF = "%PDF".toByteArray()
        private val ZIP = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

        /** The book's bytes are what the list says: its length, its first bytes, and its sha256 when the list has one. */
        internal fun checks(file: File, item: ManifestItem): Boolean {
            if (file.length() != item.bytes) return false
            val head = ByteArray(4)
            val n = file.inputStream().use { it.read(head) }
            val magic = if (item.format == BookFormat.EPUB) ZIP else PDF
            if (n != 4 || !head.contentEquals(magic)) return false
            return item.sha256 == null || sha256(file).equals(item.sha256, ignoreCase = true)
        }

        internal fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
