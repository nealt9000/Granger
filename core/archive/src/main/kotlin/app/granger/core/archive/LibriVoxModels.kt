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
/** [title] is the chip's string resource. */
enum class LibriVoxShelf(val title: Int, val query: LibriVoxQuery) {
    RECENT(R.string.archive_librivox_shelf_recent, LibriVoxQuery.Recent),
    FICTION(R.string.archive_librivox_shelf_fiction, LibriVoxQuery.Genre("General Fiction")),
    POETRY(R.string.archive_librivox_shelf_poetry, LibriVoxQuery.Genre("Poetry")),
    CHILDREN(R.string.archive_librivox_shelf_children, LibriVoxQuery.Genre("Children's Fiction")),
    SCIENCE_FICTION(R.string.archive_librivox_shelf_science_fiction, LibriVoxQuery.Genre("Science Fiction")),
    DETECTIVE(R.string.archive_librivox_shelf_detective, LibriVoxQuery.Genre("Detective Fiction")),
    PHILOSOPHY(R.string.archive_librivox_shelf_philosophy, LibriVoxQuery.Genre("Philosophy")),
}

/** Why a LibriVox call failed, for the screen to word: [text] is its string resource, formatted with [LibriVoxError.code] for [ANSWERED]. */
enum class LibriVoxProblem(val text: Int) {
    NOT_FOUND(R.string.archive_librivox_error_not_found),
    UNREACHABLE(R.string.archive_librivox_error_unreachable),
    BUSY(R.string.archive_librivox_error_busy),
    ANSWERED(R.string.archive_librivox_error_answered),
    UNREADABLE(R.string.archive_librivox_error_unreadable),
}

/** [message] is the English wording, for logs; screens show [problem] through its string. [code] is the HTTP status. */
class LibriVoxError(val problem: LibriVoxProblem, message: String, val code: Int = 0) : Exception(message) {
    /** [LibriVoxProblem.text]'s format args. */
    val textArgs: List<Any> get() = if (problem == LibriVoxProblem.ANSWERED) listOf(code) else emptyList()
}
