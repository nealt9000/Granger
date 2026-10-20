package app.granger.research

import app.granger.BookTarget
import app.granger.core.library.ReadingSessions
import app.granger.core.library.ReadingTab
import kotlinx.coroutines.CancellationException

/** Where opening a cited passage leads. */
sealed interface CitationTarget {
    /** An article in its rail tab, scrolled to [fragment] when there is one. */
    data class Article(val tabId: Long, val fragment: String?) : CitationTarget

    /** A book in its rail tab, at [at]; null opens the book where it was left. */
    data class Book(val tabId: Long, val at: BookTarget?) : CitationTarget

    /** A music album's page ([albumKey] without the "album:" prefix of a media card's sourceId). */
    data class Album(val albumKey: String) : CitationTarget

    /** An audiobook's page. */
    data class Audiobook(val audiobookId: String) : CitationTarget

    /** The pack, book, album or audiobook is no longer in the library (removed, missing or damaged). */
    data object Gone : CitationTarget
}

/** The rail tab a citation opened, or null when it is gone or can't be read (a storage error opens nothing rather than crashing the tap). */
internal suspend fun citationTab(sessions: ReadingSessions, tabId: Long): ReadingTab? = try {
    sessions.tab(tabId)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}
