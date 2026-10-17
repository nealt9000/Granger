package app.granger.reader

/**
 * Repairs for ZIM pages that expect something the reader doesn't supply. Kiwix-built main pages (the Ray Charles
 * sample among them) run `_res_/script.js`, which needs jQuery but never loads it: Kiwix's own reader injects it.
 * Granger serves its copy from the app's assets through the `_granger` pseudo-pack (see [ZimWebViewClient]).
 */
object MainPageFixes {
    const val JQUERY_URL = "https://zim.granger.invalid/_granger/jquery-3.7.1.min.js"
    private const val JQUERY_TAG = """<script src="$JQUERY_URL"></script>"""

    private val SCRIPT_TAG = Regex("""<script\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val SRC = Regex("""(?<![\w-])src\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE)

    /** Adds jQuery before the first `_res_/script.js` when the page loads no jQuery itself; otherwise returns [html]. */
    fun patch(html: String): String {
        val scripts = SCRIPT_TAG.findAll(html).map { it to src(it.value) }.toList()
        if (scripts.any { (_, src) -> src?.contains("jquery", ignoreCase = true) == true }) return html
        val res = scripts.firstOrNull { (_, src) -> src?.endsWith("_res_/script.js") == true }?.first ?: return html
        return html.substring(0, res.range.first) + JQUERY_TAG + html.substring(res.range.first)
    }

    private fun src(tag: String): String? =
        SRC.find(tag)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
}
