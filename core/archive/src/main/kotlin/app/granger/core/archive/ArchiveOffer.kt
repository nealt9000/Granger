package app.granger.core.archive

import java.time.LocalDate

/** [COMIC]: a comic book archive (CBZ or CBR), made into a PDF of its pages on the phone. */
enum class TextKind { EPUB, TEXT, OCR_TEXT, PDF, COMIC }

sealed interface Offer {
    /**
     * [file] of [kind] to download. [pdf] is the item's best PDF beside a text download (TEXT, OCR_TEXT, or an EPUB
     * archive.org made from a scan), which can be got as a second book (Plan P2); null otherwise.
     */
    data class Download(val file: ArchiveFile, val kind: TextKind, val pdf: ArchiveFile? = null) : Offer
    data object LendOnly : Offer
    data object Nothing : Offer
}

/**
 * Decides what an archive.org item offers. Private and LCP (DRM) files are never offered, and an item archive.org
 * restricts to lending is lend-only whatever its files (spec §5).
 */
object ArchiveOffers {
    /** The largest text Granger converts to an EPUB; a bigger one would verify and then never reach the Library. */
    const val MAX_TEXT_BYTES = 20L * 1024 * 1024

    fun choose(item: ArchiveItem, maxTextBytes: Long = MAX_TEXT_BYTES): Offer {
        if (item.restricted) return Offer.LendOnly
        val usable = item.files.filter(::isUsable)
        val pdf = bestPdf(usable)
        // The size cap is for text Granger converts in memory; a PDF is opened in place, at any size.
        val convertible = usable.filter { (it.sizeBytes ?: 0) <= maxTextBytes }
        val epubs = usable.filter { it.format == "EPUB" }

        // An EPUB or a text its uploader made is the book.
        epubs.filter { it.source == "original" }.maxByOrNull { it.sizeBytes ?: 0 }
            ?.let { return Offer.Download(it, TextKind.EPUB) }
        convertible.firstOrNull { it.source == "original" && it.format == "Text" }
            ?.let { return Offer.Download(it, TextKind.TEXT, pdf) }
        // What the uploader made otherwise, a comic or a PDF (a magazine, a scan, a born-digital book), reads better as
        // itself than as the EPUB archive.org makes from it: OCR text a page per file, with no chapters, and pictures
        // cut out of the page (Pixel 6 walkthrough, 2026-10-30). A multi-part item offers its first part.
        usable.filter { it.source == "original" && isComic(it) }.minByOrNull { it.name }
            ?.let { return Offer.Download(it, TextKind.COMIC) }
        bestPdf(usable.filter { it.source == "original" })
            ?.let { return Offer.Download(it, TextKind.PDF) }
        // archive.org's EPUB from a scan: its text for reading and Listen, with the scan's PDF beside it.
        epubs.maxByOrNull { it.sizeBytes ?: 0 }
            ?.let { return Offer.Download(it, TextKind.EPUB, pdf) }
        convertible.firstOrNull { it.format == "DjVuTXT" }
            ?.let { return Offer.Download(it, TextKind.OCR_TEXT, pdf) }

        if (item.files.any { it.isPrivate && isTextOrPdf(it) }) return Offer.LendOnly
        if (pdf != null) return Offer.Download(pdf, TextKind.PDF)
        return Offer.Nothing
    }

    /** A guess from search formats, before the item's metadata is fetched; the file is a placeholder. */
    fun preview(doc: ArchiveDoc): Offer? {
        val placeholder = ArchiveFile(name = "", format = "", sizeBytes = null, sha1 = null, md5 = null, source = "", isPrivate = false)
        val pdf = doc.formats.any { it.contains("PDF") && !it.startsWith("LCP Encrypted") }
        // archive.org makes an Image Container PDF only from an uploaded PDF, which choose() prefers to the EPUB.
        val uploadedPdf = "Image Container PDF" in doc.formats
        return when {
            doc.formats.any { it.startsWith("Comic Book") } && !doc.restricted -> Offer.Download(placeholder, TextKind.COMIC)
            uploadedPdf && !doc.restricted -> Offer.Download(placeholder, TextKind.PDF)
            "EPUB" in doc.formats && !doc.restricted -> Offer.Download(placeholder, TextKind.EPUB)
            "DjVuTXT" in doc.formats && !doc.restricted ->
                Offer.Download(placeholder, TextKind.OCR_TEXT, pdf = if (pdf) placeholder else null)
            doc.restricted -> Offer.LendOnly
            pdf -> Offer.Download(placeholder, TextKind.PDF)
            else -> null
        }
    }

    /**
     * The PDF to offer: a Text PDF first (archive.org's scan with its OCR text layer, so it is searchable), then an
     * Additional Text PDF, then an Image Container PDF (pictures only), then any other PDF; the smallest of the best.
     */
    fun bestPdf(usable: List<ArchiveFile>): ArchiveFile? =
        usable.filter { it.format.contains("PDF") }
            .minWithOrNull(compareBy<ArchiveFile> { PDF_PREFERENCE.indexOf(it.format).let { i -> if (i < 0) PDF_PREFERENCE.size else i } }
                .thenBy { it.sizeBytes ?: Long.MAX_VALUE })

    private val PDF_PREFERENCE = listOf("Text PDF", "Additional Text PDF", "Image Container PDF")

    /** Published 1930 or earlier. The uploader's licence tag is ignored: it isn't trusted (2026-10-17). */
    fun isPublicDomain(item: ArchiveItem): Boolean = item.year != null && item.year <= 1930

    /**
     * Published before January 1 of (current year − 100), and not before the first recordings: archive.org's year 0
     * and undated items fail. The same rule as the music search's rolling filter, checked again at Get.
     */
    fun isPublicDomainRecording(item: ArchiveItem, today: LocalDate): Boolean =
        item.year != null && item.year in PublicDomain.EARLIEST_RECORDING_YEAR..PublicDomain.lastRecordingYear(today)

    /** A comic book archive: archive.org's "Comic Book RAR"/"Comic Book ZIP", or a `.cbz`/`.cbr` it didn't name. */
    fun isComic(file: ArchiveFile): Boolean =
        file.format.startsWith("Comic Book") || file.name.lowercase().let { it.endsWith(".cbz") || it.endsWith(".cbr") }

    private fun isTextOrPdf(file: ArchiveFile): Boolean = file.format != "Metadata" && (
        file.format in setOf("EPUB", "Text", "DjVuTXT") || file.format.contains("PDF") || file.name.endsWith(".epub") || file.name.endsWith(".txt"))
}

/** Shared by the book and audio offers: never private, never LCP, verifiable, non-empty, and a safe name. */
internal fun isUsable(file: ArchiveFile): Boolean =
    !file.isPrivate &&
        !file.format.startsWith("LCP Encrypted") &&
        (file.sha1 != null || file.md5 != null) &&
        (file.sizeBytes ?: 0) > 0 &&
        isSafeName(file.name)

/** Names come from remote metadata and go into a download URL. */
internal fun isSafeName(name: String): Boolean =
    !name.startsWith("/") && name.split("/").none { it.isEmpty() || it == "." || it == ".." }
