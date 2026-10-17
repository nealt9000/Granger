package app.granger.core.library

/** Decides where a pack or book is filed by default: which collection, named what, under which media type. */
object Filing {
    private class Rule(val matches: (String) -> Boolean, val key: String, val name: String)

    /** Mapping from `Pack.name` to a collection key and name; first match wins. */
    private val RULES = listOf(
        Rule({ it.startsWith("wikipedia_") }, "wikipedia", "Wikipedia"),
        Rule({ it.startsWith("wiktionary_") }, "wiktionary", "Wiktionary"),
        Rule({ it.startsWith("wikisource_") }, "wikisource", "Wikisource"),
        Rule({ it.startsWith("wikem_") }, "wikem", "WikEM"),
        Rule({ it.startsWith("gutenberg_") }, "gutenberg", "Gutenberg"),
        Rule({ it.startsWith("granger_classics") }, "granger-classics", "Granger Classics"),
        Rule({ it.startsWith("ifixit_") }, "ifixit", "iFixit"),
        Rule({ "stackexchange" in it || "stackoverflow" in it }, "stack-exchange", "Stack Exchange"),
    )

    /** Identity of a pack across updates: its ZIM `Name`, else its id. */
    fun packKey(pack: Pack): String = pack.name.ifBlank { pack.id }

    fun defaultKey(pack: Pack): String {
        if (pack.name.isBlank()) return "other"
        return RULES.firstOrNull { it.matches(pack.name) }?.key ?: "pack:${pack.name}"
    }

    /** The name of the collection with [key]; [pack] supplies the title for one-off `pack:` collections. */
    fun defaultName(key: String, pack: Pack): String = when {
        key == "other" -> "Other"
        key.startsWith("pack:") -> pack.title
        else -> RULES.firstOrNull { it.key == key }?.name ?: pack.title
    }

    /** Every ZIM is reading material. */
    @Suppress("UNUSED_PARAMETER")
    fun defaultMediaType(pack: Pack): String = "literature"

    /** Books are filed by origin; these are their collections' keys and names. */
    private val BOOK_COLLECTIONS = mapOf(
        BookOrigin.GRANGER to ("granger-classics" to "Granger Classics"),
        BookOrigin.IMPORT to ("my-books" to "My books"),
        BookOrigin.ARCHIVE_ORG to ("archive-org" to "Archive.org"),
    )

    /** Identity of a book across re-downloads: its id, which is derived from origin and source. */
    fun bookKey(book: Book): String = book.id

    fun defaultKey(book: Book): String = BOOK_COLLECTIONS.getValue(book.origin).first

    fun defaultName(key: String, book: Book): String =
        BOOK_COLLECTIONS.values.firstOrNull { it.first == key }?.second ?: book.title

    /** Every book is reading material. */
    @Suppress("UNUSED_PARAMETER")
    fun defaultMediaType(book: Book): String = "literature"

    /** The old Classics ZIM, replaced by the Granger Classics books. Only reported; nothing is hidden or deleted here. */
    fun isSuperseded(pack: Pack): Boolean = pack.name == "granger_classics_en"
}
