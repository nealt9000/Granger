package app.granger.core.library

import app.granger.core.zim.ZimArchive

/**
 * One book a book pack lists. [ref] is its id within [PackBookSource.origin] (`book:<origin>:<ref>`), [path] its entry
 * in the pack. [description] null leaves a row's description as it is.
 */
data class PackItem(
    val ref: String,
    val path: String,
    val title: String,
    val author: String,
    val format: BookFormat,
    val sizeBytes: Long,
    val description: String? = null,
    /** Its language, when the pack's list says; null takes the row's own, else the pack's. */
    val language: String? = null,
    /** Where it sits in a manifest collection (Plan KB2): see [Book.groupName] and the fields after it. */
    val groupName: String? = null,
    val ord: Int? = null,
    val workKey: String? = null,
    val workTitle: String? = null,
    val partNo: Int? = null,
)

/** One book of a book pack, as [PackBookList] lists it: its entry [path], title, author, format, size and section. */
data class PackBookEntry(
    val path: String,
    val title: String,
    val author: String,
    val format: BookFormat,
    val sizeBytes: Long,
    /** Its section in a manifest collection; null when the pack has none. */
    val group: String?,
)

/**
 * A book pack's books, read from the pack's own list (Plan I4b Task 8), on both phones: Project Gutenberg packs from
 * `full_by_title.js` ([GutenbergList]), nautilus packs from `database.js` ([NautilusDatabase]), Kiwix packs from their
 * `.gbooks` manifest ([BookManifest]) or, for a pure container ([Pack.BOOK_FILES]), from their files' names
 * ([BookFiles]). Android's `PackBooks` sources list through these, so the order and titles are the ones its Library
 * files them by.
 */
object PackBookList {
    /** [pack]'s books from [archive], in the pack's order; null when [pack] isn't a book pack or its list can't be read. */
    fun list(pack: Pack, archive: ZimArchive): List<PackBookEntry>? = items(pack, archive)?.map {
        PackBookEntry(it.path, it.title, it.author, it.format, it.sizeBytes, it.groupName?.ifBlank { null })
    }

    /** [pack]'s books as Android's shelf rows take them, by its [bookSource]; null as for [list]. */
    fun items(pack: Pack, archive: ZimArchive): List<PackItem>? = when (pack.bookSource) {
        BookOrigin.GUTENBERG -> gutenberg(archive)
        BookOrigin.NAUTILUS -> nautilus(pack, archive)
        BookOrigin.KIWIX -> manifest(pack, archive)
        else -> null
    }

    /**
     * A Project Gutenberg pack's books, each by its Gutenberg id ([PackItem.ref]), from its `full_by_title.js`; a listed
     * book with no EPUB in the pack is left out.
     */
    fun gutenberg(archive: ZimArchive): List<PackItem>? {
        val js = archive.read(GutenbergList.PATH)?.bytes ?: return null
        return GutenbergList.parse(js.decodeToString()).mapNotNull { listing ->
            val path = GutenbergList.epubPath(archive, listing) ?: return@mapNotNull null
            PackItem(listing.gutenbergId, path, listing.title, listing.author, BookFormat.EPUB, archive.entry(path)?.sizeBytes ?: 0L)
        }
    }

    /**
     * A nautilus pack's documents: each file of each record of its `database.js`, `<pack name>/<fp>`, titled by its record
     * ("Title (2 of 3)" for a record with several files), PDF or EPUB by the file's type. A file the pack doesn't hold, or
     * that is neither, is left out.
     */
    fun nautilus(pack: Pack, archive: ZimArchive): List<PackItem>? {
        val js = archive.read(NautilusDatabase.PATH)?.bytes ?: return null
        val items = ArrayList<PackItem>()
        for (record in NautilusDatabase.records(js.decodeToString())) {
            val count = record.files.size
            for ((i, fp) in record.files.withIndex()) {
                val format = formatOfName(fp) ?: continue
                val path = NautilusDatabase.FILES + fp
                val entry = archive.entry(path) ?: continue
                items += PackItem(
                    ref = "${pack.name}/$fp",
                    path = entry.path,
                    title = if (count > 1) "${record.title} (${i + 1} of $count)" else record.title,
                    author = record.author,
                    format = format,
                    sizeBytes = entry.sizeBytes,
                    description = record.description,
                )
            }
        }
        return items
    }

    /**
     * A Kiwix book pack's books (Plan KB2): from the manifest at [Pack.bookManifest] (checked against this exact pack
     * file before it was set, so its items are trusted without looking each up), or, for [Pack.BOOK_FILES], every PDF
     * and EPUB in the pack titled by its file name. Each is `<pack name>/<key>`.
     */
    fun manifest(pack: Pack, archive: ZimArchive): List<PackItem>? {
        val manifest = pack.bookManifest ?: return null
        if (manifest == Pack.BOOK_FILES) {
            val items = ArrayList<PackItem>()
            val walked = archive.list(BookFiles.MIMES) { e ->
                val format = BookFiles.formatOf(e.mimeType)
                if (format != null) {
                    items += PackItem(
                        ref = "${pack.name}/${e.path}", path = e.path, title = BookFiles.titleOf(e.path), author = "",
                        format = format, sizeBytes = e.sizeBytes, ord = items.size,
                    )
                }
                true
            }
            return items.takeIf { walked }
        }
        val items = BookManifest.items(manifest) ?: return null
        // A whole file with parts is marked as the head of its own work, so its parts fold under it.
        val wholes = items.mapNotNullTo(HashSet()) { it.partOf }
        return items.map { item ->
            PackItem(
                ref = "${pack.name}/${item.key}",
                path = item.path,
                title = item.title.ifBlank { BookFiles.titleOf(item.path) },
                author = item.author,
                format = item.format,
                sizeBytes = item.bytes,
                description = item.description,
                language = item.language.ifBlank { null },
                groupName = item.groupName,
                ord = item.ord,
                workKey = item.partOf ?: item.workKey ?: item.key.takeIf { it in wholes },
                workTitle = item.workTitle,
                partNo = item.partNo,
            )
        }
    }

    /** A listed file's format by its name; null for a file the reader can't open. */
    fun formatOfName(name: String): BookFormat? = when {
        name.endsWith(".pdf", ignoreCase = true) -> BookFormat.PDF
        name.endsWith(".epub", ignoreCase = true) -> BookFormat.EPUB
        else -> null
    }
}
