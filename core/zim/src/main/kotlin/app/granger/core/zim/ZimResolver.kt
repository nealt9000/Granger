package app.granger.core.zim

/** What the reader's WebView gets for one request. */
sealed interface ZimResponse {
    class Content(val mimeType: String, val encoding: String?, val bytes: ByteArray) : ZimResponse
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
    /** javascript:, file:, intent: and anything else unsafe. */
    data object Ignore : LinkAction
}

class ZimResolver(private val archiveFor: (packId: String) -> ZimArchive?) {
    fun response(url: String): ZimResponse {
        val zim = ZimUrls.parse(url) ?: return ZimResponse.Blocked
        val item = archiveFor(zim.packId)?.read(zim.path) ?: return ZimResponse.NotFound
        val (mimeType, encoding) = splitMimeType(item.mimeType)
        return ZimResponse.Content(mimeType, encoding, item.bytes)
    }

    fun linkAction(url: String): LinkAction {
        val zim = ZimUrls.parse(url)
            ?: return if (url.substringBefore(':', "").lowercase() in EXTERNAL_SCHEMES) {
                LinkAction.External(url)
            } else {
                LinkAction.Ignore
            }
        val target = archiveFor(zim.packId)?.resolvePath(zim.path) ?: return LinkAction.Load
        return if (target == zim.path) {
            LinkAction.Load
        } else {
            LinkAction.Redirect(ZimUrls.forEntry(zim.packId, target, zim.fragment))
        }
    }

    companion object {
        private val EXTERNAL_SCHEMES = setOf("http", "https", "mailto", "tel", "geo")
        private val TEXT_TYPES = setOf("application/javascript", "application/json", "application/xml", "image/svg+xml")

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
