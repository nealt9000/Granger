package app.granger.core.books

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import java.io.File
import java.io.IOException
import java.util.Calendar

/** PdfBox-Android reads font metrics from the app's assets; call once, before the first PDF is read. */
object PdfSupport {
    fun init(context: Context) = PDFBoxResourceLoader.init(context.applicationContext)
}

/** Opens [file] with PdfBox, buffering in temp files rather than memory (scans run to hundreds of MB). */
internal fun loadPdf(file: File): PDDocument = PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly())

object PdfInspector {
    fun inspect(file: File, fallbackTitle: String = file.nameWithoutExtension): Inspection {
        val doc = try {
            loadPdf(file)
        } catch (e: InvalidPasswordException) {
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

    private fun inspect(doc: PDDocument, fallbackTitle: String): Inspection {
        // A PDF that needs a password never gets here (InvalidPasswordException above). One that opened is at most
        // owner-password encrypted: print and copy flags, readable by anyone, so it's read like any other PDF (Neal,
        // 2026-10-30: V for Vendetta on archive.org; this reverses the P2 spec's refusal of them).
        if (doc.numberOfPages == 0) return Inspection.Invalid("no pages")
        val info = doc.documentInformation
        fun String?.clean() = this?.trim().orEmpty()
        return Inspection.Ok(
            BookMeta(
                title = info.title.clean().ifEmpty { fallbackTitle },
                authors = info.author.clean().split(';', '&').map { it.trim() }.filter { it.isNotEmpty() },
                translators = emptyList(),
                date = info.creationDate?.let(::date).orEmpty(),
                language = doc.documentCatalog.language.clean(),
                subjects = listOf(info.subject.clean()).filter { it.isNotEmpty() },
                description = info.keywords.clean(),
                identifier = "",
                collection = null,
                cover = null,
                coverExtension = null,
                spine = emptyList(),
                pageCount = doc.numberOfPages,
                outline = outline(doc),
            ),
        )
    }

    /**
     * [file]'s outline, read with PdfBox: empty when it has none or can't be read. The reader asks for it when
     * PDFium's outline comes back empty, or with no entry it can place on a page.
     */
    fun outline(file: File): List<OutlineEntry> = try {
        loadPdf(file).use { if (it.isEncrypted) emptyList() else outline(it) }
    } catch (e: IOException) {
        emptyList()
    } catch (e: RuntimeException) {
        emptyList()
    }

    private fun date(c: Calendar) = "%04d-%02d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))

    /**
     * Depth-first, each entry with its depth (0 at the top). Entries whose destination isn't a page in this document
     * are skipped; PdfBox follows a destination given directly, by name, or through a GoTo action.
     */
    private fun outline(doc: PDDocument): List<OutlineEntry> {
        val root = doc.documentCatalog.documentOutline ?: return emptyList()
        val out = ArrayList<OutlineEntry>()
        fun walk(node: PDOutlineNode, depth: Int) {
            if (depth > MAX_OUTLINE_DEPTH || out.size >= MAX_OUTLINE) return
            for (item in node.children()) {
                val page = try { item.findDestinationPage(doc) } catch (_: IOException) { null }
                val index = page?.let { doc.pages.indexOf(it) } ?: -1
                val title = item.title?.trim().orEmpty()
                if (index >= 0 && title.isNotEmpty()) out += OutlineEntry(title, index + 1, depth)
                walk(item, depth + 1)
            }
        }
        walk(root, 0)
        return out
    }

    private const val MAX_OUTLINE = 2000
    private const val MAX_OUTLINE_DEPTH = 8
}
