package app.granger.library

import app.granger.core.library.Book
import app.granger.core.library.BookPosition
import app.granger.core.library.PackStatus
import app.granger.core.library.onShelf

// Shared since Plan I5 Task 2: the reading shelf's state; Android draws it in ReadingShelf.kt.

/**
 * The reading shelf (Plan O4 Task 5; S1 on the O3 canvas, Neal 2026-11-19): what you're reading now, most recent first,
 * and what you've finished, from the books' positions.
 */
data class ShelfReading(val book: Book, val progress: Float)

data class ReadingShelfUi(val now: List<ShelfReading>, val finished: List<Book>)

object ReadingShelf {
    /** A book read past this is finished. */
    const val FINISHED = 0.98

    /** Reading now at most (one large, two small, then a short row), and Finished at most. */
    const val NOW = 6
    const val DONE = 12

    /** From the shelf's books and their positions (pure, for tests): on the shelf, readable, opened at least once. */
    fun of(books: List<Book>, positions: Map<String, BookPosition>): ReadingShelfUi {
        val read = books.filter { it.onShelf && it.status == PackStatus.OK }.mapNotNull { b -> positions[b.id]?.let { b to it } }
            .sortedByDescending { it.second.updatedAt }
        val now = read.filter { it.second.totalProgression < FINISHED }.take(NOW).map { (b, p) -> ShelfReading(b, p.totalProgression.toFloat()) }
        val finished = read.filter { it.second.totalProgression >= FINISHED }.take(DONE).map { it.first }
        return ReadingShelfUi(now, finished)
    }
}

