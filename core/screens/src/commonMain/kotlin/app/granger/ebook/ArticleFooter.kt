package app.granger.ebook

import app.granger.core.library.BookMention

/** An article's opening for a link's card: its title, first lines and cached picture. */
data class ArticleLead(val title: String, val sentence: String, val thumbnail: String?)

/** A book on the phone, as the Linked Library shows it: to open, with its title and cover. */
data class LibraryBook(val bookId: String, val title: String, val cover: String?)

/** One installed Classic that mentions an article's subject: how often, and its first few passages (Task 9). */
data class InYourBook(val book: LibraryBook, val count: Int, val passages: List<BookMention>)

/**
 * What the web reader shows under an article (Plan LL1 Task 9): the article's work when it's on the phone, the
 * Classics that mention its subject, and the installed books by its subject when that's an author.
 */
data class ArticleFooter(
    val thisWork: List<LibraryBook>,
    /** The books mentioning it most, at most `ReferenceLinksSource.BOOKS_SHOWN`. */
    val inBooks: List<InYourBook>,
    val byAuthor: List<LibraryBook>,
    /** How many installed books mention it in all (London is in 290 of the 622 Classics). */
    val inBooksTotal: Int = inBooks.size,
) {
    val isEmpty: Boolean get() = thisWork.isEmpty() && inBooks.isEmpty() && byAuthor.isEmpty()

    companion object {
        val NONE = ArticleFooter(emptyList(), emptyList(), emptyList())
    }
}

/** Where the web reader gets an article's footer; the app's is `ReferenceLinksSource`. */
fun interface ArticleFooterSource {
    suspend fun footer(packId: String, path: String): ArticleFooter

    /**
     * [footer] with *In your books* kept to books in [languages] (a book whose language isn't known stays), those in
     * [first] listed first (Plan LL2: a Spanish page's Spanish books, and only the library's languages; Neal, 2026-11-16).
     */
    suspend fun footer(packId: String, path: String, languages: Set<String>?, first: String?): ArticleFooter = footer(packId, path)
}
