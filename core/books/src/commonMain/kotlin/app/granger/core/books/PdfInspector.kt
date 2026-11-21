package app.granger.core.books

import kotlinx.io.IOException

object PdfInspector {
    /** The PDF at [path]; [fallbackTitle] when it names none. */
    fun inspect(path: String, fallbackTitle: String = stemOf(path)): Inspection {
        val doc = try {
            BookEngines.pdf.open(path)
        } catch (e: PdfPasswordException) {
            return Inspection.Protected(DrmKind.PDF_ENCRYPTED)
        } catch (e: IOException) {
            return Inspection.Invalid("Corrupt PDF: ${e.message}")
        } catch (e: RuntimeException) {
            return Inspection.Invalid("Corrupt PDF: ${e.message}")
        }
        return doc.use {
            try {
                inspect(it, fallbackTitle)
            } catch (e: IOException) {
                Inspection.Invalid("Corrupt PDF: ${e.message}")
            } catch (e: RuntimeException) {
                Inspection.Invalid("Corrupt PDF: ${e.message}")
            }
        }
    }

    private fun inspect(doc: PdfFile, fallbackTitle: String): Inspection {
        // A PDF that needs a password never gets here (PdfPasswordException above). One that opened is at most
        // owner-password encrypted: print and copy flags, readable by anyone, so it's read like any other PDF (Neal,
        // 2026-10-30: V for Vendetta on archive.org; this reverses the P2 spec's refusal of them).
        if (doc.pageCount == 0) return Inspection.Invalid("no pages")
        val info = doc.info()
        fun String?.clean() = this?.trim().orEmpty()
        return Inspection.Ok(
            BookMeta(
                title = info.title.clean().ifEmpty { fallbackTitle },
                authors = info.author.clean().split(';', '&').map { it.trim() }.filter { it.isNotEmpty() },
                translators = emptyList(),
                date = info.created.orEmpty(),
                language = info.language.clean(),
                subjects = listOf(info.subject.clean()).filter { it.isNotEmpty() },
                description = info.keywords.clean(),
                identifier = "",
                collection = null,
                cover = null,
                coverExtension = null,
                spine = emptyList(),
                pageCount = doc.pageCount,
                outline = doc.outline(),
            ),
        )
    }

    /**
     * The outline of the PDF at [path], read with the phone's PDF engine: empty when it has none or can't be read. The
     * reader asks for it when PDFium's outline comes back empty, or with no entry it can place on a page.
     */
    fun outline(path: String): List<OutlineEntry> = try {
        BookEngines.pdf.open(path).use { if (it.isEncrypted) emptyList() else it.outline() }
    } catch (e: IOException) {
        emptyList()
    } catch (e: RuntimeException) {
        emptyList()
    }
}
