package app.granger.core.speech

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UtterancesTest {
    private fun article(blockId: String, n: Int): ReadAddress = ReadAddress.Article("wikipedia_en_top", "A/Rosetta_Stone", blockId, n)

    @Test
    fun addressesRoundTripThroughEncode() {
        val cases = listOf(
            article("g12", 3),
            ReadAddress.Article("p", "A/Some:path/with#hash", "mw-h2", 0),
            ReadAddress.Book("book:granger:hamlet", """{"href":"/OEBPS/act1.xhtml","locations":{"progression":0.25}}""", 7),
            ReadAddress.Text("answer:41", 2, 1),
        )
        for (a in cases) assertThat(ReadAddress.decode(a.encode())).isEqualTo(a)
        assertThat(ReadAddress.decode("pack:p/path:3")).isNull()      // no block
        assertThat(ReadAddress.decode("book:x:1")).isNull()           // no locator
        assertThat(ReadAddress.decode("nothing")).isNull()
        assertThat(article("g1", 2).withSentence(5).sentence).isEqualTo(5)
    }

    @Test
    fun aSentenceInsideAQuoteTheParagraphOpenedSaysSo() {
        val blocks = listOf(
            Block("p1", BlockKind.PARAGRAPH, "\"I'll go. You stay.\" He left."),
            Block("p2", BlockKind.PARAGRAPH, "Nobody spoke. Then the door shut."),
        )
        val u = Utterances.fromBlocks(blocks, ::article)
        assertThat(u.map { it.text }).containsExactly("\"I'll go.", "You stay.\"", "He left.", "Nobody spoke.", "Then the door shut.").inOrder()
        assertThat(u.map { it.quoteOpen }).containsExactly(false, true, false, false, false).inOrder()
    }

    @Test
    fun blocksBecomeHeadingAndSentenceUtterancesWithPauses() {
        val blocks = listOf(
            Block("h1", BlockKind.HEADING, "Rosetta  Stone"),
            Block("p1", BlockKind.PARAGRAPH, "It was found in 1799. It is in London."),
            Block("li1", BlockKind.LIST_ITEM, "One item."),
        )
        val u = Utterances.fromBlocks(blocks, ::article)
        assertThat(u.map { it.text }).containsExactly("Rosetta Stone", "It was found in 1799.", "It is in London.", "One item.").inOrder()
        assertThat(u.map { it.kind }).containsExactly(UtteranceKind.HEADING, UtteranceKind.SENTENCE, UtteranceKind.SENTENCE, UtteranceKind.SENTENCE).inOrder()
        assertThat(u.map { it.pauseAfterMs }).containsExactly(900L, 350L, 700L, 700L).inOrder()
        assertThat(u.map { it.address }).containsExactly(article("h1", 0), article("p1", 0), article("p1", 1), article("li1", 0)).inOrder()
        assertThat(u[2].start).isEqualTo(22)
        assertThat(u[2].end).isEqualTo(38)
    }

    @Test
    fun preBlocksBecomeOneCodeOmittedUtterance() {
        val u = Utterances.fromBlocks(listOf(Block("pre1", BlockKind.CODE, "val x = 1\nprintln(x)"), Block("pre2", BlockKind.CODE, "  ")), ::article)
        assertThat(u).hasSize(1)
        assertThat(u[0].kind).isEqualTo(UtteranceKind.CODE_OMITTED)
        assertThat(u[0].text).isEqualTo("Code omitted.")
        assertThat(u[0].end).isEqualTo(0)
    }

    @Test
    fun emptyAndPunctuationOnlyBlocksAreDropped() {
        val u = Utterances.fromBlocks(listOf(Block("a", BlockKind.PARAGRAPH, "…"), Block("b", BlockKind.HEADING, "  "), Block("c", BlockKind.PARAGRAPH, "Real.")), ::article)
        assertThat(u.map { it.text }).containsExactly("Real.")
        assertThat(u[0].paragraph).isEqualTo(0)
    }

    @Test
    fun paragraphIndexAdvancesPerBlock() {
        val u = Utterances.fromBlocks(
            listOf(Block("a", BlockKind.PARAGRAPH, "One. Two."), Block("b", BlockKind.PARAGRAPH, "Three."), Block("c", BlockKind.HEADING, "Four")),
            ::article,
        )
        assertThat(u.map { it.paragraph }).containsExactly(0, 0, 1, 2).inOrder()
    }

    @Test
    fun textSourceSplitsParagraphsAndServesFromAnAddress() = runTest {
        val src = TextReadSource("text:spike:answer", "Answer", "First one. Second one.\n\nThird one.")
        assertThat(src.size).isEqualTo(3)
        val first = src.utterances(null, 2)
        assertThat(first.map { it.text }).containsExactly("First one.", "Second one.").inOrder()
        val rest = src.utterances(first.last().address, 10)
        assertThat(rest.map { it.text }).containsExactly("Second one.", "Third one.").inOrder()
        assertThat(rest.last().address).isEqualTo(ReadAddress.Text("text:spike:answer", 1, 0))
        assertThat(src.utterances(ReadAddress.Text("text:spike:answer", 9, 9), 10)).isEmpty()
    }
}
