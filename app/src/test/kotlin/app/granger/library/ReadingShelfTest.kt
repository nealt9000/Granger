package app.granger.library

import app.granger.book
import app.granger.core.library.BookOrigin
import app.granger.core.library.BookPosition
import app.granger.core.library.PackStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Plan O4 Task 5: the reading shelf from the books and their positions. */
class ReadingShelfTest {
    private fun at(id: String, progress: Double, when_: Long) = id to BookPosition(id, "{}", progress, "", when_)

    @Test fun readingNowMostRecentFirstThenFinished() {
        val genji = book("genji", "The Tale of Genji")
        val steps = book("steps", "The Thirty-Nine Steps")
        val pride = book("pride", "Pride and Prejudice")
        val unread = book("unread", "Not opened")
        val shelf = ReadingShelf.of(
            listOf(genji, steps, pride, unread),
            mapOf(at("genji", 0.4, 30), at("steps", 0.1, 50), at("pride", 1.0, 40)),
        )
        assertThat(shelf.now.map { it.book.id }).containsExactly("steps", "genji").inOrder()
        assertThat(shelf.now.first().progress).isWithin(0.001f).of(0.1f)
        assertThat(shelf.finished.map { it.id }).containsExactly("pride")
    }

    @Test fun offTheShelfAndBrokenBooksAreLeftOut() {
        val saved = book("saved", "Saved for now").copy(hidden = true)
        val pack = book("p", "Pack document").copy(origin = BookOrigin.PACK)
        val gone = book("gone", "Missing").copy(status = PackStatus.MISSING)
        val shelf = ReadingShelf.of(listOf(saved, pack, gone), mapOf(at("saved", 0.2, 1), at("p", 0.2, 2), at("gone", 0.2, 3)))
        assertThat(shelf.now).isEmpty()
    }
}
