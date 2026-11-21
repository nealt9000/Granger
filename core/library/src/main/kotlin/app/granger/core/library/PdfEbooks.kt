package app.granger.core.library

import app.granger.core.books.BookMeta
import app.granger.core.books.EpubInspector
import app.granger.core.books.Inspection
import app.granger.core.books.PdfToEpub
import app.granger.core.books.convert
import app.granger.core.books.inspect
import java.io.File

/**
 * An ebook made from a PDF (Plan IA): `x.pdf.epub` beside `x.pdf`, which stays as the book's original pages. One
 * conversion for "Read as ebook" ([RoomBookshelf.readAsEbook]) and for bringing such a pack book back
 * ([PackBooks.restore], Plan KB2 Task 2.5).
 */
internal object PdfEbooks {
    const val SUFFIX = ".pdf.epub"

    /** Where the ebook made from [pdf] goes. */
    fun ebookOf(pdf: File): File = File(pdf.parentFile, pdf.name + ".epub")

    /** The PDF [ebook] was made from: `x.pdf` for `x.pdf.epub`; null for an ebook that wasn't. */
    fun pagesOf(ebook: File): File? =
        ebook.takeIf { it.name.endsWith(SUFFIX, ignoreCase = true) }?.let { File(it.parentFile, it.name.dropLast(".epub".length)) }

    /**
     * Converts [pdf] to [ebookOf] it. The meta is there only when the conversion passes and the ebook opens; an ebook
     * that doesn't open is deleted, and the report then says so.
     */
    fun make(pdf: File, title: String, author: String, language: String): Pair<PdfToEpub.Report, BookMeta?> {
        val target = ebookOf(pdf)
        val report = PdfToEpub.convert(pdf, target, title, author, language)
        if (!report.passes) return report to null
        val meta = (EpubInspector.inspect(target) as? Inspection.Ok)?.meta
            ?: return report.copy(passes = false, why = "the ebook didn't open").also { target.delete() } to null
        return report to meta
    }
}
