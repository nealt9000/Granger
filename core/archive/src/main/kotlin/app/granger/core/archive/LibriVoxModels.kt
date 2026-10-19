package app.granger.core.archive

/** One recorded section (chapter) of a LibriVox book. [number] is LibriVox's `section_number`, which may start at 0. */
data class LibriVoxSection(
    val id: String,
    val number: Int,
    val title: String,
    /** `https://www.archive.org/download/<identifier>/<name>_64kb.mp3`; its basename names the file in the book's item. */
    val listenUrl: String,
    val playtimeMs: Long,
    val readers: List<String>,
)

data class LibriVoxBook(
    val id: String,
    /** As LibriVox catalogues it, with any leading article dropped ("Time Machine"). */
    val title: String,
    /** "H. G. Wells": first and last name. */
    val authors: List<String>,
    /** HTML stripped. Empty in search results. */
    val description: String,
    val language: String,
    /** Only [LibriVoxClient.book] fills these: the API sends genres only with `extended=1`. */
    val genres: List<String>,
    /** 0 for a book not yet on archive.org (`totaltime` "", `totaltimesecs` 0). */
    val totalTimeMs: Long,
    val copyrightYear: String,
    /** The last segment of `url_iarchive`, validated; null when there is none yet (→ [LibriVoxOffer.NotOnArchive]). */
    val archiveIdentifier: String?,
    /** `url_librivox`. */
    val pageUrl: String,
    /** Empty in search results. */
    val sections: List<LibriVoxSection>,
)

data class LibriVoxPage(val books: List<LibriVoxBook>, val hasMore: Boolean)

sealed interface LibriVoxQuery {
    /** Asked by title and by author (the text's last word), title matches first. */
    data class Text(val text: String) : LibriVoxQuery
    /** A LibriVox genre name, exactly as the API knows it: an unknown name is HTTP 500. */
    data class Genre(val name: String) : LibriVoxQuery
    /** Newest first (`sort_field=id&sort_order=desc`). */
    data object Recent : LibriVoxQuery
}

/** Recently added plus six genres (decided with Neal: the API has no popularity, so no "Most downloaded"). */
enum class LibriVoxShelf(val title: String, val query: LibriVoxQuery) {
    RECENT("Recently added", LibriVoxQuery.Recent),
    FICTION("Fiction", LibriVoxQuery.Genre("General Fiction")),
    POETRY("Poetry", LibriVoxQuery.Genre("Poetry")),
    CHILDREN("Children's", LibriVoxQuery.Genre("Children's Fiction")),
    SCIENCE_FICTION("Science fiction", LibriVoxQuery.Genre("Science Fiction")),
    DETECTIVE("Mystery & detective", LibriVoxQuery.Genre("Detective Fiction")),
    PHILOSOPHY("Philosophy", LibriVoxQuery.Genre("Philosophy")),
}

class LibriVoxError(message: String) : Exception(message)
