package app.granger.reader

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView

/** A WebView configured for ZIM content that can report and restore how far down the page it is. */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class ReaderWebView(context: Context, client: ZimWebViewClient) : WebView(context) {
    init {
        settings.javaScriptEnabled = true // Wikipedia ZIMs use JS for collapsible sections
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        webViewClient = client
    }

    /** 0 at the top, 1 at the bottom; 0 when the page does not scroll. */
    fun scrollFraction(): Float {
        val range = computeVerticalScrollRange() - height
        return if (range <= 0) 0f else (scrollY.toFloat() / range).coerceIn(0f, 1f)
    }

    fun scrollToFraction(fraction: Float) {
        evaluateJavascript(
            "window.scrollTo(0, (document.documentElement.scrollHeight - window.innerHeight) * $fraction)",
            null,
        )
    }
}
