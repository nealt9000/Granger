package app.granger.archive

import app.granger.core.archive.ArchiveItem
import app.granger.core.archive.ArchiveOrgClient
import app.granger.core.archive.ArchiveQuery
import app.granger.core.archive.Offer
import app.granger.core.archive.TextKind
import app.granger.core.downloads.DownloadSpec
import app.granger.core.downloads.HashAlgorithm
import app.granger.core.downloads.ItemKind
import app.granger.core.library.BookDetails
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Builds archive.org download specs and keeps the item metadata that the finished download is filed with. */
object ArchiveDownloads {
    const val PREFIX = "archive-org:"
    /**
     * Marks the PDF got beside an item's text (Plan P2): its own download and its own book, so the text and the
     * scan sit side by side. A PDF-only item's PDF is the item's one download and has no suffix.
     */
    const val PDF_SUFFIX = "#pdf"
    private const val OCR_NOTE = "OCR text from a scan: may have errors. "
    private val json = Json { ignoreUnknownKeys = true }

    fun itemId(identifier: String) = PREFIX + identifier

    /** The download of the PDF beside [identifier]'s text. */
    fun pdfItemId(identifier: String) = PREFIX + identifier + PDF_SUFFIX

    /** The item's identifier, for its text download and the PDF beside it alike. */
    fun identifierOf(itemId: String): String? =
        if (itemId.startsWith(PREFIX)) itemId.removePrefix(PREFIX).removeSuffix(PDF_SUFFIX) else null

    fun isPdfCopy(itemId: String): Boolean = itemId.startsWith(PREFIX) && itemId.endsWith(PDF_SUFFIX)

    /** The book's source ref for a download: the identifier, with [PDF_SUFFIX] for the PDF beside a text. */
    fun bookRefOf(itemId: String): String? = identifierOf(itemId)?.let { if (isPdfCopy(itemId)) it + PDF_SUFFIX else it }

    /** The PDF beside a text is filed as its own book: "(PDF)" after the title, and none of the OCR text's note. */
    fun pdfCopyDetails(details: BookDetails): BookDetails =
        details.copy(title = "${details.title} (PDF)", description = details.description.removePrefix(OCR_NOTE))

    /** The spec for [offer]'s main file, or with [pdfCopy] for the PDF beside its text ([Offer.Download.pdf]). */
    fun spec(item: ArchiveItem, offer: Offer.Download, client: ArchiveOrgClient, pdfCopy: Boolean = false): DownloadSpec {
        val identifier = item.identifier
        require(ArchiveQuery.isValidIdentifier(identifier)) { "Bad archive.org identifier" }
        val file = if (pdfCopy) requireNotNull(offer.pdf) { "No PDF beside this item's text" } else offer.file
        val (hash, algorithm) = when {
            !file.sha1.isNullOrBlank() -> file.sha1!! to HashAlgorithm.SHA1
            !file.md5.isNullOrBlank() -> file.md5!! to HashAlgorithm.MD5
            else -> throw IllegalArgumentException("File has neither sha1 nor md5")
        }
        val extension = when {
            pdfCopy || offer.kind == TextKind.PDF -> "pdf"
            offer.kind == TextKind.EPUB -> "epub"
            else -> "txt"
        }
        val title = item.title.ifBlank { identifier }
        return DownloadSpec(
            itemId = if (pdfCopy) pdfItemId(identifier) else itemId(identifier),
            kind = ItemKind.BOOK,
            title = if (pdfCopy) "$title (PDF)" else title,
            urls = listOf(client.downloadUrl(identifier, file.name)),
            sizeBytes = file.sizeBytes ?: 0L,
            hash = hash,
            hashAlgorithm = algorithm,
            relativePath = "books/archive-org/$identifier.$extension",
            snapshotDate = "",
            capped = true,
        )
    }

    fun details(item: ArchiveItem, kind: TextKind): BookDetails = BookDetails(
        title = item.title,
        author = item.creator,
        date = item.date,
        subjects = item.subjects,
        description = if (kind == TextKind.OCR_TEXT) OCR_NOTE + item.description else item.description,
        language = item.language,
    )

    @Serializable
    private class Sidecar(
        val title: String,
        val creator: String,
        val date: String,
        val subjects: List<String>,
        val description: String,
        val language: String,
        val kind: String,
    )

    /** Writes `<dir>/<identifier>.json` and returns it. */
    fun writeSidecar(dir: File, item: ArchiveItem, kind: TextKind): File {
        require(ArchiveQuery.isValidIdentifier(item.identifier)) { "Bad archive.org identifier" }
        dir.mkdirs()
        val d = details(item, kind)
        val file = sidecarFile(dir, item.identifier)
        file.writeText(json.encodeToString(Sidecar.serializer(),
            Sidecar(d.title, d.author, d.date, d.subjects, d.description, d.language, kind.name)))
        return file
    }

    fun sidecarFile(dir: File, identifier: String): File = File(dir, "$identifier.json")

    /**
     * Deletes `<dir>/<identifier>.json`. A sidecar stays for as long as its book does (a repair re-files with it),
     * so this runs only when the item is removed or cancelled, or its enqueue is refused.
     */
    fun deleteSidecar(dir: File, identifier: String) {
        if (ArchiveQuery.isValidIdentifier(identifier)) sidecarFile(dir, identifier).delete()
    }

    /** Null when the file is missing or unreadable. */
    fun readSidecar(file: File): Pair<BookDetails, TextKind>? = try {
        val s = json.decodeFromString(Sidecar.serializer(), file.readText())
        BookDetails(s.title, s.creator, s.date, s.subjects, s.description, s.language) to TextKind.valueOf(s.kind)
    } catch (e: Exception) {
        null
    }
}
