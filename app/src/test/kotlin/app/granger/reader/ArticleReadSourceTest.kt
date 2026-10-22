package app.granger.reader

import app.granger.FakeZimArchive
import app.granger.core.speech.ReadAddress
import app.granger.core.speech.UtteranceKind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** ICU's sentence breaks come from the framework (Robolectric); the rest is Jsoup. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ArticleReadSourceTest {
    private val html = """
        <html><body>
        <h1 id="firstHeading">Rosetta Stone</h1>
        <p>The Rosetta Stone is a stele.<sup class="reference">[1]</sup> It was found in 1799.</p>
        <div class="navbox"><p>Navigation junk.</p></div>
        <h2 id="History">History</h2>
        <p>Ptolemaic era. Carved in 196 BC.</p>
        <pre>code here</pre>
        <h2>References</h2>
        <ol><li>A reference.</li></ol>
        </body></html>
    """.trimIndent()
    private val archive = FakeZimArchive("p", contents = mapOf("Rosetta_Stone" to html))
    private val source = ArticleReadSource(archive, "p", "Rosetta_Stone", "Rosetta Stone", Dispatchers.Unconfined)

    @Test fun utterancesSkipNoiseAndReferencesAndCarryAddresses() = runTest {
        val all = source.utterances(null, 100)
        assertThat(all.map { it.text }).containsExactly(
            "Rosetta Stone", "The Rosetta Stone is a stele.", "It was found in 1799.", "History", "Ptolemaic era.", "Carved in 196 BC.", "Code omitted.",
        ).inOrder()
        assertThat(all[0].kind).isEqualTo(UtteranceKind.HEADING)
        assertThat(all[6].kind).isEqualTo(UtteranceKind.CODE_OMITTED)
        assertThat(all[2].address).isEqualTo(ReadAddress.Article("p", "Rosetta_Stone", "g1", 1))
        assertThat(all[2].start).isEqualTo(30)
        assertThat(source.key).isEqualTo("pack:p/Rosetta_Stone")
    }

    @Test fun utterancesFromAnAddressAndSectionTitles() = runTest {
        val from = ReadAddress.Article("p", "Rosetta_Stone", "History", 0)
        assertThat(source.utterances(from, 2).map { it.text }).containsExactly("History", "Ptolemaic era.").inOrder()
        assertThat(source.sectionTitle(ReadAddress.Article("p", "Rosetta_Stone", "g4", 1))).isEqualTo("History")
        assertThat(source.sectionTitle(ReadAddress.Article("p", "Rosetta_Stone", "g1", 0))).isNull()   // under the title itself
        assertThat(source.utterances(ReadAddress.Article("p", "Rosetta_Stone", "gone", 0), 5)).isEmpty()
    }

    @Test fun addressAtPicksTheSentenceAtAnOffsetAndLocateRepairsTheSentenceIndex() = runTest {
        // g4 is the "Ptolemaic era. Carved in 196 BC." paragraph (the navbox's <p> is g2 and the History heading g3).
        assertThat(source.addressAt("g4")).isEqualTo(ReadAddress.Article("p", "Rosetta_Stone", "g4", 0))
        assertThat(source.addressAt("g4", offset = 20)).isEqualTo(ReadAddress.Article("p", "Rosetta_Stone", "g4", 1))
        assertThat(source.addressAt("nope")).isNull()
        assertThat(source.firstAddress()).isEqualTo(ReadAddress.Article("p", "Rosetta_Stone", "firstHeading", 0))
        assertThat(source.locate(ReadAddress.Article("p", "Rosetta_Stone", "g4", 9))).isEqualTo(ReadAddress.Article("p", "Rosetta_Stone", "g4", 0))
        assertThat(source.locate(ReadAddress.Article("p", "Rosetta_Stone", "nope", 0))).isNull()
    }

    @Test fun aMissingPageReadsAsNothing() = runTest {
        val empty = ArticleReadSource(FakeZimArchive("p"), "p", "Nothing", "Nothing", Dispatchers.Unconfined)
        assertThat(empty.utterances(null, 10)).isEmpty()
        assertThat(empty.firstAddress()).isNull()
    }
}
