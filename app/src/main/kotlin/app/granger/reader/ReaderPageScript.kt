package app.granger.reader

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface

/**
 * Script run in every page after it loads (by [ZimWebViewClient]). A tap that lands on plain content in the middle
 * 60 % of the width shows or hides the reader's chrome; taps on links, controls, headings (collapsible sections) and anything the page already handled, and near the edges, don't.
 * The listener runs in the capture phase so a page handler that stops propagation can't hide a tap from it. It also
 * keeps the reading spot across a resize (see [keepSpot]).
 */
object ReaderPageScript {
    const val BRIDGE_NAME = "GrangerBridge"

    /** Tells the page its top moved [shiftPx] device pixels down (negative: up), ahead of the resize that goes with it. */
    fun keepSpot(shiftPx: Int) = "window.__grangerSpot && window.__grangerSpot.shift($shiftPx)"

    /**
     * What to tell the page when the WebView is resized with its top now at [top] (window pixels), having been at
     * [before] at the last layout: how far it moved, or nothing when it didn't move (the page script keeps the spot
     * at the same height by itself) or this is the first layout ([before] null, or not [resized] from a real size).
     */
    fun spotShift(before: Int?, top: Int, resized: Boolean): String? =
        if (before == null || !resized || top == before) null else keepSpot(top - before)

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
        (function () {
          // The reading spot: the line of text near the top of the window, and how far down the window it is. It is
          // taken again as the page scrolls; when the page is resized (the toolbar shown or hidden, the keyboard) the
          // page is scrolled so that line is where it was on screen, before the new layout is painted.
          if (window.__grangerSpot) return;
          var spot = null, shift = 0, shiftTimer = 0, unshiftedAt = 0, queued = false;
          function scale() { return (window.devicePixelRatio || 1) * (window.visualViewport ? window.visualViewport.scale : 1); }
          function topOf(s) {
            if (s.range) { var rects = s.range.getClientRects(); return (rects.length ? rects[0] : s.range.getBoundingClientRect()).top; }
            return s.el.getBoundingClientRect().top;
          }
          function capture() {
            var x = window.innerWidth / 2, y = Math.min($SPOT_Y_CSS, window.innerHeight / 2);
            var at = document.caretRangeFromPoint ? document.caretRangeFromPoint(x, y) : null;
            if (at && at.startContainer.nodeType === 3) {
              var node = at.startContainer, range = document.createRange();
              range.setStart(node, at.startOffset);
              range.setEnd(node, Math.min(at.startOffset + 1, node.length));
              spot = { range: range };
            } else {
              var el = document.elementFromPoint(x, y);
              spot = el ? { el: el } : null;
            }
            if (spot) spot.y = topOf(spot);
          }
          function attached(s) { return s.range ? s.range.startContainer.isConnected : s.el.isConnected; }
          window.addEventListener('scroll', function () {
            if (queued) return;
            queued = true;
            requestAnimationFrame(function () { queued = false; capture(); });
          }, { passive: true });
          window.addEventListener('resize', function () {
            if (spot && attached(spot)) window.scrollBy(0, topOf(spot) - (spot.y - shift / scale()));
            unshiftedAt = shift === 0 ? Date.now() : 0;
            shift = 0;
            capture();
          });
          window.__grangerSpot = {
            shift: function (px) {
              if (unshiftedAt && Date.now() - unshiftedAt < $LATE_MS) {
                // The resize got here first: finish the job now.
                unshiftedAt = 0;
                window.scrollBy(0, px / scale());
                capture();
                return;
              }
              shift = px;
              clearTimeout(shiftTimer);
              shiftTimer = setTimeout(function () { shift = 0; }, $LATE_MS);
            }
          };
          capture();
        })();
    """.trimIndent()

    /** How far down the window (CSS px) the reading spot is taken: the first line or so below the top. */
    private const val SPOT_Y_CSS = 24

    /** A shift and its resize further apart than this don't belong together. */
    private const val LATE_MS = 500
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
