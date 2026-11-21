package app.granger.readaloud

import app.granger.core.books.TestEpubs
import app.granger.core.speech.ReadAddress
import app.granger.core.speech.UtteranceKind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The iPhone's book source for the voice (Plan I5 Task 8): an EPUB's XHTML walked with Android's rules. */
class EpubReadSourceTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun source(): EpubReadSource {
        val file = TestEpubs.build(
            tmp.root,
            chapters = listOf(
                "<h1>Contents</h1><p>Chapter One</p><p>Chapter Two</p>",
                "<h1 id=\"one\">Chapter One</h1><p>It was a dark night. The owl flew.</p><aside epub:type=\"footnote\"><p>A note.</p></aside><div><p id=\"later\">Nobody saw it.</p></div>",
                "<h1>Chapter Two</h1><ul><li>First thing.</li><li>Second thing.</li></ul><p>The end.</p>",
            ),
        )
        val toc = listOf(
            EpubTocEntry("Contents", "OEBPS/c001.xhtml", 0),
            EpubTocEntry("Chapter One", "OEBPS/c002.xhtml#one", 0),
            EpubTocEntry("Later", "OEBPS/c002.xhtml#later", 1),
            EpubTocEntry("Chapter Two", "OEBPS/c003.xhtml", 0),
        )
        return EpubReadSource("b1", "The Book", file.path, toc, io = Dispatchers.Unconfined)
    }

    @Test fun readsBlocksInOrderSkippingFootnotesWithHeadingsWhole() = runBlocking {
        val all = source().utterances(null, 100)
        assertThat(all.map { it.text }).containsExactly(
            "Contents", "Chapter One", "Chapter Two",
            "Chapter One", "It was a dark night.", "The owl flew.", "Nobody saw it.",
            "Chapter Two", "First thing.", "Second thing.", "The end.",
        ).inOrder()
        assertThat(all.filter { it.kind == UtteranceKind.HEADING }.map { it.text }).containsExactly("Contents", "Chapter One", "Chapter Two")
        // Paragraphs only go forward, across files.
        assertThat(all.map { it.paragraph }).isInOrder()
        assertThat(all.map { it.text }).doesNotContain("A note.")
    }

    @Test fun continuesFromAnAddressAndFindsASavedOneAgain() = runBlocking {
        val first = source()
        val all = first.utterances(null, 100)
        val owl = all.first { it.text == "The owl flew." }
        // A new source (the app restarted) makes the same addresses.
        val again = source()
        assertThat(again.locate(owl.address)).isEqualTo(owl.address)
        assertThat(again.utterances(owl.address, 3).map { it.text }).containsExactly("The owl flew.", "Nobody saw it.", "Chapter Two").inOrder()
        assertThat(again.compare(all[1].address, owl.address)).isLessThan(0)
    }

    @Test fun chaptersFollowTheContentsAndAnchors() = runBlocking {
        val s = source()
        assertThat(s.chapters().map { it.title }).containsExactly("Contents", "Chapter One", "Later", "Chapter Two").inOrder()
        val later = s.chapterStart(2)!!
        assertThat(s.utterances(later, 1).single().text).isEqualTo("Nobody saw it.")
        val dark = s.utterances(null, 100).first { it.text == "It was a dark night." }
        assertThat(s.chapterAt(dark.address)).isEqualTo(1)
        assertThat(s.chapterAt(later)).isEqualTo(2)
        assertThat(s.sectionTitle(dark.address)).isEqualTo("Chapter One")
    }

    @Test fun startsPastTheFrontMatterAndAtThePagesFirstWords() = runBlocking {
        val s = source()
        assertThat(s.utterances(s.firstAfterFrontMatter(), 1).single().text).isEqualTo("Chapter One")
        val at = s.addressAtText("OEBPS/c002.xhtml", "The owl flew. Nobody")!!
        assertThat(s.utterances(at, 1).single().text).isEqualTo("The owl flew.")
        val top = s.addressAtText("OEBPS/c003.xhtml", null)!!
        assertThat(s.utterances(top, 1).single().text).isEqualTo("Chapter Two")
    }

    @Test fun aSentenceLocatorCarriesItsTextForReadium() = runBlocking {
        val s = source()
        val owl = s.utterances(null, 100).first { it.text == "The owl flew." }
        val json = Json.parseToJsonElement(s.locatorJson(owl)!!).jsonObject
        assertThat(json["href"]!!.jsonPrimitive.content).isEqualTo("OEBPS/c002.xhtml")
        assertThat(json["title"]!!.jsonPrimitive.content).isEqualTo("Chapter One")
        val text = json["text"]!!.jsonObject
        assertThat(text["highlight"]!!.jsonPrimitive.content).isEqualTo("The owl flew.")
        assertThat(text["before"]!!.jsonPrimitive.content).isEqualTo("It was a dark night. ")
        assertThat(json["locations"]!!.jsonObject["cssSelector"]!!.jsonPrimitive.content).isNotEmpty()
        assertThat((owl.address as ReadAddress.Book).locatorJson).doesNotContain("highlight")
    }
}
