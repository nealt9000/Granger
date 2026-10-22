package app.granger.ebook

/**
 * The first words visible on the e-book's current page, asked of the navigator's WebView directly. Readium's
 * `firstVisibleElementLocator()` answers with the first *element* that touches the page, which is the paragraph that
 * ran over from the page before (or a container holding the whole chapter) as often as not; Read aloud wants the
 * sentence the reader can see at the top of the page, so this walks the text nodes and returns the first word whose
 * box lies inside the viewport (the page's column when paginated, the window when scrolling), with what follows it.
 */
object FirstVisibleText {
    const val SNIPPET = 80

    /** Returns the snippet as a JSON string, or null when the page shows no text. */
    val JS = """
        (function () {
          var w = window.innerWidth, h = window.innerHeight;
          var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null);
          var range = document.createRange(), node;
          function inside(r) { return r.width > 0 && r.height > 0 && r.left >= -1 && r.right <= w + 1 && r.top >= -1 && r.bottom <= h + 1; }
          while ((node = walker.nextNode())) {
            var text = node.nodeValue;
            if (!text || !text.trim()) continue;
            range.selectNodeContents(node);
            var whole = range.getBoundingClientRect();
            if (whole.width === 0 && whole.height === 0) continue;
            if (whole.right <= 0 || whole.left >= w || whole.bottom <= 0 || whole.top >= h) continue;
            var re = /\S+/g, m;
            while ((m = re.exec(text))) {
              range.setStart(node, m.index); range.setEnd(node, m.index + m[0].length);
              if (inside(range.getBoundingClientRect())) {
                var out = text.slice(m.index), n;
                while (out.length < $SNIPPET && (n = walker.nextNode())) { if (n.nodeValue) out += n.nodeValue; }
                return out.slice(0, $SNIPPET);
              }
            }
          }
          return null;
        })()
    """.trimIndent()

    /** The snippet out of `evaluateJavascript`'s answer (a JSON string literal, or `null`), or null. */
    fun parse(result: String?): String? {
        val raw = result?.trim()?.takeIf { it.length >= 2 && it.startsWith('"') && it.endsWith('"') } ?: return null
        return unescape(raw.substring(1, raw.length - 1)).trim().takeIf { it.isNotEmpty() }
    }

    /** JSON string escapes, by hand: the platform's `org.json` is a stub in JVM tests and the input is tiny. */
    private fun unescape(s: String): String {
        if ('\\' !in s) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i + 1 >= s.length) { out.append(c); i++; continue }
            when (val e = s[i + 1]) {
                'n' -> out.append('\n'); 't' -> out.append('\t'); 'r' -> out.append('\r')
                'b' -> out.append('\b'); 'f' -> out.append('\u000C')
                'u' -> {
                    val hex = s.substring(i + 2, minOf(i + 6, s.length))
                    val code = hex.takeIf { it.length == 4 }?.toIntOrNull(16)
                    if (code != null) { out.append(code.toChar()); i += 4 } else out.append(e)
                }
                else -> out.append(e)       // \" \\ \/
            }
            i += 2
        }
        return out.toString()
    }
}
