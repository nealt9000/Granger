package app.granger.core.library

/** Where a book came from. [key] is the stable text used in book ids. */
enum class BookOrigin(val key: String) {
    GRANGER("granger"),
    IMPORT("import"),
    ARCHIVE_ORG("archive-org"),
}

object BookIds {
    private const val PREFIX = "book:"

    fun of(origin: BookOrigin, ref: String): String = "$PREFIX${origin.key}:$ref"

    fun isBook(id: String): Boolean = id.startsWith(PREFIX)
}
