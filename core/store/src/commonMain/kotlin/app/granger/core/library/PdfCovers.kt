package app.granger.core.library

/** A PDF has no cover entry; its first page stands in (Android: the platform's PdfRenderer, `PlatformPdfCovers`). */
fun interface PdfCovers {
    /** Page 1 of the PDF at [path] as a PNG [heightPx] tall; null if the file can't be rendered. */
    fun firstPage(path: String, heightPx: Int): ByteArray?

    companion object {
        /** No covers: a phone that can't render a page yet. */
        val NONE = PdfCovers { _, _ -> null }
    }
}
