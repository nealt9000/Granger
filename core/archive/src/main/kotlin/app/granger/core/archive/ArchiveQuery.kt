package app.granger.core.archive

enum class ArchiveShelf(val title: String, val clause: String) {
    MOST_DOWNLOADED("Most downloaded", ""),
    MANUALS("Technical manuals", "collection:manuals"),
    SCIENCE("Science & engineering", "subject:(science OR engineering OR mechanics OR chemistry OR physics)"),
    LITERATURE("Literature", "subject:(fiction OR literature OR poetry OR drama)"),
}

object ArchiveQuery {
    /**
     * Date only: published 1930 or earlier. Changed 2026-10-17 after the Pixel 6 walkthrough: uploader licence tags
     * are not trusted (modern books tagged public domain by their uploaders showed up in the filtered view).
     */
    const val PUBLIC_DOMAIN = "year:[* TO 1930]"

    private val identifier = Regex("[A-Za-z0-9._-]+")

    fun build(text: String, shelf: ArchiveShelf?, filtered: Boolean): String {
        val cleaned = balance(text).trim()
        return buildString {
            append("mediatype:texts")
            if (cleaned.isNotEmpty()) append(" AND (").append(cleaned).append(')')
            if (shelf != null && shelf.clause.isNotEmpty()) append(" AND ").append(shelf.clause)
            if (filtered) append(" AND ").append(PUBLIC_DOMAIN)
        }
    }

    fun isValidIdentifier(id: String): Boolean = identifier.matches(id) && !id.startsWith(".")

    /**
     * Drops all quotes if they don't pair up, and every parenthesis: counting them can't tell one inside a quoted
     * phrase from one outside, so any of them could close the `AND (…)` group early.
     */
    private fun balance(text: String): String {
        var out = text.replace("(", "").replace(")", "")
        if (out.count { it == '"' } % 2 != 0) out = out.replace("\"", "")
        return out
    }
}
