package app.granger.reader

import android.content.res.AssetManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import app.granger.core.zim.LinkAction
import app.granger.core.zim.ZimResolver
import app.granger.core.zim.ZimResponse
import app.granger.core.zim.ZimUrls
import java.io.ByteArrayInputStream
import java.nio.charset.Charset

/**
 * Serves every request from the ZIM (the Kiwix approach) and blocks everything else, so the reader
 * never touches the network. The pseudo-pack [APP_PACK] is answered from the app's `assets/reader/` instead, and
 * HTML pages get [MainPageFixes]. After each page loads it runs [ReaderPageScript]. Callbacks run on the main thread.
 */
class ZimWebViewClient(
    private val resolver: ZimResolver,
    private val assets: AssetManager,
    private val onExternalLink: (url: String) -> Unit,
    private val onLeavingPage: (WebView) -> Unit,
    private val onPageShown: (WebView, url: String) -> Unit,
    /** The WebView's history changed to [url]: a new page committed (before it finishes), or a jump within one. */
    private val onVisited: (url: String) -> Unit = {},
) : WebViewClient() {
    /** File names under `assets/reader/`; nothing else is ever served from the app. */
    private val appFiles: Set<String> by lazy { assets.list(ASSET_DIR).orEmpty().toSet() }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
        val url = request.url.toString()
        ZimUrls.parse(url)?.takeIf { it.packId == APP_PACK }?.let { return appAsset(it.path) }
        return when (val response = resolver.response(url)) {
            is ZimResponse.Content -> {
                val bytes = if (response.mimeType == "text/html") patched(response.bytes, response.encoding) else response.bytes
                WebResourceResponse(response.mimeType, response.encoding, ByteArrayInputStream(bytes))
            }
            ZimResponse.NotFound -> notFound()
            ZimResponse.Blocked ->
                WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
    }

    private fun appAsset(name: String): WebResourceResponse {
        if (name !in appFiles) return notFound()
        val mimeType = if (name.endsWith(".js")) "application/javascript" else "application/octet-stream"
        return WebResourceResponse(mimeType, "UTF-8", assets.open("$ASSET_DIR/$name"))
    }

    private fun patched(bytes: ByteArray, encoding: String?): ByteArray {
        val charset = encoding?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
        val html = String(bytes, charset)
        val fixed = MainPageFixes.patch(html)
        return if (fixed === html) bytes else fixed.toByteArray(charset)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (!request.isForMainFrame) return false
        return when (val action = resolver.linkAction(request.url.toString())) {
            LinkAction.Load -> {
                onLeavingPage(view)
                false
            }
            is LinkAction.Redirect -> {
                onLeavingPage(view)
                view.loadUrl(action.url)
                true
            }
            is LinkAction.External -> {
                onExternalLink(action.url)
                true
            }
            LinkAction.Ignore -> true
        }
    }

    override fun onPageFinished(view: WebView, url: String) {
        view.evaluateJavascript(ReaderPageScript.SOURCE, null)
        onPageShown(view, url)
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
        if (!isReload) onVisited(url)
    }

    private fun notFound() =
        WebResourceResponse("text/html", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(NOT_FOUND))

    companion object {
        /** `https://zim.granger.invalid/_granger/<file>` serves `assets/reader/<file>`. */
        const val APP_PACK = "_granger"
        private const val ASSET_DIR = "reader"
        private val NOT_FOUND =
            "<html><body><p>This page isn't in this pack.</p></body></html>".toByteArray()
    }
}
