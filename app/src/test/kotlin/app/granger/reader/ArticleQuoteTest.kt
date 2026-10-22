package app.granger.reader

import app.granger.core.research.ArticleText
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ArticleQuoteTest {
    private val html = """
        <html><body>
        <h1 id="firstHeading">Rosetta Stone</h1>
        <p>The stone names <b>Ptolemy V</b> and was found in 1799.<sup class="reference">[1]</sup> Its text is a decree.</p>
        <h2 id="Decipherment">Decipherment</h2>
        <p>Champollion  announced, in 1822, the <a href="x">décipherment</a> of the hieroglyphs.</p>
        <h3 id="Later">Later work</h3>
        <p>Its text is a decree.</p>
        <h2 id="Display">Display</h2>
        <p>Its text is a decree.</p>
        <pre>Its text is a decree.</pre>
        </body></html>
    """.trimIndent()
    private val blocks = ArticleText.blocks(html)

    private fun textOf(range: CiteRange) = blocks.single { it.id == range.blockId }.text.substring(range.start, range.end)

    @Test fun findsAQuoteAcrossInlineTags() {
        val r = ArticleQuote.locate(blocks, null, "The stone names Ptolemy V and was found in 1799.")!!
        assertThat(r.blockId).isEqualTo("g1")
        assertThat(textOf(r)).isEqualTo("The stone names Ptolemy V and was found in 1799.")
        assertThat(r.prefix).isEqualTo(blocks.single { it.id == "g1" }.text.take(40))
    }

    @Test fun theAnchorsSectionWinsOverAnEarlierRepeat() {
        // The Decipherment section runs on through its h3 to the next h2; Display's copy comes later still.
        assertThat(ArticleQuote.locate(blocks, "Decipherment", "Its text is a decree.")!!.blockId).isEqualTo(blocks[5].id)
        assertThat(ArticleQuote.locate(blocks, "Display", "Its text is a decree.")!!.blockId).isEqualTo(blocks[7].id)
        assertThat(ArticleQuote.locate(blocks, null, "Its text is a decree.")!!.blockId).isEqualTo("g1")
        assertThat(ArticleQuote.locate(blocks, "nowhere", "Its text is a decree.")!!.blockId).isEqualTo("g1")
    }

    @Test fun accentsAndWhitespaceFoldWithOffsetsIntact() {
        val r = ArticleQuote.locate(blocks, "Decipherment", "champollion announced, in 1822, the DECIPHERMENT of the hieroglyphs.")!!
        assertThat(textOf(r)).isEqualTo("Champollion announced, in 1822, the décipherment of the hieroglyphs.")
    }

    @Test fun aQuoteNotFoundWholeIsTriedByItsFirstHalf() {
        val r = ArticleQuote.locate(blocks, null, "Champollion announced, in 1822, the decipherment of the demotic script as well.")!!
        assertThat(textOf(r)).startsWith("Champollion announced, in 1822")
    }

    @Test fun notFoundIsNull() {
        assertThat(ArticleQuote.locate(blocks, null, "Nothing like this is on the page at all.")).isNull()
        assertThat(ArticleQuote.locate(blocks, null, "  ")).isNull()
    }
}
