package app.granger.core.books

import app.granger.core.books.pdfepub.Chapter
import app.granger.core.books.pdfepub.ChapterSource
import app.granger.core.books.pdfepub.EpubBook
import app.granger.core.books.pdfepub.EpubChapter
import app.granger.core.books.pdfepub.EpubImage
import app.granger.core.books.pdfepub.EpubPackage
import app.granger.core.books.pdfepub.EpubPackage.esc
import app.granger.core.books.pdfepub.Heading
import app.granger.core.books.pdfepub.Item
import app.granger.core.books.pdfepub.Note
import app.granger.core.books.pdfepub.PagePicture
import app.granger.core.books.pdfepub.Paragraph
import app.granger.core.books.pdfepub.PdfChapters
import app.granger.core.books.pdfepub.PdfLayout
import app.granger.core.books.pdfepub.PdfPages
import app.granger.core.books.pdfepub.Picture
import app.granger.core.books.pdfepub.Span
import app.granger.core.books.pdfepub.Verse
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Turns a born-digital PDF into an EPUB (Plan IA): flowing text in the reader's own typeface and size, chapters from
 * its outline (or its headings, or every 20 pages), no running heads or page numbers, paragraphs that run across
 * pages, verse kept line by line, pictures, and footnotes gathered at each chapter's end. A conversion that doesn't
 * look like a book ([Report.passes], the quality gate) writes nothing: the PDF is read as pages instead.
 */
object PdfToEpub {
    /** What a conversion saw, for the quality gate and the eval's report. */
    data class Report(
        val origin: PdfOrigin,
        val pages: Int,
        val sourceChars: Int,
        val keptChars: Int,
        val droppedChars: Int,
        val paragraphs: Int,
        val medianParagraphChars: Int,
        val verseLines: Int,
        val headings: Int,
        val pictures: Int,
        val notes: Int,
        val chapters: Int,
        val chapterSource: ChapterSource?,
        val complexPages: Int,
        val passes: Boolean,
        val why: String,
    ) {
        val keptShare: Float get() = if (sourceChars == 0) 0f else keptChars.toFloat() / sourceChars
    }

    /**
     * Converts [pdf] into [out] (through a `.incoming-` partial beside it), named [title] by [author] in [language]
     * when given (an archive.org sidecar's), else as the PDF's own information says. [out] is written only when the
     * report [Report.passes].
     */
    fun convert(pdf: File, out: File, title: String = "", author: String = "", language: String = ""): Report {
        val doc = try {
            loadPdf(pdf)
        } catch (e: IOException) {
            return failed("unreadable: ${e.message}")
        } catch (e: RuntimeException) {
            return failed("unreadable: ${e.message}")
        }
        return doc.use { convert(it, pdf, out, title, author, language) }
    }

    private fun convert(doc: PDDocument, pdf: File, out: File, title: String, author: String, language: String): Report {
        val origin = PdfOrigin.of(doc)
        if (origin != PdfOrigin.BORN_DIGITAL) return failed("not born-digital: $origin", origin, doc.numberOfPages)
        // The pictures wait here until the EPUB is written.
        val scratch = File(out.absoluteFile.parentFile, ".incoming-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            return convert(doc, pdf, out, title, author, language, origin, scratch)
        } finally {
            scratch.deleteRecursively()
        }
    }

    private fun convert(
        doc: PDDocument, pdf: File, out: File, title: String, author: String, language: String, origin: PdfOrigin, scratch: File,
    ): Report {
        val pages = (0 until doc.numberOfPages).map { PdfPages.read(doc, it, scratch) }
        val layout = PdfLayout.build(pages)
        val outline = try { PdfInspector.outline(doc) } catch (_: IOException) { emptyList() }
        val (chapters, source) = PdfChapters.split(layout.items, outline, pages.size)
        val info = doc.documentInformation
        val bookTitle = title.ifBlank { info.title?.trim().orEmpty() }.ifBlank { pdf.nameWithoutExtension }
        val bookAuthor = author.ifBlank { info.author?.trim().orEmpty() }
        val bookLanguage = language.ifBlank { doc.documentCatalog.language.orEmpty() }

        val images = mutableListOf<EpubImage>()
        val pictureNames = java.util.IdentityHashMap<PagePicture, String>()
        fun nameOf(p: PagePicture): String = pictureNames.getOrPut(p) {
            val ext = if (p.mediaType == "image/png") "png" else "jpg"
            "images/p${p.page + 1}-${pictureNames.size + 1}.$ext".also { images += EpubImage(it, p.mediaType, p.file) }
        }
        val rendered = chapters.map { render(it, ::nameOf) }
        // A picture covering page 1 is the cover.
        val cover = pages.firstOrNull()?.pictures?.firstOrNull { it.w * it.h >= pages[0].width * pages[0].height * 0.6f }?.let(::nameOf)

        val items = chapters.flatMap { it.items }
        val prose = items.filterIsInstance<Paragraph>().filterNot { it.caption }.map { it.text.count { c -> !c.isWhitespace() } }.sorted()
        val kept = items.sumOf { textOf(it).count { c -> !c.isWhitespace() } }
        val report = Report(
            origin = origin,
            pages = pages.size,
            sourceChars = layout.sourceChars,
            keptChars = kept,
            droppedChars = layout.droppedChars,
            paragraphs = prose.size,
            medianParagraphChars = prose.getOrNull(prose.size / 2) ?: 0,
            verseLines = items.filterIsInstance<Verse>().sumOf { it.lines.size },
            headings = items.count { it is Heading },
            pictures = images.size,
            notes = items.count { it is Note },
            chapters = rendered.size,
            chapterSource = source,
            complexPages = layout.complexPages,
            passes = false,
            why = "",
        )
        val verdict = gate(report)
        val final = report.copy(passes = verdict == null, why = verdict ?: "passes")
        if (!final.passes) return final

        val partial = File(out.absoluteFile.parentFile, ".incoming-${UUID.randomUUID()}.epub")
        try {
            partial.outputStream().use {
                EpubPackage.write(EpubBook(bookTitle, bookAuthor, bookLanguage, pdf.name + ":" + pdf.length(), rendered, images, CSS, cover), it)
            }
            if (!partial.renameTo(out)) partial.copyTo(out, overwrite = true)
        } catch (e: IOException) {
            return final.copy(passes = false, why = "couldn't write: ${e.message}")
        } finally {
            partial.delete()
        }
        return final
    }

    /**
     * Null when the conversion reads like a book; else why not. Nearly all the PDF's text is kept (less its running
     * heads), its pages aren't mostly tables or three columns, its prose paragraphs are paragraphs (not a line or a
     * page each), and there's enough of it.
     */
    internal fun gate(r: Report): String? = when {
        r.keptChars < 500 -> "too little text (${r.keptChars} characters)"
        r.keptShare < 0.92f -> "kept only ${(r.keptShare * 100).toInt()}% of the text"
        r.keptShare > 1.08f -> "text came out ${(r.keptShare * 100).toInt()}% of the source"
        r.complexPages * 5 > r.pages -> "${r.complexPages} of ${r.pages} pages have tables or three columns"
        r.paragraphs >= 20 && r.medianParagraphChars < 40 -> "paragraphs too short (median ${r.medianParagraphChars} characters)"
        r.paragraphs >= 5 && r.medianParagraphChars > 6000 -> "paragraphs too long (median ${r.medianParagraphChars} characters)"
        r.chapters == 0 -> "no chapters"
        else -> null
    }

    private fun failed(why: String, origin: PdfOrigin = PdfOrigin.UNKNOWN, pages: Int = 0) =
        Report(origin, pages, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, 0, passes = false, why = why)

    private fun textOf(item: Item): String = when (item) {
        is Heading -> item.text
        is Paragraph -> item.text
        is Verse -> item.lines.joinToString(" ") { (_, spans) -> spans.joinToString("") { it.text } }
        is Note -> item.spans.joinToString("") { it.text }
        is Picture -> ""
    }

    /** A chapter's XHTML: its title as h1, smaller headings as h2 and h3, prose, verse, pictures, then its notes. */
    private fun render(chapter: Chapter, pictureName: (PagePicture) -> String): EpubChapter {
        val body = StringBuilder()
        val headingSizes = chapter.items.filterIsInstance<Heading>().map { it.size }.distinct().sortedDescending()
        val sectionAt = chapter.sections.withIndex().associate { (i, s) -> s.second to ("s${i + 1}" to s.first) }
        if (chapter.titleItem == null) body.append("<h1>").append(esc(chapter.title)).append("</h1>")
        for ((i, item) in chapter.items.withIndex()) {
            val anchor = sectionAt[i]?.first
            val id = anchor?.let { " id=\"$it\"" }.orEmpty()
            when (item) {
                is Heading -> {
                    val tag = when {
                        i == chapter.titleItem -> "h1"
                        anchor != null || headingSizes.indexOf(item.size) <= 1 -> "h2"
                        else -> "h3"
                    }
                    // A heading is bold by being one: its spans' own bold would only double it.
                    body.append("<$tag$id>").append(spans(item.spans.map { it.copy(bold = false) })).append("</$tag>")
                }
                is Paragraph -> {
                    if (i == chapter.titleItem || anchor != null) {
                        body.append("<h${if (i == chapter.titleItem) 1 else 2}$id>").append(spans(item.spans)).append("</h${if (i == chapter.titleItem) 1 else 2}>")
                    } else {
                        body.append(if (item.caption) "<p class=\"caption\">" else "<p>").append(spans(item.spans)).append("</p>")
                    }
                }
                is Verse -> {
                    body.append("<div class=\"verse\"$id>")
                    for ((indent, line) in item.lines) {
                        body.append(if (indent > 0) "<p class=\"line\" style=\"margin-left: ${indent}em\">" else "<p class=\"line\">")
                            .append(spans(line)).append("</p>")
                    }
                    body.append("</div>")
                }
                is Picture -> body.append("<figure$id><img src=\"${esc(pictureName(item.picture))}\" alt=\"\"/></figure>")
                is Note -> Unit
            }
        }
        val notes = chapter.items.filterIsInstance<Note>()
        if (notes.isNotEmpty()) {
            body.append("<section class=\"notes\"><h2>Notes</h2>")
            notes.forEach { body.append("<p class=\"note\">").append(spans(it.spans)).append("</p>") }
            body.append("</section>")
        }
        return EpubChapter(chapter.title, body.toString(), chapter.sections.withIndex().map { (i, s) -> s.first to "s${i + 1}" })
    }

    private fun spans(spans: List<Span>): String = spans.joinToString("") { s ->
        var t = esc(s.text)
        if (s.italic) t = "<i>$t</i>"
        if (s.bold) t = "<b>$t</b>"
        t
    }

    private const val CSS = """div.verse { margin: 1em 0; }
p.line { margin: 0; text-indent: 0; text-align: left; }
p.caption { font-size: 0.9em; font-style: italic; text-align: center; text-indent: 0; }
figure { margin: 1em 0; text-align: center; }
figure img { max-width: 100%; }
section.notes { font-size: 0.9em; margin-top: 2em; }
p.note { text-indent: 0; }
"""
}
