package app.granger.online

import app.granger.core.catalog.PackEntry
import app.granger.core.catalog.SidecarEntry
import app.granger.core.library.BookFormat
import app.granger.core.library.BookManifest
import app.granger.core.library.ConcurrentCache
import app.granger.core.library.FileHashes
import app.granger.core.library.ManifestItem
import app.granger.core.library.SavedForNow
import app.granger.core.text.hex
import app.granger.core.text.sha1
import app.granger.screens.ioDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** One hop's answer: its status, and where it redirects to (resolved against the request's URL), if anywhere. */
class BookHop(val code: Int, val location: String?)

/** A range request's answer before its body: its status and its length (-1 when it doesn't say). */
class RangeAnswer(val code: Int, val contentLength: Long)

/**
 * The requests the online bookshelf sends (Plan I5 Task 7), each phone its own way: Android's app `OkHttpClient`
 * (its Local only gate, pool and User-Agent), the iPhone's Ktor on Darwin. None follows redirects but [fetch]; none
 * throws but cancellation (no answer is null or false).
 */
interface PackBookHttp {
    /** GET [url], following redirects, its body written to the file [to]; false unless it answered 2xx and all was written. */
    suspend fun fetch(url: String, to: String): Boolean

    /** HEAD [url] without following a redirect; null when there's no answer. */
    suspend fun head(url: String): BookHop?

    /**
     * GET bytes [first]..[last] of [url] without following a redirect. [start] is shown the answer before its body; when
     * it says yes, the body comes to [body] piece by piece (a buffer and how much of it is filled) until [body] says
     * stop or it ends. The answer, or null when there's none.
     */
    suspend fun range(url: String, first: Long, last: Long, start: (RangeAnswer) -> Boolean, body: suspend (ByteArray, Int) -> Boolean): RangeAnswer?
}

/**
 * Reads book packs' books one at a time from the pack file on Kiwix's mirrors (Plan BK; spike 2026-11-20). Shared since
 * Plan I5 Task 7: Android's `PackBookReads` keeps its constructor over this, and the iPhone reads the same way. Every
 * book in a book pack sits in a stored cluster, so it is one contiguous byte range of the ZIM, and the pack's `.gbooks`
 * says where (`zim_offset`, `bytes`).
 *
 * - **The list** is the pack's `.gbooks` sidecar, fetched from its catalog URL, checked against the catalog's sha256 and
 *   against the pack's own sha256 ([BookManifest.verify]), and kept in Saved for now (kind BOOK).
 * - **A book** is one `Range` GET on the pack's URL, after a check (once per pack a session) that the file there is the
 *   one the list was built from: its header UUID. The bytes are checked by length, by their first bytes (`%PDF`, `PK`)
 *   and by sha256 when the list has one, then go into Saved for now under [bookKey].
 * - `download.kiwix.org` redirects to a mirror: the mirror's address is remembered for the session, and asked again
 *   once when a read fails.
 *
 * Every request goes through [http], so Local only refuses them. Blocking work runs on [io].
 */
class RemoteBookReads(
    private val http: PackBookHttp,
    private val saved: SavedForNow,
    /** Where a book is written while it comes down. */
    private val partDir: String,
    /** Keeps Saved for now under its cap after something is added. */
    private val trim: () -> Unit = {},
    private val io: CoroutineDispatcher = ioDispatcher,
) {
    private val resolved = ConcurrentCache<String, String>()
    private val checkedUuid = ConcurrentCache<String, Boolean>()
    private val lists = ConcurrentCache<String, RemoteCollection>()

    /** [pack]'s collection, from Saved for now or fetched; null when it has no list, or it can't be had or checked. */
    suspend fun collection(pack: PackEntry, canFetch: Boolean): RemoteCollection? = withContext(io) {
        val side = pack.sidecars.firstOrNull { it.kind == SidecarEntry.GBOOKS } ?: return@withContext null
        lists[side.sha256]?.let { return@withContext it }
        val key = listKey(side)
        val file = saved.file(key) ?: run {
            if (!canFetch) return@withContext null
            SystemFileSystem.createDirectories(Path(partDir))
            val tmp = "$partDir/list-${side.sha256.take(16)}.part"
            val ok = http.fetch(side.url, tmp) && runCatching { FileHashes.sha256(tmp).equals(side.sha256, ignoreCase = true) }.getOrDefault(false)
            if (!ok || !saved.putFile(key, BOOK_KIND, tmp, GBOOKS_MIME)) {
                delete(tmp)
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
    ): String? = withContext(io) {
        val key = bookKey(remote.bookId(item))
        saved.file(key)?.let { return@withContext it }
        val offset = item.zimOffset ?: return@withContext null
        if (!canFetch || item.bytes <= 0) return@withContext null
        val url = remote.pack.urls.firstOrNull() ?: return@withContext null
        if (!sameFile(url, remote.collection.zimUuid.ifBlank { remote.pack.zimUuid })) return@withContext null
        SystemFileSystem.createDirectories(Path(partDir))
        val tmp = "$partDir/book-${sha1(key.encodeToByteArray()).hex()}.part"
        try {
            var got = read(url, offset, item.bytes, tmp, progress, retried = false)
            if (!got) got = read(url, offset, item.bytes, tmp, progress, retried = true)
            if (!got || !checks(tmp, item)) return@withContext null
            if (!saved.putFile(key, BOOK_KIND, tmp, mimeOf(item.format))) return@withContext null
            trim()
            saved.file(key)
        } finally {
            delete(tmp)
        }
    }

    /** Whether the file at [url] is the pack the list was built from (its header's UUID); asked once a session. */
    private suspend fun sameFile(url: String, uuid: String): Boolean {
        if (uuid.isBlank()) return true   // a list built before Plan BK: the length and first bytes still check
        checkedUuid[url]?.let { return it }
        val header = range(url, 8, 16, retried = false) ?: range(url, 8, 16, retried = true) ?: return false
        val same = header.hex().equals(uuid, ignoreCase = true)
        checkedUuid[url] = same
        return same
    }

    private suspend fun read(url: String, offset: Long, length: Long, to: String, progress: (Long, Long) -> Unit, retried: Boolean): Boolean {
        if (retried) resolved.remove(url)
        val target = resolved[url] ?: resolve(url) ?: return false
        var done = 0L
        var over = false
        val answer = try {
            SystemFileSystem.sink(Path(to)).buffered().use { out ->
                http.range(target, offset, offset + length - 1, start = { a -> a.code == 206 && (a.contentLength < 0 || a.contentLength == length) }) { buf, n ->
                    currentCoroutineContext().ensureActive()
                    out.write(buf, 0, n)
                    done += n
                    if (done > length) {
                        over = true
                        false
                    } else {
                        progress(done, length)
                        true
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        } ?: return false
        if (answer.code != 206 || (answer.contentLength >= 0 && answer.contentLength != length)) return false
        return !over && done == length
    }

    private suspend fun range(url: String, offset: Long, length: Int, retried: Boolean): ByteArray? {
        if (retried) resolved.remove(url)
        val target = resolved[url] ?: resolve(url) ?: return null
        val got = ArrayList<Byte>(length)
        val answer = http.range(target, offset, offset + length - 1, start = { it.code == 206 }) { buf, n ->
            for (i in 0 until n) got += buf[i]
            got.size <= length
        } ?: return null
        return if (answer.code == 206 && got.size == length) got.toByteArray() else null
    }

    /** Where [url] redirects (download.kiwix.org to a mirror), remembered for the session. */
    private suspend fun resolve(url: String): String? {
        var at = url
        repeat(MAX_HOPS) {
            val hop = http.head(at) ?: return null
            when {
                hop.code in REDIRECTS && hop.location != null -> at = hop.location
                hop.code in 200..299 -> return at.also { resolved[url] = it }
                else -> return null
            }
        }
        return null
    }

    private fun delete(path: String) {
        runCatching { SystemFileSystem.delete(Path(path), mustExist = false) }
    }

    companion object {
        private const val MAX_HOPS = 4
        private const val GBOOKS_MIME = "application/vnd.sqlite3"
        /** Saved for now's kind for books (Android's `SavedStore.Kind.BOOK`). */
        const val BOOK_KIND = "BOOK"
        /** The statuses OkHttp's `Response.isRedirect` takes as redirects. */
        private val REDIRECTS = setOf(300, 301, 302, 303, 307, 308)

        /** A pack's list in Saved for now, by its checksum (a new list has a new one). */
        fun listKey(side: SidecarEntry) = "gbooks:${side.sha256}"

        /** A book read online or opened without keeping, in Saved for now (Android's `SavedStore.bookKey`). */
        fun bookKey(bookId: String) = "book:$bookId"

        fun mimeOf(format: BookFormat) = if (format == BookFormat.EPUB) "application/epub+zip" else "application/pdf"

        private val PDF = "%PDF".encodeToByteArray()
        private val ZIP = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

        /** The book's bytes are what the list says: its length, its first bytes, and its sha256 when the list has one. */
        fun checks(file: String, item: ManifestItem): Boolean {
            if ((SystemFileSystem.metadataOrNull(Path(file))?.size ?: -1L) != item.bytes) return false
            val head = runCatching { SystemFileSystem.source(Path(file)).buffered().use { s -> ByteArray(4).also { b -> if (s.readAtMostTo(b) != 4) return false } } }
                .getOrNull() ?: return false
            val magic = if (item.format == BookFormat.EPUB) ZIP else PDF
            if (!head.contentEquals(magic)) return false
            return item.sha256 == null || runCatching { FileHashes.sha256(file).equals(item.sha256, ignoreCase = true) }.getOrDefault(false)
        }
    }
}
