package app.granger.ebook

import app.granger.core.speech.ReadAddress
import app.granger.core.speech.UtteranceKind
import app.granger.testPublicationWithContent
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BookReadSourceTest {
    private val publication = testPublicationWithContent(
        title = "Hamlet",
        chapters = linkedMapOf(
            "c001.xhtml" to "<h1>Act I</h1><p>Who's there? Nay, answer me. Stand and unfold yourself.</p><p>Long live the king!</p><aside epub:type=\"footnote\" xmlns:epub=\"http://www.idpf.org/2007/ops\"><p>A footnote.</p></aside>",
            "c002.xhtml" to "<h2>Act II</h2><p>Second act begins.</p>",
        ),
    )
    private val source = BookReadSource(publication, "book:granger:hamlet", "Hamlet", Dispatchers.Unconfined)

    @Test fun utterancesWalkTheBookInOrderWithHeadingsAndNoFootnotes() = runTest {
        val all = source.utterances(null, 50)
        assertThat(all.map { it.text }).containsExactly(
            "Act I", "Who's there?", "Nay, answer me.", "Stand and unfold yourself.", "Long live the king!", "Act II", "Second act begins.",
        ).inOrder()
        assertThat(all[0].kind).isEqualTo(UtteranceKind.HEADING)
        assertThat(all.map { it.paragraph }).containsExactly(0, 1, 1, 1, 2, 3, 4).inOrder()
        assertThat(all[1].pauseAfterMs).isEqualTo(350L)
        assertThat(all[3].pauseAfterMs).isEqualTo(700L)
        val a = all[2].address as ReadAddress.Book
        assertThat(a.bookId).isEqualTo("book:granger:hamlet")
        assertThat(a.sentence).isEqualTo(1)
        assertThat(source.key).isEqualTo("book:book:granger:hamlet")
        // Every sentence has a locator the navigator can decorate: the element's, with the sentence as its text.
        val locator = source.locatorOf(all[2])!!
        assertThat(locator.text.highlight).isEqualTo("Nay, answer me.")
        assertThat(locator.text.before).isEqualTo("Who's there? ")
        assertThat(locator.href.toString()).isEqualTo("c001.xhtml")
    }

    @Test fun utterancesFromAnAddressReopenTheIteratorThere() = runTest {
        val all = source.utterances(null, 50)
        val from = all[4].address                       // "Long live the king!"
        val fresh = BookReadSource(publication, "book:granger:hamlet", "Hamlet", Dispatchers.Unconfined)
        assertThat(fresh.utterances(from, 10).map { it.text }).containsExactly("Long live the king!", "Act II", "Second act begins.").inOrder()
        assertThat(fresh.locate(from)).isEqualTo(from)
        assertThat(fresh.locate(ReadAddress.Book("book:granger:hamlet", "not json", 0))).isNull()
    }

    @Test fun addressAtAVisibleElementOrASelectionPicksItsSentence() = runTest {
        val chapterTwo = Locator(href = Url("c002.xhtml")!!, mediaType = MediaType.XHTML, locations = Locator.Locations(progression = 0.0))
        val at = source.addressAt(chapterTwo)!!
        assertThat(source.utterances(at, 1).single().text).isEqualTo("Act II")

        val fresh = BookReadSource(publication, "book:granger:hamlet", "Hamlet", Dispatchers.Unconfined)
        val all = fresh.utterances(null, 50)
        val paragraph = fresh.locatorOf(all[1])!!      // the element of "Who's there? …"
        val selection = paragraph.copy(text = Locator.Text(before = "Nay, answer me. ", highlight = "Stand and unfold", after = " yourself."))
        val picked = source.addressAt(selection)!!
        assertThat(picked.sentence).isEqualTo(2)
        assertThat(source.utterances(picked, 1).single().text).isEqualTo("Stand and unfold yourself.")
        assertThat(source.sectionTitle(picked)).isNull()   // the test publication's locators carry no chapter title
    }

    @Test fun addressAtTextFindsTheSentenceHoldingThePagesFirstWords() = runTest {
        val page = Locator(href = Url("c001.xhtml")!!, mediaType = MediaType.XHTML, locations = Locator.Locations(progression = 0.6))
        val at = source.addressAtText(page, "Stand and   unfold yourself. Long live")!!   // the page's words, whitespace as the DOM has it
        assertThat(source.utterances(at, 1).single().text).isEqualTo("Stand and unfold yourself.")
        val king = source.addressAtText(page, "Long live the king!")!!
        assertThat(source.utterances(king, 1).single().text).isEqualTo("Long live the king!")
        assertThat(source.addressAtText(page, "Second act begins.")).isNull()           // another chapter's words
        assertThat(source.addressAtText(page, "   ")).isNull()
    }
}
