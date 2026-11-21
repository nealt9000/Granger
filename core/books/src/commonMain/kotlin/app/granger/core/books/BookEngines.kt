package app.granger.core.books

import kotlinx.io.IOException

/**
 * The phone's own readers behind the shared book rules (Plan I5 call 6). Android's (PdfBox, junrar and java.util.zip,
 * commonmark) are set by default. The iPhone's are set from Swift at launch (PDFKit, libarchive; Plan I5b): until then
 * they throw [UnsupportedOperationException].
 */
object BookEngines {
    var pdf: PdfOpener = platformPdfOpener()
    var comics: ComicPages = platformComicPages()
    var markdown: MarkdownChapters = platformMarkdown()
}

internal expect fun platformPdfOpener(): PdfOpener

internal expect fun platformComicPages(): ComicPages

internal expect fun platformMarkdown(): MarkdownChapters

/** Opens a PDF: throws [PdfPasswordException] when it needs a password to open, another [IOException] when it can't be read. */
fun interface PdfOpener {
    fun open(path: String): PdfFile
}

/** A PDF that needs a password to open (an owner password alone doesn't stop it opening). */
class PdfPasswordException(message: String?) : IOException(message)

/**
 * One open PDF as the phone's PDF engine reads it. Pages count from 0 unless a parameter says otherwise. Any read may
 * throw an [IOException] or a [RuntimeException] on a damaged file.
 */
interface PdfFile : AutoCloseable {
    val pageCount: Int

    /** Encrypted at all: an owner-password PDF opens like any other, but some readers leave its outline and links alone. */
    val isEncrypted: Boolean

    /** The information dictionary and the catalog's language. */
    fun info(): PdfInfo

    /**
     * The outline, depth-first, each entry with its 1-based page and its depth (0 at the top). Entries whose destination
     * isn't a page here are skipped (destinations given directly, by name or through a GoTo action are followed); at
     * most 2,000 entries, 8 deep.
     */
    fun outline(): List<OutlineEntry>

    /**
     * Page [page] (from 1) as plain text, lines in reading order (sorted by position) split by `\n`, with no paragraph
     * marks of the engine's own.
     */
    fun pageText(page: Int): String

    /** How page [index] draws its text, and whether a picture covers it, for [PdfOrigin]. */
    fun look(index: Int): PdfOrigin.Companion.PageLook

    /** Every link to one of this PDF's own pages, page by page; at most [max]. */
    fun links(max: Int): List<PdfLink>

    /** Page [index] as shown (a page turned a quarter is measured turned), in points. */
    fun pageSize(index: Int): PdfPageSize

    /**
     * Page [index]'s glyphs as drawn, sorted by position, in the page's shown orientation with y down from its top.
     * A glyph drawn twice a hair apart (faked bold) comes once. Glyphs read before damage stand.
     */
    fun glyphs(index: Int): List<Glyph>

    /**
     * Page [index]'s pictures covering at least [minArea] square points, each written into the folder [scratch] (a
     * JPEG as it is, up to 1,600 pixels a side; anything else decoded, scaled to that and stored as PNG with
     * transparency or JPEG). Pictures found before damage stand.
     */
    fun pictures(index: Int, minArea: Float, scratch: String): List<PdfPicture>
}

/** A PDF's own information; null where it says nothing. [created] is `yyyy-MM-dd`. */
data class PdfInfo(
    val title: String?,
    val author: String?,
    val subject: String?,
    val keywords: String?,
    val created: String?,
    val language: String?,
)

data class PdfPageSize(val width: Float, val height: Float)

/**
 * A glyph as drawn: its [text] (null when the engine has none), its left [x] and baseline [y] from the page's top-left,
 * its [width], its font [size] in points (0 when unknown) and [height], and whether its font is [bold] or [italic]
 * (by the font's name and descriptor: "Minion-BoldIt", weight 600 or more, the italic flag).
 */
class Glyph(
    val text: String?,
    val x: Float,
    val y: Float,
    val width: Float,
    val size: Float,
    val height: Float,
    val bold: Boolean,
    val italic: Boolean,
)

/** A picture on a page, top-down, written to the file at [path]. */
class PdfPicture(val x: Float, val y: Float, val w: Float, val h: Float, val path: String, val mediaType: String)

/** A comic book archive's pictures (CBZ, a zip; CBR, a RAR 4 archive), and a PDF made of pictures. */
interface ComicPages {
    /**
     * The pictures in [archive] whose names [keep] accepts, unpacked into the folder [scratch] in the archive's own
     * order (a solid RAR unpacks no other way): each as its name in the archive and the unpacked file's path. The kind
     * is read from the first bytes, not the name. Null when it's neither a zip nor a RAR archive. Throws
     * [IOException] when it can't be read.
     */
    fun unpack(archive: String, scratch: String, keep: (String) -> Boolean): List<Pair<String, String>>?

    /**
     * A PDF at [out] with one page per picture that reads, at the picture's own size, in order: JPEGs as they are,
     * other pictures stored as JPEG. False, with nothing written, when none reads.
     */
    fun writePdf(pictures: List<String>, out: String): Boolean
}

/** Markdown into an EPUB's chapters, each its title and its XHTML body. */
fun interface MarkdownChapters {
    fun chapters(markdown: String): List<Pair<String, String>>
}
