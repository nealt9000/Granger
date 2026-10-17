package app.granger.core.archive

enum class ArchiveShelf(val title: String, val clause: String) {
    MOST_DOWNLOADED("Most downloaded", ""),
    MANUALS("Technical manuals", "collection:manuals"),
    SCIENCE("Science & engineering", "subject:(science OR engineering OR mechanics OR chemistry OR physics)"),
    LITERATURE("Literature", "subject:(fiction OR literature OR poetry OR drama)"),
}

object ArchiveQuery {
    const val PUBLIC_DOMAIN = "(year:[* TO 1930] OR licenseurl:*publicdomain*)"

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

    /** Drops all quotes if they don't pair up, and all parentheses if they don't nest. */
    private fun balance(text: String): String {
        var out = text
        if (out.count { it == '"' } % 2 != 0) out = out.replace("\"", "")
        var depth = 0
        var balanced = true
        for (c in out) {
            if (c == '(') depth++
            if (c == ')' && --depth < 0) { balanced = false; break }
        }
        if (!balanced || depth != 0) out = out.replace("(", "").replace(")", "")
        return out
    }
}
