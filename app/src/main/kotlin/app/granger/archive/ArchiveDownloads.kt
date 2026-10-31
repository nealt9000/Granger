package app.granger.archive

import app.granger.core.archive.ArchiveItem
import app.granger.core.archive.ArchiveOrgClient
import app.granger.core.archive.ArchiveQuery
import app.granger.core.archive.Offer
import app.granger.core.archive.Part
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
    /**
     * Joins an item's identifier and a part's number (Plan IA): `<identifier>~03` is the key of part 3, in its item id,
     * its file's name, its sidecar's and its book's ref. archive.org identifiers never hold a `~`.
     */
    const val PART_SEPARATOR = '~'

    /** Separates a part's title from its item's in the part's book title: "Batman (1986) · 02 (of 04)". */
    const val PART_TITLE_SEPARATOR = " · "
    private const val OCR_NOTE = "OCR text from a scan: may have errors. "
    private val json = Json { ignoreUnknownKeys = true }

    /** [key] is an identifier, or a part's key ([partKey]). */
    fun itemId(key: String) = PREFIX + key

    /** The download of the PDF beside [identifier]'s text. */
    fun pdfItemId(identifier: String) = PREFIX + identifier + PDF_SUFFIX

    /** Part [number] of an item with [count] parts: `<identifier>~03`, zero-padded to [count]'s width (at least two). */
    fun partKey(identifier: String, number: Int, count: Int): String =
        identifier + PART_SEPARATOR + number.toString().padStart(maxOf(2, count.toString().length), '0')

    /** The item's identifier, for its text download, the PDF beside it and each of its parts alike. */
    fun identifierOf(itemId: String): String? =
        if (itemId.startsWith(PREFIX)) itemId.removePrefix(PREFIX).removeSuffix(PDF_SUFFIX).substringBefore(PART_SEPARATOR) else null

    /** The part a download is, or null for an item's one file (and the PDF beside a text). */
    fun partNumberOf(itemId: String): Int? =
        itemId.takeIf { it.startsWith(PREFIX) }?.substringAfter(PART_SEPARATOR, "")?.toIntOrNull()

    fun isPdfCopy(itemId: String): Boolean = itemId.startsWith(PREFIX) && itemId.endsWith(PDF_SUFFIX)

    /** The book's source ref for a download: the identifier, with [PDF_SUFFIX] for the PDF beside a text, or a part's key. */
    fun bookRefOf(itemId: String): String? = if (itemId.startsWith(PREFIX)) itemId.removePrefix(PREFIX) else null

    /** Whose sidecar a download is filed with: a part's own, else its item's (the PDF beside a text shares the text's). */
    fun sidecarKeyOf(itemId: String): String? = bookRefOf(itemId)?.removeSuffix(PDF_SUFFIX)

    /** The download group of [identifier]'s parts, for Cancel all and Retry all on the Downloads page. */
    fun setGroupId(identifier: String) = PREFIX + identifier

    /** The PDF beside a text is filed as its own book: "(PDF)" after the title, and none of the OCR text's note. */
    fun pdfCopyDetails(details: BookDetails): BookDetails =
        details.copy(title = "${details.title} (PDF)", description = details.description.removePrefix(OCR_NOTE))

    /**
     * The spec for [offer]'s main file, with [pdfCopy] for the PDF beside its text ([Offer.Download.pdf]), or with
     * [part] for one part of a set (Plan IA): its own key, file and title, in the set's download group.
     */
    fun spec(item: ArchiveItem, offer: Offer.Download, client: ArchiveOrgClient, pdfCopy: Boolean = false, part: Part? = null): DownloadSpec {
        val identifier = item.identifier
        require(ArchiveQuery.isValidIdentifier(identifier)) { "Bad archive.org identifier" }
        require(part == null || !pdfCopy) { "A part has no PDF beside it" }
        val file = when {
            pdfCopy -> requireNotNull(offer.pdf) { "No PDF beside this item's text" }
            part != null -> part.file
            else -> offer.file
        }
        val (hash, algorithm) = when {
            !file.sha1.isNullOrBlank() -> file.sha1!! to HashAlgorithm.SHA1
            !file.md5.isNullOrBlank() -> file.md5!! to HashAlgorithm.MD5
            else -> throw IllegalArgumentException("File has neither sha1 nor md5")
        }
        val extension = when {
            pdfCopy || offer.kind == TextKind.PDF -> "pdf"
            offer.kind == TextKind.EPUB -> "epub"
            // Filed as a PDF of its pages beside it (Bookshelf.addDownloaded); the name only has to say "comic".
            offer.kind == TextKind.COMIC -> if (file.format.contains("RAR") || file.name.endsWith(".cbr", ignoreCase = true)) "cbr" else "cbz"
            else -> "txt"
        }
        val title = item.title.ifBlank { identifier }
        val key = part?.let { partKey(identifier, it.number, offer.parts.size) } ?: identifier
        return DownloadSpec(
            itemId = if (pdfCopy) pdfItemId(identifier) else itemId(key),
            kind = ItemKind.BOOK,
            title = when {
                pdfCopy -> "$title (PDF)"
                part != null -> title + PART_TITLE_SEPARATOR + part.title
                else -> title
            },
            urls = listOf(client.downloadUrl(identifier, file.name)),
            sizeBytes = file.sizeBytes ?: 0L,
            hash = hash,
            hashAlgorithm = algorithm,
            relativePath = "books/archive-org/$key.$extension",
            snapshotDate = "",
            capped = true,
            groupId = part?.let { setGroupId(identifier) },
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

    /** Writes `<dir>/<identifier>.json`, or a [part]'s `<dir>/<identifier>~03.json` titled for it, and returns it. */
    fun writeSidecar(dir: File, item: ArchiveItem, kind: TextKind, part: Part? = null, partCount: Int = 0): File {
        require(ArchiveQuery.isValidIdentifier(item.identifier)) { "Bad archive.org identifier" }
        dir.mkdirs()
        val base = details(item, kind)
        val d = if (part == null) base else base.copy(title = base.title.ifBlank { item.identifier } + PART_TITLE_SEPARATOR + part.title)
        val file = sidecarFile(dir, part?.let { partKey(item.identifier, it.number, partCount) } ?: item.identifier)
        file.writeText(json.encodeToString(Sidecar.serializer(),
            Sidecar(d.title, d.author, d.date, d.subjects, d.description, d.language, kind.name)))
        return file
    }

    /** [key] is an identifier or a part's key. */
    fun sidecarFile(dir: File, key: String): File = File(dir, "$key.json")

    /**
     * Deletes `<dir>/<key>.json`. A sidecar stays for as long as its book does (a repair re-files with it),
     * so this runs only when the item (or the part) is removed or cancelled, or its enqueue is refused.
     */
    fun deleteSidecar(dir: File, key: String) {
        if (ArchiveQuery.isValidIdentifier(key.substringBefore(PART_SEPARATOR))) sidecarFile(dir, key).delete()
    }

    /** Null when the file is missing or unreadable. */
    fun readSidecar(file: File): Pair<BookDetails, TextKind>? = try {
        val s = json.decodeFromString(Sidecar.serializer(), file.readText())
        BookDetails(s.title, s.creator, s.date, s.subjects, s.description, s.language) to TextKind.valueOf(s.kind)
    } catch (e: Exception) {
        null
    }
}
