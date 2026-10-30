package app.granger.library

import app.granger.core.library.Book
import app.granger.core.library.BookOrigin
import app.granger.core.library.BookPosition
import app.granger.core.library.SearchText
import app.granger.core.library.ShelfKey
import app.granger.library.shelf.ShelfSections
import app.granger.library.shelf.sortedByShelfKey

/** How a collection's books are ordered (Plan U2 Decision 3). */
enum class BookSort(val label: String) { TITLE("Title"), AUTHOR("Author"), RECENT("Recently read") }

/**
 * The books a collection shows: [books] after the Find text, the category and the sort, and as [sections] under their
 * letters (Plan LV Decision 6); [categories] are the
 * catalog's kinds among the collection's books, in catalog order (empty when there are fewer than two to choose
 * between); [category] is the chosen one, null for All; [total] counts every book before filtering.
 */
data class CollectionBooks(
    val sections: ShelfSections<Book> = ShelfSections.empty(),
    val categories: List<String> = emptyList(),
    val category: String? = null,
    val total: Int = 0,
) {
    /** The books in the order shown. */
    val books: List<Book> get() = sections.items.map { it.value }
}

/**
 * [books] filtered and sorted. [categoryOf] maps a Granger book's catalog id to its category, in catalog order; a
 * chosen category no longer among the books falls back to All. Pure, so the view model runs it off the main thread.
 */
fun collectionBooks(
    books: List<Book>,
    query: String,
    sort: BookSort,
    category: String?,
    categoryOf: Map<String, String>,
    positions: Map<String, BookPosition>,
): CollectionBooks {
    fun kind(book: Book) = if (book.origin == BookOrigin.GRANGER) categoryOf[book.sourceRef] else null
    val present = books.mapNotNullTo(HashSet(), ::kind)
    val categories = categoryOf.values.distinct().filter { it in present }.takeIf { it.size > 1 }.orEmpty()
    val chosen = category?.takeIf { it in categories }
    val q = key(query)
    val shown = books.filter { book ->
        (chosen == null || kind(book) == chosen) &&
            (q.isEmpty() || " ${key(book.title)}".contains(" $q") || " ${key(book.author)}".contains(" $q"))
    }
    // Titles A–Z skipping The, A and An (Plan LV Decision 3); one title's copies by their full title, then id.
    val byTitle = compareBy<Book>({ SearchText.fold(it.title) }, { it.id })
    fun titleKey(book: Book) = ShelfKey.of(book.title)
    val sections = when (sort) {
        BookSort.TITLE -> ShelfSections.of(shown.sortedByShelfKey(::titleKey, byTitle), byName = true, key = ::bookKey) { ShelfKey.letter(titleKey(it)) }
        // By surname, filed under its letter; unknown authors last (under #), one author's books by title.
        BookSort.AUTHOR -> ShelfSections.of(
            shown.sortedWith(
                compareBy<Book> { it.author.isBlank() }.thenBy { authorKey(it.author) }.thenBy { SearchText.fold(it.author) }
                    .thenBy(::titleKey).then(byTitle),
            ),
            byName = true, key = ::bookKey,
        ) { ShelfKey.letter(authorKey(it.author)) }
        // Books never opened follow, by title.
        BookSort.RECENT -> ShelfSections.of(
            shown.sortedWith(compareByDescending<Book> { positions[it.id]?.updatedAt ?: Long.MIN_VALUE }.thenBy(::titleKey).then(byTitle)),
            byName = false, key = ::bookKey,
        ) { "" }
    }
    return CollectionBooks(sections, categories, chosen, books.size)
}

/** A book's lazy-list key, the same in every sort and view. */
internal fun bookKey(book: Book) = "book:${book.id}"

/**
 * The surname an author is filed under: the last word of the first full name given, so "J. M. Barrie" files under
 * B, "Jacob and Wilhelm Grimm" under G and "Alfred, Lord Tennyson" under T; a single name ("Homer") is its own.
 */
internal fun authorKey(author: String): String {
    val names = author.split(" and ", ",").map(SearchText::words).filter { it.isNotEmpty() }
    return (names.firstOrNull { it.size > 1 } ?: names.firstOrNull())?.last().orEmpty()
}

/** Folded words joined by single spaces, matched from the start of a word as Titles does. */
private fun key(text: String): String = SearchText.words(text).joinToString(" ")
