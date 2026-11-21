package app.granger.core.library

/**
 * The key a shelf sorts a name by, and the letter it files under (Plan LV Decision 3). "The Odyssey" files under O,
 * "1984" under #, "Émile" under E. One key for every A–Z shelf: books, albums, songs, audiobooks, artists, genres,
 * folders and Android Auto's albums.
 */
object ShelfKey {
    /** Quotes, brackets and other marks before the first letter or digit: "'Salem's Lot" sorts as Salem's Lot. */
    private val LEADING_MARKS = Regex("^[^\\p{L}\\p{N}]+")
    private val ARTICLE = Regex("^(the|an|a)\\s+", RegexOption.IGNORE_CASE)

    /** Spanish's articles (Plan L3), dropped only from a Spanish title: "El Paso" is an English album's name. */
    private val SPANISH_ARTICLE = Regex("^(el|la|los|las|un|una)\\s+", RegexOption.IGNORE_CASE)

    /**
     * [title]'s key: [sortTag] when there is one (a music tag such as "Beatles, The"), else [title] without its
     * leading marks and a leading "The ", "A " or "An "; then [SearchText.fold]ed. A title in Spanish ([language] "es",
     * "spa" or "Spanish") also drops "El ", "La ", "Los ", "Las ", "Un " or "Una " (Plan L3): "El Quijote" files under Q.
     */
    fun of(title: String, sortTag: String? = null, language: String? = null): String {
        val tag = sortTag?.trim().orEmpty()
        val bare = ARTICLE.replace(LEADING_MARKS.replace(title.trim(), ""), "")
        val name = if (tag.isNotEmpty()) tag else if (isSpanish(language)) SPANISH_ARTICLE.replace(bare, "") else bare
        return SearchText.fold(LEADING_MARKS.replace(name, ""))
    }

    /** Whether [language] (a code, a code list's first, or LibriVox's English name) is Spanish. */
    fun isSpanish(language: String?): Boolean {
        val first = language?.split(',')?.firstOrNull()?.trim()?.lowercase()?.substringBefore('-')?.substringBefore('_') ?: return false
        return first == "es" || first == "spa" || first == "spanish" || first == "español"
    }

    /** The letter [key] files under: A–Z for a key starting with one, "#" for digits and everything else. */
    fun letter(key: String): String {
        val first = key.firstOrNull()
        return if (first != null && first in 'a'..'z') first.uppercaseChar().toString() else OTHER
    }

    /** Where digits, other scripts and empty names file: after Z. */
    const val OTHER = "#"
}
