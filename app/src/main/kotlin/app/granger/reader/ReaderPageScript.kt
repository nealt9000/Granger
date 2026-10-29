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
 * The padding a page gets for the reader's bars over it (Plan U3c Decision 5): [topDp] and [bottomDp] (CSS px are dp
 * here) on the root, so the first and last lines clear the bars at rest, and as scroll padding, so the Contents' jumps
 * and #anchors land below the top bar. [leftDp] and [rightDp] keep the lines' starts clear of the rail on its side,
 * while the page's background still runs under it. The top is kept for the read-aloud script's first visible block.
 *
 * A page that scrolls inside its own full-screen box instead of the root (Kiwix main pages: mwoffliner's `#container`
 * is `position:absolute; left:0; top:0; height:100%; overflow:auto`) ignores the root's padding, so that box gets the
 * same four, border-box: a body child, or its first child, that is absolute or fixed, starts at the viewport's top
 * left, is at least 90 % of its height and scrolls. Run at document start the body isn't there yet, so the script runs
 * again when the DOM is loaded; it is idempotent, and [ReaderWebView.applyChromePadding] runs it once more after load.
 */
fun chromePaddingScript(topDp: Int, bottomDp: Int, leftDp: Int = 0, rightDp: Int = 0): String = """
    (function () {
      function pad(s) {
        s.setProperty('padding-top', '${topDp}px', 'important');
        s.setProperty('padding-bottom', '${bottomDp}px', 'important');
        s.setProperty('padding-left', '${leftDp}px', 'important');
        s.setProperty('padding-right', '${rightDp}px', 'important');
      }
      function scroller(el) {
        if (!el) return false;
        var cs = getComputedStyle(el);
        if (cs.position !== 'absolute' && cs.position !== 'fixed') return false;
        if (!/(auto|scroll)/.test(cs.overflowY + ' ' + cs.overflow)) return false;
        var r = el.getBoundingClientRect();
        return r.top <= 2 && r.left <= 2 && r.height >= window.innerHeight * 0.9;
      }
      function apply() {
        var s = document.documentElement.style;
        pad(s);
        s.setProperty('scroll-padding-top', '${topDp}px');
        s.setProperty('scroll-padding-bottom', '${bottomDp}px');
        window.__grangerTop = $topDp;
        if (!document.body) return;
        var kids = document.body.children;
        for (var i = 0; i < kids.length; i++) {
          var el = scroller(kids[i]) ? kids[i] : scroller(kids[i].firstElementChild) ? kids[i].firstElementChild : null;
          if (!el) continue;
          pad(el.style);
          el.style.setProperty('box-sizing', 'border-box', 'important');
          el.style.setProperty('scroll-padding-top', '${topDp}px');
          el.style.setProperty('scroll-padding-bottom', '${bottomDp}px');
        }
      }
      apply();
      if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', apply, { once: true });
    })();
""".trimIndent()

/**
 * The page's only way into the app, added to the WebView as [ReaderPageScript.BRIDGE_NAME]. JavaScript calls arrive
 * on a WebView background thread, so [onToggleChrome] and [onUserScroll] are posted to the main thread.
 */
class GrangerBridge(
    private val onToggleChrome: () -> Unit,
    private val onUserScroll: (dyDp: Float, byHand: Boolean) -> Unit = { _, _ -> },
) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun toggleChrome() {
        main.post(onToggleChrome)
    }

    /**
     * The page scrolled, not by the read-aloud script: [dy] dp, down when positive. [byHand] when a finger moved it
     * (a drag, or the fling after one); false for the page's own moves (restoring the place, a Contents jump), which
     * stop the voice following but leave the chrome alone.
     */
    @JavascriptInterface
    fun onUserScroll(dy: Double, byHand: Boolean) {
        main.post { onUserScroll(dy.toFloat(), byHand) }
    }
}
