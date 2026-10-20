package app.granger.reader

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface

/**
 * Script run in every page after it loads (by [ZimWebViewClient]). A tap that lands on plain content in the middle
 * 60 % of the width shows or hides the reader's chrome; taps on links, controls, headings (collapsible sections) and anything the page already handled, and near the edges, don't.
 * The listener runs in the capture phase so a page handler that stops propagation can't hide a tap from it.
 */
object ReaderPageScript {
    const val BRIDGE_NAME = "GrangerBridge"

    val SOURCE = """
        (function () {
          if (window.__grangerTap) return;
          window.__grangerTap = true;
          document.addEventListener('click', function (e) {
            if (e.defaultPrevented) return;
            var target = e.target;
            if (target && target.closest && target.closest('a, button, input, summary, [onclick], h1, h2, h3, h4, h5, h6, [role=button]')) return;
            var width = window.innerWidth;
            if (e.clientX < width * 0.2 || e.clientX > width * 0.8) return;
            if (window.$BRIDGE_NAME) $BRIDGE_NAME.toggleChrome();
          }, true);
        })();
    """.trimIndent()
}

/**
 * The page's only way into the app, added to the WebView as [ReaderPageScript.BRIDGE_NAME]. JavaScript calls arrive
 * on a WebView background thread, so [onToggleChrome] is posted to the main thread.
 */
class GrangerBridge(private val onToggleChrome: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun toggleChrome() {
        main.post(onToggleChrome)
    }
}
