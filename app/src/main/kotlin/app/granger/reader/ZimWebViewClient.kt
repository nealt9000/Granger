package app.granger.reader

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import app.granger.core.zim.LinkAction
import app.granger.core.zim.ZimResolver
import app.granger.core.zim.ZimResponse
import java.io.ByteArrayInputStream

/**
 * Serves every request from the ZIM (the Kiwix approach) and blocks everything else, so the reader
 * never touches the network. Callbacks run on the main thread.
 */
class ZimWebViewClient(
    private val resolver: ZimResolver,
    private val onExternalLink: (url: String) -> Unit,
    private val onLeavingPage: (WebView) -> Unit,
    private val onPageShown: (WebView, url: String) -> Unit,
) : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
        when (val response = resolver.response(request.url.toString())) {
            is ZimResponse.Content ->
                WebResourceResponse(response.mimeType, response.encoding, ByteArrayInputStream(response.bytes))
            ZimResponse.NotFound ->
                WebResourceResponse("text/html", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(NOT_FOUND))
            ZimResponse.Blocked ->
                WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
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
        onPageShown(view, url)
    }

    private companion object {
        val NOT_FOUND =
            "<html><body><p>This page isn't in this pack.</p></body></html>".toByteArray()
    }
}
