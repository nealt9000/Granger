package app.granger.core.research

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ArticleTextTest {
    private val html = """
        <html><body>
        <h1 id="firstHeading">Rosetta Stone</h1>
        <p>The Rosetta Stone is a stele.<sup class="reference">[1]</sup> It was found in 1799.</p>
        <table class="infobox"><tr><td><p>Infobox text</p></td></tr></table>
        <h2 id="History">History</h2>
        <p>Ptolemaic&nbsp;era.</p>
        <ul><li>First item <p>with a nested paragraph</p></li><li>Second item.</li></ul>
        <pre>val x = 1
        println(x)</pre>
        <p>…</p>
        <div class="navbox"><p>Navigation junk.</p></div>
        <h2>See also</h2>
        <p>Not read.</p>
        <h3>Deeper in see also</h3>
        <p>Still not read.</p>
        <h2 id="Legacy">Legacy</h2>
        <p>Read again.</p>
        <h2>References</h2>
        <ol><li>A reference.</li></ol>
        </body></html>
    """.trimIndent()

    @Test
    fun blocksSkipNoiseNestedBlocksAndBibliographySections() {
        val blocks = ArticleText.blocks(html)
        assertThat(blocks.map { it.text.lines().joinToString("\n") { l -> l.trim() } }).containsExactly(
            "Rosetta Stone",
            "The Rosetta Stone is a stele. It was found in 1799.",
            "History",
            "Ptolemaic era.",
            "First item with a nested paragraph",
            "Second item.",
            "val x = 1\nprintln(x)",
            "Legacy",
            "Read again.",
        ).inOrder()
        assertThat(blocks.map { it.tag }).containsExactly("h1", "p", "h2", "p", "li", "li", "pre", "h2", "p").inOrder()
        assertThat(blocks[6].isCode).isTrue()
        assertThat(blocks[0].isHeading).isTrue()
    }

    @Test
    fun idsAreTheElementsOwnOrItsIndexAmongAllBlockElements() {
        val blocks = ArticleText.blocks(html)
        assertThat(blocks[0].id).isEqualTo("firstHeading")
        assertThat(blocks[2].id).isEqualTo("History")
        // The first <p> is the second block element in document order (index 1), whatever was removed later.
        assertThat(blocks[1].id).isEqualTo("g1")
        // Indices count the infobox's <p> (index 2) and every other block element, removed or not.
        assertThat(blocks[3].id).isEqualTo("g4")
        assertThat(blocks.map { it.id }.distinct()).hasSize(blocks.size)
    }

    @Test
    fun theSelectorsAreTheOnesResearchUses() {
        assertThat(ArticleText.NOISE).contains("sup.reference")
        assertThat(ArticleText.BLOCKS).startsWith("h1, h2, h3, h4, p")
        assertThat(ArticleText.fold("a  b\n c")).isEqualTo("a b c")
    }
}
