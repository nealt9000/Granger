package app.granger.ebook

/**
 * A stylesheet added to every styled HTML page of a book, last in its head so it follows the book's own CSS.
 *
 * Why (Pixel 6 walkthrough, 2026-10-17): archive.org's scan EPUBs declare their pages `text/html`, so the WebView
 * parses `<p/>` as an open paragraph and the page image lands inside it. The book's `p { text-indent: 4em }` then
 * starts the image 4em in while `max-width: 100%` keeps it page-wide, so it runs 4em off the right edge. That overflow
 * also traps edge taps: the page is a little wider than one screen, so Readium's `scrollRight` "moves" to an offset
 * that snaps back to 0 and reports a move, and the navigator never goes on to the next page. ReadiumCSS already caps
 * images at the page's width; this only takes the indent off a paragraph holding an image, so the page is exactly one
 * screen wide. Nothing else is overridden: the book's own image sizes (an equation glyph at `height: 1.2em`, a cover
 * at `height: 100%`) stand.
 *
 * Readium adds ReadiumCSS-default only to pages with no styles of their own, so an unstyled page is left alone
 * (adding a `<style>` would cost it those defaults, and with no book CSS it has no indent to undo).
 */
object BookCss {
    private const val ID = "granger-book-css"
    const val STYLE = "<style id=\"$ID\">p:has(img) { text-indent: 0 !important; }</style>"

    private val closingHead = Regex("</head\\s*>", RegexOption.IGNORE_CASE)
    private val hasStyles = Regex("<link | style=|<style.*?>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)) // ReadiumCss.hasStyles

    /** [html] with [STYLE] before `</head>`; unchanged when it has no head, no styles of its own, or [STYLE] already. */
    fun inject(html: ByteArray): ByteArray {
        // Latin-1 maps each byte to one char and back, so UTF-8 (or any ASCII-compatible encoding) round-trips intact.
        val text = String(html, Charsets.ISO_8859_1)
        if (ID in text) return html
        val head = closingHead.find(text) ?: return html
        if (!hasStyles.containsMatchIn(text)) return html
        return StringBuilder(text).insert(head.range.first, STYLE).toString().toByteArray(Charsets.ISO_8859_1)
    }
}
