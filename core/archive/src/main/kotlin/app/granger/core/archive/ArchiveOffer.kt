package app.granger.core.archive

enum class TextKind { EPUB, TEXT, OCR_TEXT }

sealed interface Offer {
    data class Download(val file: ArchiveFile, val kind: TextKind, val alsoPdf: Boolean) : Offer
    data object PdfOnly : Offer
    data object LendOnly : Offer
    data object Nothing : Offer
}

/** Decides what an archive.org item offers. Private and LCP (DRM) files are never offered. */
object ArchiveOffers {
    fun choose(item: ArchiveItem): Offer {
        val usable = item.files.filter(::isUsable)
        val pdf = usable.any { it.format.contains("PDF") }

        usable.filter { it.format == "EPUB" }.maxByOrNull { it.sizeBytes ?: 0 }
            ?.let { return Offer.Download(it, TextKind.EPUB, alsoPdf = false) }
        usable.firstOrNull { it.source == "original" && (it.format == "Text" || (it.name.endsWith(".txt") && it.format == "Plain Text")) }
            ?.let { return Offer.Download(it, TextKind.TEXT, pdf) }
        usable.firstOrNull { it.format == "DjVuTXT" }
            ?.let { return Offer.Download(it, TextKind.OCR_TEXT, pdf) }

        if (item.restricted || item.files.any { it.isPrivate && isTextOrPdf(it) }) return Offer.LendOnly
        if (pdf) return Offer.PdfOnly
        return Offer.Nothing
    }

    /** A guess from search formats, before the item's metadata is fetched; the file is a placeholder. */
    fun preview(doc: ArchiveDoc): Offer? {
        val placeholder = ArchiveFile(name = "", format = "", sizeBytes = null, sha1 = null, md5 = null, source = "", isPrivate = false)
        return when {
            "EPUB" in doc.formats && !doc.restricted -> Offer.Download(placeholder, TextKind.EPUB, alsoPdf = false)
            "DjVuTXT" in doc.formats && !doc.restricted ->
                Offer.Download(placeholder, TextKind.OCR_TEXT, alsoPdf = doc.formats.any { it.contains("PDF") })
            doc.restricted -> Offer.LendOnly
            doc.formats.any { it.contains("PDF") } -> Offer.PdfOnly
            else -> null
        }
    }

    fun isPublicDomain(item: ArchiveItem): Boolean =
        (item.year != null && item.year <= 1930) || "publicdomain" in item.licenseUrl.lowercase()

    private fun isUsable(file: ArchiveFile): Boolean =
        !file.isPrivate &&
            !file.format.startsWith("LCP Encrypted") &&
            (file.sha1 != null || file.md5 != null) &&
            (file.sizeBytes ?: 0) > 0 &&
            isSafeName(file.name)

    /** Names come from remote metadata and go into a download URL. */
    private fun isSafeName(name: String): Boolean =
        !name.startsWith("/") && name.split("/").none { it.isEmpty() || it == "." || it == ".." }

    private fun isTextOrPdf(file: ArchiveFile): Boolean =
        file.format in setOf("EPUB", "Text", "DjVuTXT") || file.format.contains("PDF") || file.name.endsWith(".epub") || file.name.endsWith(".txt")
}
