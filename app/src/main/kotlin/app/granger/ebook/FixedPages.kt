package app.granger.ebook

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.readium.r2.shared.publication.Publication

/**
 * Picture books laid out page by page (Plan KB walkthrough, 2026-10-30). Prunelle's interactive books (Kotobee
 * exports of PDFs) declare `rendition:layout reflowable` for the book but `rendition:layout-pre-paginated` on every
 * spine item; each page is one full-page picture in a `<meta name="viewport" content="width=2000, height=1000">`
 * box. Readium 3.4 takes the layout from the book's metadata only and drops the spine item's, so it reflowed them.
 *
 * A fixed-layout page must declare its size in a viewport meta (EPUB 3 FXL), and a reflowable page has no reason to
 * give a size in pixels, so a book whose every page does is laid out fixed, whatever its metadata says. That one
 * signal catches the per-item declaration Readium skips without reading the package again, and it is what the page
 * itself needs to be fitted.
 */
object FixedPages {
    /** Average characters of text a page needs before the book counts as having text to read aloud or search. */
    const val MIN_CHARS_PER_PAGE = 40

    private val viewportMeta = Regex("""<meta\b[^>]*\bname\s*=\s*["']viewport["'][^>]*>""", RegexOption.IGNORE_CASE)
    private val width = Regex("""\bwidth\s*=\s*\d""", RegexOption.IGNORE_CASE)
    private val height = Regex("""\bheight\s*=\s*\d""", RegexOption.IGNORE_CASE)
    private val bodyStart = Regex("""<body\b""", RegexOption.IGNORE_CASE)
    private val hidden = Regex("""<(script|style)\b.*?</\1\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val tag = Regex("""<[^>]*>""")
    private val entity = Regex("""&(#\d+|#x[0-9a-fA-F]+|[a-zA-Z]+);""")
    private val space = Regex("""\s+""")

    /** Whether [html] gives its page a size in pixels, as a fixed-layout page does. */
    fun hasPageSize(html: String): Boolean {
        val meta = viewportMeta.find(html)?.value ?: return false
        return width.containsMatchIn(meta) && height.containsMatchIn(meta)
    }

    /** How many characters of visible text [html]'s body holds; entities (a `&#160;` filler) and markup don't count. */
    fun textLength(html: String): Int {
        val body = bodyStart.find(html)?.let { html.substring(it.range.first) } ?: html
        return body.replace(hidden, " ").replace(tag, " ").replace(entity, " ").replace(space, "").length
    }

    /** What a book's pages are. */
    data class Scan(val pages: Int, val sizedPages: Int, val textChars: Int) {
        /** Every page has a size of its own: show them as fixed pages. */
        val allSized: Boolean get() = pages > 0 && sizedPages == pages

        /** Some words to read aloud or find: a picture book's pages hold little more than a page number. */
        val hasText: Boolean get() = pages == 0 || textChars >= pages * MIN_CHARS_PER_PAGE
    }

    /**
     * Reads [publication]'s HTML pages. With [stopAtUnsized] it stops at the first page without a size (a reflowable
     * book usually stops at its first page), so only a picture book is read through; that early [Scan] counts as
     * having text.
     */
    suspend fun scan(publication: Publication, stopAtUnsized: Boolean): Scan {
        val pages = publication.readingOrder.filter { it.mediaType?.isHtml == true }
        var sized = 0
        var text = 0
        for (link in pages) {
            currentCoroutineContext().ensureActive()
            val resource = publication.get(link)
            val html = try {
                resource?.read()?.getOrNull()?.let { String(it, Charsets.UTF_8) }.orEmpty()
            } finally {
                resource?.close()
            }
            if (hasPageSize(html)) {
                sized++
            } else if (stopAtUnsized) {
                return Scan(pages.size, sized, Int.MAX_VALUE)
            }
            text += textLength(html)
        }
        return Scan(pages.size, sized, text)
    }
}
