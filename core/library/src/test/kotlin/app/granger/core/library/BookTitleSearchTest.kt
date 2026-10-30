package app.granger.core.library

import app.granger.core.library.BookTitleSearch.Rank
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BookTitleSearchTest {
    private val peterPan = book("peter_pan", title = "Peter Pan").copy(author = "J. M. Barrie")
    private val peterPanKensington = book("kensington", title = "Peter Pan in Kensington Gardens").copy(author = "J. M. Barrie")
    private val panic = book("panic", title = "The Panic of 1907").copy(author = "Someone Else")
    private val japan = book("japan", title = "Japan Day by Day").copy(author = "Edward Morse")
    private val shelf = listOf(panic, japan, peterPanKensington, peterPan)

    private fun ranked(query: String, books: List<Book> = shelf) = BookTitleSearch.search(books, query).map { (rank, book) -> rank to book.title }

    @Test fun anExactTitleComesFirstThenOnesStartingWithIt() {
        assertThat(ranked("peter pan")).containsExactly(
            Rank.EXACT to "Peter Pan",
            Rank.STARTS_WITH to "Peter Pan in Kensington Gardens",
        ).inOrder()
    }

    @Test fun aTitleContainingTheQueryFromAWordStartComesNext() {
        // "pan" starts a word in both Peter Pan titles and in Panic, but not inside Japan.
        assertThat(ranked("pan")).containsExactly(
            Rank.TITLE_CONTAINS to "Peter Pan",
            Rank.TITLE_CONTAINS to "Peter Pan in Kensington Gardens",
            Rank.TITLE_CONTAINS to "The Panic of 1907",
        ).inOrder()
    }

    @Test fun anAuthorMatchesAfterTitles() {
        val barrieLife = book("life", title = "Barrie: A Life").copy(author = "Lisa Chaney")
        assertThat(ranked("barrie", shelf + barrieLife)).containsExactly(
            Rank.STARTS_WITH to "Barrie: A Life",
            Rank.AUTHOR to "Peter Pan",
            Rank.AUTHOR to "Peter Pan in Kensington Gardens",
        ).inOrder()
        // Initials and their stops fold away.
        assertThat(ranked("j.m. barrie").map { it.second }).containsExactly("Peter Pan", "Peter Pan in Kensington Gardens")
    }

    @Test fun caseAndAccentsDoNotCount() {
        val miserables = book("mis", title = "Les Misérables").copy(author = "Victor Hugo")
        val eve = book("eve", title = "L'Ève future").copy(author = "Villiers de l'Isle-Adam")
        assertThat(ranked("LES MISERABLES", listOf(miserables))).containsExactly(Rank.EXACT to "Les Misérables")
        assertThat(ranked("eve", listOf(eve))).containsExactly(Rank.TITLE_CONTAINS to "L'Ève future")
        assertThat(ranked("PETER pán")).contains(Rank.EXACT to "Peter Pan")
    }

    @Test fun packDocumentsBrokenBooksAndBlankQueriesFindNothing() {
        val doc = book("doc", origin = BookOrigin.PACK, title = "Peter Pan")
        val missing = book("gone", title = "Peter Pan", status = PackStatus.MISSING)
        assertThat(ranked("peter pan", listOf(doc, missing))).isEmpty()
        assertThat(ranked("  ")).isEmpty()
        assertThat(ranked("-")).isEmpty()
    }

    @Test fun aGutenbergBookIsFoundByTitleBeforeItsFirstOpen() {
        // Listed from its pack, its EPUB not yet copied out (Plan GB Decision 6).
        val beethoven = book("1000", BookOrigin.GUTENBERG, "Beethoven").copy(author = "Ludwig Nohl", filePath = "/nowhere/1000.epub")
        assertThat(ranked("beethoven", shelf + beethoven)).containsExactly(Rank.EXACT to "Beethoven")
        assertThat(ranked("nohl", shelf + beethoven)).containsExactly(Rank.AUTHOR to "Beethoven")
    }
}
