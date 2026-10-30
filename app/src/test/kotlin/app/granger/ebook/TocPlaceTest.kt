package app.granger.ebook

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TocPlaceTest {
    // The shape of a gutenberg2zim EPUB's nav (The Oera Linda Book, Gutenberg 40986): three anchors in the header
    // file, then 51 in the one big text file.
    private val header = "6397459508036794248_40986-h-0.htm.xhtml"
    private val body = "6397459508036794248_40986-h-1.htm.xhtml"
    private val toc = listOf(
        TocRow("Title", "$header#pgepubid00005", 0),
        TocRow("Preface", "$header#pgepubid00009", 0),
        TocRow("Contents", "$header#pgepubid00030", 0),
        TocRow("Introduction", "$body#entext", 0),
        TocRow("Thet Bok", "$body#pgepubid00034", 1),
        TocRow("Minnos Skrifta", "$body#pgepubid00037", 1),
        TocRow("Notes", "$body#pgepubid00046", 1),
    )

    private fun at(href: String, progression: Double?) =
        Locator(Url(href)!!, MediaType.XHTML, locations = Locator.Locations(progression = progression))

    @Test fun theFirstPageOfABigFileIsItsFirstChapter() {
        val starts = mapOf(body to mapOf("entext" to 0.0, "pgepubid00034" to 0.2, "pgepubid00037" to 0.5, "pgepubid00046" to 0.9))
        assertThat(TocPlace.index(toc, at(body, 0.0), starts)).isEqualTo(3)
        assertThat(TocPlace.index(toc, at(body, 0.55), starts)).isEqualTo(5)
        assertThat(TocPlace.index(toc, at(body, 0.95), starts)).isEqualTo(6)
    }

    @Test fun beforeTheFirstAnchorTheFirstRowStillLights() {
        val starts = mapOf(header to mapOf("pgepubid00005" to 0.1, "pgepubid00009" to 0.3, "pgepubid00030" to 0.6))
        assertThat(TocPlace.index(toc, at(header, 0.0), starts)).isEqualTo(0)
        assertThat(TocPlace.index(toc, at(header, 0.4), starts)).isEqualTo(1)
    }

    @Test fun withoutStartsOrProgressionTheFilesFirstRowLights() {
        assertThat(TocPlace.index(toc, at(body, 0.8), emptyMap())).isEqualTo(3)
        assertThat(TocPlace.index(toc, at(body, null), mapOf(body to mapOf("pgepubid00046" to 0.1)))).isEqualTo(3)
        assertThat(TocPlace.index(toc, at("elsewhere.xhtml", 0.5), emptyMap())).isNull()
    }

    @Test fun anchorsStartWhereTheirTextBegins() {
        val html = "<html><head><style>p { color: red }</style></head><body><h2 id=\"a\">AAAA</h2><p>aaaa</p>" +
            "<a name=\"b\"></a><p>bbbbbbbb</p><div id='c'>cccc</div></body></html>"
        val starts = TocPlace.anchorStarts(html, setOf("a", "b", "c", "missing"))
        assertThat(starts.keys).containsExactly("a", "b", "c")
        assertThat(starts.getValue("a")).isEqualTo(0.0)
        assertThat(starts.getValue("b")).isEqualTo(8.0 / 20)
        assertThat(starts.getValue("c")).isEqualTo(16.0 / 20)
    }

    @Test fun onlyFilesWithSeveralRowsAreRead() {
        val rows = toc + TocRow("Lone", "other.xhtml#x", 0) + TocRow("Whole", "whole.xhtml", 0)
        assertThat(TocPlace.anchorsByFile(rows).keys).containsExactly(header, body)
    }
}
