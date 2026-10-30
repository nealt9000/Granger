package app.granger.core.library

/** Where a book came from. [key] is the stable text used in book ids. */
enum class BookOrigin(val key: String) {
    GRANGER("granger"),
    IMPORT("import"),
    ARCHIVE_ORG("archive-org"),
    /** A PDF inside a Kiwix pack, opened from an article: a hidden row, so places and the rail work; not in My books. */
    PACK("pack"),

    /**
     * The PDF an ebook was made from (Plan IA), opened from the ebook's Original pages: a hidden row like [PACK]. Its ref
     * is the ebook's id without `book:`.
     */
    PAGES("pages"),
}

object BookIds {
    private const val PREFIX = "book:"

    fun of(origin: BookOrigin, ref: String): String = "$PREFIX${origin.key}:$ref"

    fun isBook(id: String): Boolean = id.startsWith(PREFIX)

    /** The id of the original pages of the ebook [bookId] (Plan IA). */
    fun pagesOf(bookId: String): String = of(BookOrigin.PAGES, bookId.removePrefix(PREFIX))
}

/**
 * False for a pack document (it opens like a book, but it belongs to its pack) and for an ebook's original pages (they
 * belong to the ebook): not on the shelves.
 */
val Book.onShelf: Boolean get() = origin != BookOrigin.PACK && origin != BookOrigin.PAGES
