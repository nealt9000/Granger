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

    /**
     * A book in a Project Gutenberg pack (Plan GB), ref its Gutenberg id: one row whichever pack or snapshot holds it.
     * Listed from the pack's own list; its EPUB is copied out of the pack the first time it's opened.
     */
    GUTENBERG("gutenberg"),
    /**
     * A document in a Kiwix "nautilus" pack (Plan KB: Food for Preppers, Water, Prunelle's books), ref
     * `<pack name>/<file>`: listed from the pack's own `database.js`, PDF or EPUB, copied out when first opened.
     */
    NAUTILUS("nautilus"),
    /**
     * A book in a Kiwix pack with a book manifest (Plan KB2: Military Medicine, Survivor Library…), ref
     * `<pack name>/<key>`, the manifest's key, stable across snapshots; or a pure container's file, ref
     * `<pack name>/<path>`. PDF or EPUB, copied out when first opened.
     */
    KIWIX("kiwix"),
}

/** A book listed from a book pack's own list ([PackBooks]): its row follows the pack, its file is copied out on open. */
val BookOrigin.isPackBook: Boolean get() = this == BookOrigin.GUTENBERG || this == BookOrigin.NAUTILUS || this == BookOrigin.KIWIX

object BookIds {
    private const val PREFIX = "book:"

    fun of(origin: BookOrigin, ref: String): String = "$PREFIX${origin.key}:$ref"

    fun isBook(id: String): Boolean = id.startsWith(PREFIX)

    /** The id of the original pages of the ebook [bookId] (Plan IA). */
    fun pagesOf(bookId: String): String = of(BookOrigin.PAGES, bookId.removePrefix(PREFIX))
}

/**
 * False for a pack document (it opens like a book, but it belongs to its pack), for an ebook's original pages (they
 * belong to the ebook) and for a pack book the reader removed ([Book.hidden]): not on the shelves.
 */
val Book.onShelf: Boolean get() = origin != BookOrigin.PACK && origin != BookOrigin.PAGES && !hidden
