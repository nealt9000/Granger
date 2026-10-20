package app.granger.core.archive

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

sealed interface LibriVoxOffer {
    /** [files] pairs each section, in order, with its archive.org file. [totalBytes] is their sum (the cover isn't counted). */
    data class Files(
        val book: LibriVoxBook,
        val item: ArchiveItem,
        val files: List<Pair<LibriVoxSection, ArchiveFile>>,
        val cover: ArchiveFile?,
        val totalBytes: Long,
    ) : LibriVoxOffer

    /** Some sections have no usable file in the item: the book isn't offered. */
    data class Incomplete(val missingSections: Int) : LibriVoxOffer

    /** No archive.org item yet (a brand-new book), or no sections to download. */
    data object NotOnArchive : LibriVoxOffer
}

/** Always the 64 kbps files (decided with Neal; no setting). */
object LibriVoxOffers {
    /**
     * Each section's file is the basename of its `listen_url` (the `*_64kb.mp3` LibriVox streams; `file_name` is often
     * null), which must be a usable file in [item]: verifiable, not private, not LCP. The cover is the item's largest
     * usable JPEG, else its Item Tile (`coverart_jpg` lives in another item and isn't used).
     */
    fun choose(book: LibriVoxBook, item: ArchiveItem): LibriVoxOffer {
        if (book.archiveIdentifier == null || book.sections.isEmpty()) return LibriVoxOffer.NotOnArchive
        val usable = item.files.filter(::isUsable).associateBy { it.name }
        val pairs = book.sections.map { section -> section to fileName(section.listenUrl)?.let(usable::get) }
        val missing = pairs.count { it.second == null }
        if (missing > 0) return LibriVoxOffer.Incomplete(missing)
        val files = pairs.map { (section, file) -> section to file!! }
        return LibriVoxOffer.Files(
            book = book,
            item = item,
            files = files,
            cover = ArchiveAudioOffers.cover(item),
            totalBytes = files.sumOf { it.second.sizeBytes ?: 0L },
        )
    }

    /** The decoded last path segment of [listenUrl], or null when it has none. */
    private fun fileName(listenUrl: String): String? =
        listenUrl.trim().toHttpUrlOrNull()?.pathSegments?.lastOrNull()?.takeIf { it.isNotEmpty() }
}
