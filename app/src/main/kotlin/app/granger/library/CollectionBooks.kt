package app.granger.library

import app.granger.core.library.Book
import app.granger.core.library.BookOrigin
import app.granger.core.library.BookPosition
import app.granger.core.library.SearchText
import app.granger.core.library.ShelfKey
import app.granger.library.shelf.ShelfSections
import app.granger.library.shelf.sortedByShelfKey

/** How a collection's books are ordered (Plan U2 Decision 3). */
enum class BookSort(@androidx.annotation.StringRes val label: Int) {
    TITLE(app.granger.R.string.library_sort_title), AUTHOR(app.granger.R.string.library_sort_author), RECENT(app.granger.R.string.library_sort_recent),
}

/**
 * The books a collection shows: [books] after the Find text, the category and the sort, and as [sections] under their
 * letters (Plan LV Decision 6); [categories] are the
 * catalog's kinds among the collection's books, in catalog order (empty when there are fewer than two to choose
 * between); [category] is the chosen one, null for All; [total] counts every book before filtering.
 *
 * A manifest collection (Plan KB2 Task 2.3) sorted by title with no Find text is shown in its own order instead, as
 * [rows]: sections, folded sections and works in parts ([sectionRows]); null for every other shelf. [languages] are the
 * names of its books' languages when there are more than one to choose between, [language] the chosen one.
 */
data class CollectionBooks(
    val sections: ShelfSections<Book> = ShelfSections.empty(),
    val categories: List<String> = emptyList(),
    val category: String? = null,
    val total: Int = 0,
    val rows: List<SectionRow>? = null,
    val languages: List<String> = emptyList(),
    val language: String? = null,
) {
    /** The books in the order shown. */
    val books: List<Book> get() = sections.items.map { it.value }
}

/**
 * How a manifest collection is laid out (Plan KB2 Task 2.3): its sections' order ([groups]), which folded sections and
 * which works are open, and the chosen language (a name, null for All).
 */
data class CollectionLayout(
    val groups: List<String> = emptyList(),
    val openSections: Set<String> = emptySet(),
    val openWorks: Set<String> = emptySet(),
    val language: String? = null,
)

/**
 * [books] filtered and sorted. [categoryOf] maps a Granger book's catalog id to its category, in catalog order; a
 * chosen category no longer among the books falls back to All, and so does a chosen language. Pure, so the view model
 * runs it off the main thread. [languageName] names a language code ("eng" and "en" are both "English").
 */
fun collectionBooks(
    books: List<Book>,
    query: String,
    sort: BookSort,
    category: String?,
    categoryOf: Map<String, String>,
    positions: Map<String, BookPosition>,
    layout: CollectionLayout = CollectionLayout(),
    languageName: (String) -> String = { it },
): CollectionBooks {
    fun kind(book: Book) = if (book.origin == BookOrigin.GRANGER) categoryOf[book.sourceRef] else null
    val present = books.mapNotNullTo(HashSet(), ::kind)
    val categories = categoryOf.values.distinct().filter { it in present }.takeIf { it.size > 1 }.orEmpty()
    val chosen = category?.takeIf { it in categories }
    // A collection in more than one language gets a chip per language (Plan KB2): only manifest collections, whose
    // books say their own language.
    val ownOrder = hasOwnOrder(books)
    val names = if (ownOrder) books.associate { it.id to languageName(it.language) } else emptyMap()
    val languages = names.values.filter { it.isNotBlank() }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER).takeIf { it.size > 1 }.orEmpty()
    val language = layout.language?.takeIf { it in languages }
    val q = key(query)
    val shown = books.filter { book ->
        (language == null || names[book.id] == language) &&
            (chosen == null || kind(book) == chosen) &&
            (q.isEmpty() || " ${key(book.title)}".contains(" $q") || " ${key(book.author)}".contains(" $q"))
    }
    // Titles A–Z skipping The, A and An (Plan LV Decision 3); one title's copies by their full title, then id.
    val byTitle = compareBy<Book>({ SearchText.fold(it.title) }, { it.id })
    fun titleKey(book: Book) = ShelfKey.of(book.title, language = book.language)
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
    val rows = if (ownOrder && sort == BookSort.TITLE && q.isEmpty()) {
        sectionRows(shown, layout.groups, layout.openSections, layout.openWorks)
    } else null
    return CollectionBooks(sections, categories, chosen, books.size, rows, languages, language)
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
