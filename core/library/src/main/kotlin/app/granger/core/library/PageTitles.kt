package app.granger.core.library

/**
 * A page's title without the site it came from (Plan U3b Decision 11). A ZIM page keeps its web `<title>`, so a tab
 * or card would read "Apocalypse - Wikipedia", "Night feeds - Parenting Stack Exchange" or "Pancakes | Based Cooking";
 * the pack is already named beside it.
 */
object PageTitles {
    /** " - " with a hyphen, en or em dash. */
    private const val DASH = "\\s+[-–—]\\s+"
    private val WIKIPEDIA = Regex("${DASH}Wikipedia$", RegexOption.IGNORE_CASE)
    private val STACK_EXCHANGE = Regex("$DASH[^-–—|]*Stack Exchange$", RegexOption.IGNORE_CASE)
    private val SITE = Regex("\\s+\\|\\s+[^|]+$")

    /** What packs call their front page; a tab named so says nothing, so it takes the pack's name. */
    private val MAIN_PAGE_NAMES = setOf("main page", "home", "home page", "index")

    /**
     * [title] with a trailing " - Wikipedia", " - <X> Stack Exchange", " | <site>" or " - <packTitle>" taken off. A
     * title that is only the site's name is left as it is. The pack's [mainPage], or a page titled only "Main Page",
     * "Home" or "Index", is called by [packTitle] when there is one (WikEM's front page is "Main Page").
     */
    fun clean(title: String, packTitle: String? = null, mainPage: Boolean = false): String {
        val trimmed = title.trim()
        var cleaned = trimmed
        while (true) {
            val next = strip(cleaned, packTitle)
            if (next == cleaned) break
            cleaned = next
        }
        cleaned = cleaned.ifBlank { trimmed }
        val pack = packTitle?.trim().orEmpty()
        return if (pack.isNotEmpty() && (mainPage || cleaned.lowercase() in MAIN_PAGE_NAMES)) pack else cleaned
    }

    private fun strip(title: String, packTitle: String?): String {
        for (suffix in listOf(WIKIPEDIA, STACK_EXCHANGE, SITE)) {
            val stripped = title.replace(suffix, "").trim()
            if (stripped != title && stripped.isNotBlank()) return stripped
        }
        val pack = packTitle?.trim().orEmpty()
        if (pack.isNotEmpty()) {
            val stripped = title.replace(Regex(DASH + Regex.escape(pack) + "$", RegexOption.IGNORE_CASE), "").trim()
            if (stripped.isNotBlank()) return stripped
        }
        return title
    }
}
