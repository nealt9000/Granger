package app.granger.core.library

/** Where a book came from. [key] is the stable text used in book ids. */
enum class BookOrigin(val key: String) {
    GRANGER("granger"),
    IMPORT("import"),
    ARCHIVE_ORG("archive-org"),
    /** A PDF inside a Kiwix pack, opened from an article: a hidden row, so places and the rail work; not in My books. */
    PACK("pack"),
}

object BookIds {
    private const val PREFIX = "book:"

    fun of(origin: BookOrigin, ref: String): String = "$PREFIX${origin.key}:$ref"

    fun isBook(id: String): Boolean = id.startsWith(PREFIX)
}

/** False for a pack document: it opens like a book, but it belongs to its pack, not to My books or the shelves. */
val Book.onShelf: Boolean get() = origin != BookOrigin.PACK
