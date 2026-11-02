package app.granger.core.zim

/** What the reader's WebView gets for one request. */
sealed interface ZimResponse {
    class Content(val mimeType: String, val encoding: String?, val bytes: ByteArray) : ZimResponse

    /**
     * A large stored entry (audio, video, a big image) streamed from the `.zim` file, never whole in memory. [status]
     * is 200, 206 for a byte range the WebView asked for (so media can seek), or 416 for a range past the end;
     * [headers] carry the length and range. [open] gives the bytes to send.
     */
    class Stream(
        val mimeType: String,
        val encoding: String?,
        val status: Int,
        val headers: Map<String, String>,
        private val range: ZimRange,
        val from: Long,
        val count: Long,
    ) : ZimResponse {
        fun open(): java.io.InputStream = if (count > 0) range.open(from, count) else java.io.ByteArrayInputStream(ByteArray(0))
        val reason: String get() = when (status) { 206 -> "Partial Content"; 416 -> "Range Not Satisfiable"; else -> "OK" }
    }
    data object NotFound : ZimResponse
    /** Anything that is not a reader URL. Answered locally so it never reaches the network. */
    data object Blocked : ZimResponse
}

/** What to do when the reader taps a link. */
sealed interface LinkAction {
    /** Let the WebView load it. */
    data object Load : LinkAction
    /** The entry is a redirect; load the canonical URL so relative links on the target page resolve. */
    data class Redirect(val url: String) : LinkAction
    /** Leave the app (system browser, mail, maps). */
    data class External(val url: String) : LinkAction
    /** A document the WebView can't show (a PDF, Plan P2; an EPUB, Plan GB): the e-book reader opens it. */
    data class OpenDocument(val packId: String, val path: String) : LinkAction
    /** javascript:, file:, intent: and anything else unsafe. */
    data object Ignore : LinkAction
}

/**
 * [archiveFor] may block (it can open the file); [cachedArchiveFor] must not, because link taps are
 * decided on the main thread.
 */
class ZimResolver(
    private val archiveFor: (packId: String) -> ZimArchive?,
    private val cachedArchiveFor: (packId: String) -> ZimArchive? = archiveFor,
) {
    /** [rangeHeader] is the request's `Range` header, if any; only a streamed entry answers it. */
    fun response(url: String, rangeHeader: String? = null): ZimResponse {
        val zim = ZimUrls.parse(url) ?: return ZimResponse.Blocked
        val archive = archiveFor(zim.packId) ?: return ZimResponse.NotFound
        // HTML is read whole: the reader patches it, and pages are small.
        archive.storedRange(zim.path)
            ?.takeIf { it.length >= STREAM_MIN_BYTES && splitMimeType(it.mimeType).first != "text/html" }
            ?.let { return stream(it, rangeHeader) }
        val item = archive.read(zim.path) ?: return ZimResponse.NotFound
        val (mimeType, encoding) = splitMimeType(item.mimeType)
        return ZimResponse.Content(mimeType, encoding, item.bytes)
    }

    private fun stream(range: ZimRange, rangeHeader: String?): ZimResponse.Stream {
        val (mimeType, encoding) = splitMimeType(range.mimeType)
        val length = range.length
        val asked = rangeHeader?.let { byteRange(it, length) }
        return when {
            asked == null -> ZimResponse.Stream(
                mimeType, encoding, 200, mapOf("Accept-Ranges" to "bytes", "Content-Length" to "$length"), range, 0L, length,
            )
            asked.first > asked.last -> ZimResponse.Stream(
                mimeType, encoding, 416, mapOf("Accept-Ranges" to "bytes", "Content-Range" to "bytes */$length"), range, 0L, 0L,
            )
            else -> {
                val count = asked.last - asked.first + 1
                ZimResponse.Stream(
                    mimeType, encoding, 206,
                    mapOf(
                        "Accept-Ranges" to "bytes",
                        "Content-Range" to "bytes ${asked.first}-${asked.last}/$length",
                        "Content-Length" to "$count",
                    ),
                    range, asked.first, count,
                )
            }
        }
    }

    fun linkAction(url: String): LinkAction {
        val zim = ZimUrls.parse(url)
            ?: return if (url.substringBefore(':', "").lowercase() in EXTERNAL_SCHEMES) {
                LinkAction.External(url)
            } else {
                LinkAction.Ignore
            }
        // Runs on the main thread: only an already-open archive is consulted. On a miss the WebView
        // loads the URL and shouldInterceptRequest serves it from a background thread.
        val archive = cachedArchiveFor(zim.packId) ?: return LinkAction.Load
        val target = archive.resolvePath(zim.path) ?: return LinkAction.Load
        val mimeType = archive.entry(target)?.mimeType
        if (isPdf(mimeType) || isEpub(mimeType)) return LinkAction.OpenDocument(zim.packId, target)
        return if (target == zim.path) {
            LinkAction.Load
        } else {
            LinkAction.Redirect(ZimUrls.forEntry(zim.packId, target, zim.fragment))
        }
    }

    companion object {
        private val EXTERNAL_SCHEMES = setOf("http", "https", "mailto", "tel", "geo")
        /** Smaller entries are read whole, as before: the WebView takes a byte array fastest. */
        const val STREAM_MIN_BYTES = 256L * 1024

        /**
         * One `bytes=` range of an entry of [length] bytes: `a-b`, `a-` or `-n` (the last n). Null to answer with the
         * whole entry (no header, or one this doesn't take, such as several ranges); an empty range (first > last)
         * when it starts past the end.
         */
        fun byteRange(header: String, length: Long): LongRange? {
            val spec = header.trim().takeIf { it.startsWith("bytes=", ignoreCase = true) }?.substring(6)?.trim() ?: return null
            if (',' in spec || '-' !in spec) return null
            val start = spec.substringBefore('-').trim()
            val end = spec.substringAfter('-').trim()
            return when {
                start.isEmpty() -> {
                    val n = end.toLongOrNull()?.takeIf { it > 0 } ?: return null
                    maxOf(0L, length - n)..(length - 1)
                }
                else -> {
                    val a = start.toLongOrNull() ?: return null
                    if (a >= length) return LongRange.EMPTY
                    val b = if (end.isEmpty()) length - 1 else (end.toLongOrNull() ?: return null)
                    if (b < a) return null
                    a..minOf(b, length - 1)
                }
            }
        }

        private val TEXT_TYPES = setOf("application/javascript", "application/json", "application/xml", "image/svg+xml")

        fun isPdf(mimeType: String?): Boolean = mimeType != null && splitMimeType(mimeType).first == "application/pdf"

        /** A Gutenberg pack's book (Plan GB): opened as a book, not shown as a page of bytes. */
        fun isEpub(mimeType: String?): Boolean = mimeType != null && splitMimeType(mimeType).first == "application/epub+zip"

        /** Splits `text/html; charset=utf-8` into the MIME type and encoding WebResourceResponse wants. */
        fun splitMimeType(raw: String): Pair<String, String?> {
            val parts = raw.split(';').map { it.trim() }
            val mimeType = parts.first().lowercase().ifEmpty { "application/octet-stream" }
            val charset = parts.drop(1)
                .firstOrNull { it.startsWith("charset=", ignoreCase = true) }
                ?.substringAfter('=')?.trim('"', ' ')
            val isText = mimeType.startsWith("text/") || mimeType in TEXT_TYPES
            return mimeType to (charset ?: if (isText) "UTF-8" else null)
        }
    }
}
