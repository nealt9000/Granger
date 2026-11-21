package app.granger.search

import app.granger.core.library.BookIds
import app.granger.core.library.SearchHit

/** What the results are narrowed to (Plan SQ Task 5.3): everything, or one kind. */
enum class SearchFilter { ALL, ARTICLES, BOOKS, MEDIA }

/**
 * Search's By kind (Plan SQ Task 5.3), shared since Plan I5 Task 2: which kinds a query found, for the filter chips, and
 * the titles and best matches under a filter. A title row is a pack's article or a book on the shelf
 * ([BookIds.isBook]); one row can hold both (Pride and Prejudice the article, with the book under it).
 */
object SearchKinds {
    /**
     * The kinds of result found: Articles when a title row holds an article or there is article text ([articleText]:
     * the phone's full text or Wikipedia online's) or a best match from an article ([bestArticles]); Books when a title
     * row holds a book, or there are hits inside books ([bookText]) or a best match from a book ([bestBooks]); Media
     * when music or audiobooks matched.
     */
    fun found(
        titles: List<SearchHit>, articleText: Boolean, bookText: Boolean, media: Boolean,
        bestArticles: Boolean = false, bestBooks: Boolean = false,
    ): Set<SearchFilter> = buildSet {
        if (titles.any { row -> rowPart(row, book = false) != null } || articleText || bestArticles) add(SearchFilter.ARTICLES)
        if (titles.any { row -> rowPart(row, book = true) != null } || bookText || bestBooks) add(SearchFilter.BOOKS)
        if (media) add(SearchFilter.MEDIA)
    }

    /** Whether [filter] shows results of [kind]. */
    fun showing(filter: SearchFilter, kind: SearchFilter): Boolean = filter == SearchFilter.ALL || filter == kind

    /**
     * Titles under [filter]: articles are pack titles (with Wikipedia online's not already listed, [online]), books the
     * shelf's. A row holding both keeps the part the filter wants, led by it.
     */
    fun titles(filter: SearchFilter, titles: List<SearchHit>, online: List<SearchHit> = emptyList()): List<SearchHit> = when (filter) {
        SearchFilter.ALL -> titles + online
        SearchFilter.BOOKS -> titles.mapNotNull { rowPart(it, book = true) }
        SearchFilter.ARTICLES -> titles.mapNotNull { rowPart(it, book = false) } + online
        SearchFilter.MEDIA -> emptyList()
    }

    /** Best matches under [filter], [isBook] telling a book's passage from an article's. */
    fun <T> best(filter: SearchFilter, best: List<T>, isBook: (T) -> Boolean): List<T> = when (filter) {
        SearchFilter.ALL -> best
        SearchFilter.BOOKS -> best.filter(isBook)
        SearchFilter.ARTICLES -> best.filterNot(isBook)
        SearchFilter.MEDIA -> emptyList()
    }

    /** [row]'s books (or its articles), the first leading the rest; null when it has none. */
    fun rowPart(row: SearchHit, book: Boolean): SearchHit? {
        val keep = (listOf(row.copy(also = emptyList())) + row.also).filter { BookIds.isBook(it.packId) == book }
        if (keep.size == 1 + row.also.size) return row
        return keep.firstOrNull()?.copy(also = keep.drop(1))
    }
}
