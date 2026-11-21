package app.granger.online

import app.granger.core.catalog.PackEntry
import app.granger.core.library.BookFormat
import app.granger.core.library.ManifestCollection
import app.granger.core.library.ManifestItem
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** [RemoteCollection] (shared since Plan I5) on Android's `File`. */
fun RemoteCollection(pack: PackEntry, file: File, collection: ManifestCollection, items: List<ManifestItem>): RemoteCollection =
    RemoteCollection(pack, file.path, collection, items)

/**
 * Book packs' books read one at a time from the pack file on Kiwix's mirrors (Plan BK). The rules are shared since
 * Plan I5 Task 7 ([RemoteBookReads], which the iPhone runs on Ktor); this keeps Android's constructor and `File`s, and
 * sends every request through the app's one [http] client exactly as before, so Local only refuses them.
 */
class PackBookReads(
    http: OkHttpClient,
    saved: SavedStore,
    /** Where a book is written while it comes down. */
    partDir: File,
    /** Keeps Saved for now under its cap after something is added. */
    trim: () -> Unit = {},
) {
    private val shared = RemoteBookReads(OkHttpPackBookHttp(http), saved.shared, partDir.path, trim, Dispatchers.IO)

    /** [pack]'s collection, from Saved for now or fetched; null when it has no list, or it can't be had or checked. */
    suspend fun collection(pack: PackEntry, canFetch: Boolean): RemoteCollection? = shared.collection(pack, canFetch)

    /** Whether [item] can be read online: the list says where it is. */
    fun readable(item: ManifestItem): Boolean = shared.readable(item)

    /** [item]'s file from Saved for now, or read from the pack by range into it; see [RemoteBookReads.book]. */
    suspend fun book(
        remote: RemoteCollection,
        item: ManifestItem,
        canFetch: Boolean,
        progress: (Long, Long) -> Unit = { _, _ -> },
    ): File? = shared.book(remote, item, canFetch, progress)?.let(::File)

    companion object {
        fun listKey(side: app.granger.core.catalog.SidecarEntry) = RemoteBookReads.listKey(side)

        fun mimeOf(format: BookFormat) = RemoteBookReads.mimeOf(format)
    }
}

/**
 * Android's [PackBookHttp]: the app's [http] client (its gate, pool and User-Agent) for the list, and the same client
 * without redirects for the mirror's HEAD and range reads, as Plan BK sent them. Blocking calls run on IO.
 */
class OkHttpPackBookHttp(private val http: OkHttpClient) : PackBookHttp {
    private val noRedirects: OkHttpClient by lazy { http.newBuilder().followRedirects(false).followSslRedirects(false).build() }

    override suspend fun fetch(url: String, to: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return@use false
                File(to).outputStream().use { out -> r.body.byteStream().copyTo(out) }
                true
            }
        }.getOrDefault(false)
    }

    override suspend fun head(url: String): BookHop? = withContext(Dispatchers.IO) {
        runCatching {
            noRedirects.newCall(Request.Builder().url(url).head().build()).execute().use { r ->
                BookHop(r.code, r.header("Location")?.let { r.request.url.resolve(it)?.toString() })
            }
        }.getOrNull()
    }

    override suspend fun range(
        url: String, first: Long, last: Long, start: (RangeAnswer) -> Boolean, body: suspend (ByteArray, Int) -> Boolean,
    ): RangeAnswer? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Range", "bytes=$first-$last").build()
        try {
            noRedirects.newCall(request).execute().use { r ->
                val answer = RangeAnswer(r.code, r.body.contentLength())
                if (!start(answer)) return@use answer
                val input = r.body.byteStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0 || (n > 0 && !body(buf, n))) break
                }
                answer
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}
