package app.granger.core.library

/**
 * Books on the shelf whose title or author matches a query (Plan U2 Decision 1), case and accents folded with
 * [SearchText]. Pure: Titles runs it over the shelf on each query, with no index to wait for.
 */
object BookTitleSearch {
    /** How well a book matches; lower is better. */
    enum class Rank { EXACT, STARTS_WITH, TITLE_CONTAINS, AUTHOR }

    /** [books] matching [query], best first; ties keep the title order. Pack documents and broken books are left out. */
    fun search(books: List<Book>, query: String): List<Pair<Rank, Book>> {
        val q = key(query)
        if (q.isEmpty()) return emptyList()
        return books.filter { it.onShelf && it.status == PackStatus.OK }
            .mapNotNull { book -> rank(book, q)?.let { it to book } }
            .sortedWith(compareBy<Pair<Rank, Book>> { it.first }.thenBy { SearchText.fold(it.second.title) })
    }

    private fun rank(book: Book, q: String): Rank? {
        val title = key(book.title)
        return when {
            title == q -> Rank.EXACT
            title.startsWith(q) -> Rank.STARTS_WITH
            // From the start of a word, so "pan" finds Peter Pan but not Japan.
            " $title".contains(" $q") -> Rank.TITLE_CONTAINS
            " ${key(book.author)}".contains(" $q") -> Rank.AUTHOR
            else -> null
        }
    }

    /** Folded words joined by single spaces, so "J. M. Barrie" and "j m barrie" compare alike. */
    private fun key(text: String): String = SearchText.words(text).joinToString(" ")
}
