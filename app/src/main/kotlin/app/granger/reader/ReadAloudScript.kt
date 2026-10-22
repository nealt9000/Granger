package app.granger.reader

import app.granger.core.research.ArticleText
import org.json.JSONObject
import org.json.JSONTokener

/** A block the page named: its id (the element's own, or `g<n>`), the start of its text, and a character offset into it when a selection chose it. */
data class BlockRef(val id: String, val prefix: String, val offset: Int? = null) {
    companion object {
        /** From the page script's JSON (`{"id","prefix","offset"}`), or null for `null`, an empty answer or junk. */
        fun parse(json: String?): BlockRef? {
            if (json.isNullOrBlank() || json == "null") return null
            return runCatching {
                var value = JSONTokener(json).nextValue()
                if (value is String) value = JSONTokener(value).nextValue()     // evaluateJavascript double-encodes strings
                val o = value as? JSONObject ?: return null
                val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return null
                BlockRef(id, o.optString("prefix"), if (o.has("offset") && !o.isNull("offset")) o.optInt("offset") else null)
            }.getOrNull()
        }
    }
}

/**
 * The read-aloud side of the page, run after every page loads: `window.__grangerRead`. It applies [ArticleText]'s
 * rules in the browser's DOM so block ids agree with the Kotlin side (`g<n>` counts every block element in document
 * order, noise or not), marks the sentence being read with the CSS Custom Highlight API (a class on the block where
 * the WebView lacks it), scrolls it into view while the reader follows, and reports the user's own scrolls through
 * the bridge so Follow can switch off.
 *
 * - `firstVisible()`: the first readable block on screen, as `{id, prefix}`.
 * - `selectionBlock()`: the block the selection starts in and the folded offset into it, `{id, prefix, offset}`.
 * - `mark(id, prefix, start, end, scroll)`: highlights characters `start..end` of the block's folded text.
 * - `clear()`.
 */
object ReadAloudScript {
    val SOURCE = """
        (function () {
          if (window.__grangerRead) return;
          var NOISE = ${js(ArticleText.NOISE)};
          var BLOCKS = ${js(ArticleText.BLOCKS)};
          var style = document.createElement('style');
          style.textContent = '::highlight(granger-reading){background:rgba(232,96,28,.28)} .granger-reading{background:rgba(232,96,28,.18)}';
          (document.head || document.documentElement).appendChild(style);
          var hasHighlight = !!(window.CSS && CSS.highlights && window.Highlight);
          var ownScrollUntil = 0;
          var marked = null;

          function inNoise(node) {
            var el = node.nodeType === 1 ? node : node.parentElement;
            return !!(el && el.closest && el.closest(NOISE));
          }
          function blocks() {
            var all = document.querySelectorAll(BLOCKS), out = [];
            for (var i = 0; i < all.length; i++) {
              var el = all[i];
              if (inNoise(el)) continue;
              out.push({ el: el, id: el.id || ('g' + i) });
            }
            return out;
          }
          // The block's text folded as Kotlin folds it (whitespace runs to one space, trimmed), with each folded
          // character's text node and offset, so a sentence's range lands on the right letters.
          function folded(el) {
            var walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT, null);
            var text = '', map = [], pendingSpace = false, node;
            while ((node = walker.nextNode())) {
              if (inNoise(node)) continue;
              var s = node.nodeValue;
              for (var k = 0; k < s.length; k++) {
                var c = s.charAt(k);
                if (/[\s   ]/.test(c)) { pendingSpace = text.length > 0; continue; }
                if (pendingSpace) { text += ' '; map.push(map[map.length - 1]); pendingSpace = false; }
                text += c; map.push({ node: node, offset: k });
              }
            }
            return { text: text, map: map };
          }
          function prefixOf(el) { return folded(el).text.slice(0, 40); }
          function find(id, prefix) {
            var list = blocks(), i;
            for (i = 0; i < list.length; i++) if (list[i].id === id) return list[i].el;
            if (prefix) for (i = 0; i < list.length; i++) if (folded(list[i].el).text.indexOf(prefix) === 0) return list[i].el;
            return null;
          }
          function rangeFor(el, start, end) {
            var f = folded(el);
            if (!f.map.length) return null;
            var a = f.map[Math.max(0, Math.min(start, f.map.length - 1))];
            var b = f.map[Math.max(0, Math.min(end - 1, f.map.length - 1))];
            var r = document.createRange();
            r.setStart(a.node, a.offset);
            r.setEnd(b.node, b.offset + 1);
            return r;
          }
          function clear() {
            if (hasHighlight) CSS.highlights.delete('granger-reading');
            if (marked) { marked.classList.remove('granger-reading'); marked = null; }
          }
          function mark(id, prefix, start, end, scroll) {
            var el = find(id, prefix);
            if (!el) { clear(); return 'missing'; }
            var r = rangeFor(el, start, end);
            if (hasHighlight && r) {
              if (marked) { marked.classList.remove('granger-reading'); marked = null; }
              CSS.highlights.set('granger-reading', new Highlight(r));
            } else {
              if (marked && marked !== el) marked.classList.remove('granger-reading');
              el.classList.add('granger-reading'); marked = el;
            }
            if (scroll) {
              var rect = (r && r.getBoundingClientRect().height ? r : el).getBoundingClientRect();
              if (rect.top < 0 || rect.bottom > window.innerHeight * 0.85) {
                ownScrollUntil = Date.now() + 800;
                el.scrollIntoView({ block: 'center', behavior: 'smooth' });
              }
            }
            return 'ok';
          }
          function firstVisible() {
            var list = blocks();
            for (var i = 0; i < list.length; i++) {
              var rect = list[i].el.getBoundingClientRect();
              if (rect.bottom > 0 && rect.top < window.innerHeight * 0.9 && rect.height > 0) return { id: list[i].id, prefix: prefixOf(list[i].el) };
            }
            return list.length ? { id: list[0].id, prefix: prefixOf(list[0].el) } : null;
          }
          function selectionBlock() {
            var sel = window.getSelection();
            if (!sel || !sel.anchorNode) return null;
            var node = sel.anchorNode, el = node.nodeType === 1 ? node : node.parentElement;
            var block = el && el.closest ? el.closest(BLOCKS) : null;
            if (!block) return null;
            var list = blocks(), id = null;
            for (var i = 0; i < list.length; i++) if (list[i].el === block) { id = list[i].id; break; }
            if (!id) return null;
            var f = folded(block), offset = 0;
            for (var k = 0; k < f.map.length; k++) {
              var m = f.map[k];
              if (m.node === node && m.offset >= sel.anchorOffset) { offset = k; break; }
              if (node.compareDocumentPosition(m.node) & Node.DOCUMENT_POSITION_FOLLOWING) { offset = k; break; }
              offset = k + 1;
            }
            return { id: id, prefix: f.text.slice(0, 40), offset: offset };
          }
          window.addEventListener('scroll', function () {
            if (Date.now() < ownScrollUntil) return;
            if (window.${ReaderPageScript.BRIDGE_NAME}) ${ReaderPageScript.BRIDGE_NAME}.onUserScroll();
          }, { passive: true });
          window.__grangerRead = { firstVisible: firstVisible, selectionBlock: selectionBlock, mark: mark, clear: clear };
        })();
    """.trimIndent()

    /** A call into the page: `JSON.stringify(window.__grangerRead && window.__grangerRead.<fn>(args))`. */
    fun call(fn: String, vararg args: Any?): String =
        "JSON.stringify(window.__grangerRead && window.__grangerRead.$fn(" + args.joinToString(", ") { js(it) } + "))"

    private fun js(value: Any?): String = when (value) {
        null -> "null"
        is String -> JSONObject.quote(value)
        is Boolean, is Number -> value.toString()
        else -> JSONObject.quote(value.toString())
    }
}
