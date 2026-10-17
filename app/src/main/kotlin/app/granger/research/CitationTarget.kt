package app.granger.research

import app.granger.BookTarget

/** Where opening a cited passage leads. */
sealed interface CitationTarget {
    /** An article in its rail tab, scrolled to [fragment] when there is one. */
    data class Article(val tabId: Long, val fragment: String?) : CitationTarget

    /** A book in its rail tab, at [at]; null opens the book where it was left. */
    data class Book(val tabId: Long, val at: BookTarget?) : CitationTarget

    /** The pack or book is no longer in the library (removed, missing or damaged). */
    data object Gone : CitationTarget
}
