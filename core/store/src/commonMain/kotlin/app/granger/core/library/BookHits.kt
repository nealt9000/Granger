package app.granger.core.library

import app.granger.core.books.TextQuote

// Shared since Plan I4b Task 8 (Home's feeds draw book cards on both phones); `BookIndex` finds them on Android.

/** A place in a book where the search words occur. [snippet] is HTML with `<b>` highlights; empty if unavailable. */
data class BookHit(
    val bookId: String,
    val bookTitle: String,
    val author: String,
    val href: String,
    val progression: Double,
    val snippet: String,
    /** The match itself, for the reader to find and mark on the page; null if the text couldn't be read. */
    val quote: TextQuote? = null,
    /** How many places in the book match, of the first few hundred found; Search shows it when one book has several. */
    val matches: Int = 1,
)

/**
 * What a chunk of [book] at [href] is called on a card: "Page 12" in a PDF; in an EPUB, "Chapter n" by the order of
 * the chapters with text among [chunks] (the book's chunks in reading order).
 */
fun chunkLabel(book: Book, href: String, chunks: List<BookChunk>): String =
    pdfPageOf(href)?.takeIf { book.format == BookFormat.PDF }?.let { "Page $it" }
        ?: "Chapter ${chunks.map { it.href }.distinct().indexOf(href) + 1}"

/** A PDF chunk's page ("page/12" → 12), as `PdfText.pageOf` reads it; null for an EPUB href. */
private fun pdfPageOf(href: String): Int? = href.removePrefix("page/").takeIf { it != href }?.toIntOrNull()
