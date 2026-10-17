package app.granger.core.research

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HtmlPassagesTest {
    private fun words(n: Int, prefix: String = "w") = (1..n).joinToString(" ") { "$prefix$it" }
    private fun count(s: String) = s.trim().split(Regex("\\s+")).size

    @Test fun splitsAtHeadingsWithAnchors() {
        val html = """<h2 id="History">History</h2><p>Long ago things happened.</p>
            <h2><span id="Use">Use</span></h2><p>Today it is used.</p>"""
        val out = HtmlPassages.split(html)
        assertThat(out.map { it.heading }).containsExactly("History", "Use").inOrder()
        assertThat(out.map { it.anchor }).containsExactly("History", "Use").inOrder()
    }

    @Test fun anchorFallsBackToEnclosingSection() {
        val out = HtmlPassages.split("""<section id="sec1"><h2>Intro</h2><p>Hello there.</p></section>""")
        assertThat(out.single().anchor).isEqualTo("sec1")
    }

    @Test fun dropsReferencesNavboxesAndTables() {
        val html = """<p>Steam power<sup class="reference">[1]</sup> is old.</p>
            <div class="navbox">NAVBOXTEXT</div><table><tr><td>CELLTEXT</td></tr></table>"""
        val all = HtmlPassages.split(html).joinToString(" ") { it.text }
        assertThat(all).contains("Steam power")
        assertThat(all).doesNotContain("[1]")
        assertThat(all).doesNotContain("NAVBOXTEXT")
        assertThat(all).doesNotContain("CELLTEXT")
    }

    @Test fun longParagraphIsCutUnderMaxWords() {
        val original = words(700)
        val out = HtmlPassages.split("<p>$original</p>")
        assertThat(out.size).isAtLeast(3)
        assertThat(out.all { count(it.text) <= 300 }).isTrue()
        assertThat(out.joinToString(" ") { it.text }).isEqualTo(original)
    }

    @Test fun accumulatesShortParagraphsToTarget() {
        val html = (1..10).joinToString("") { "<p>${words(30, "p$it" + "x")}</p>" }
        assertThat(HtmlPassages.split(html)).hasSize(2)
    }

    @Test fun emptyHeadingsAreDropped() =
        assertThat(HtmlPassages.split("<h2 id=\"a\">Only heading</h2>")).isEmpty()
}
