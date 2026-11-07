package app.granger.home

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LeadLinksTest {
    private fun page(lead: String, rest: String = "") =
        """<html><head><title>Hagia Sophia</title></head><body><h1>Hagia Sophia</h1>
           <table class="infobox"><tr><td><p>Infobox text that is long enough to count as a paragraph if it were read, <a href="./Istanbul_Infobox">x</a>.</p></td></tr></table>
           $lead<h2 id="History">History</h2>$rest</body></html>"""

    private val lead = page(
        """<p>Short.</p>
           <p><b>Hagia Sophia</b> (<span>/ˈhɑːɡiə soʊˈfiːə/</span>; <a href="./File:Hagia.ogg">listen</a>) is a mosque in <a href="./Istanbul">Istanbul</a>,
           built in <a href="./537">537</a> under <a href="./Justinian_I">Justinian I</a> of the <a href="./Byzantine_Empire">Byzantine Empire</a>.<sup>[1]</sup>
           It was the world's largest cathedral for nearly a thousand years. It is in <a href="./Turkey">Turkey</a>.</p>
           <p>Later the <a href="./Ottoman_Empire">Ottoman Empire</a> took <a href="./Istanbul">Istanbul</a> in the <a href="./15th_century">15th century</a>;
           see <a href="#History">below</a>, <a href="https://example.org/x">a site</a> and <a href="./Help:IPA">help</a>.</p>""",
        rest = """<p>The history section has a link to <a href="./Not_In_The_Lead">something</a> that must not orbit at all, ever.</p>""",
    )

    @Test fun linksComeFromTheLeadOnlyRankedByPlaceAndRepeats() {
        val links = LeadLinks.parse(lead, "Hagia_Sophia").links
        // Istanbul: 3 in the first paragraph, +1 for its repeat. Then the rest of the first paragraph in order, then the second's.
        assertThat(links).containsExactly("Istanbul", "Justinian_I", "Byzantine_Empire", "Turkey", "Ottoman_Empire").inOrder()
    }

    @Test fun timeFileNamespaceFragmentAndExternalLinksAreDropped() {
        val p = "A/Cat"
        assertThat(LeadLinks.linkPath("../537", p)).isNull()
        assertThat(LeadLinks.linkPath("../1990s", p)).isNull()
        assertThat(LeadLinks.linkPath("../44_BC", p)).isNull()
        assertThat(LeadLinks.linkPath("../15th_century", p)).isNull()
        assertThat(LeadLinks.linkPath("../July_4", p)).isNull()
        assertThat(LeadLinks.linkPath("../4_de_julio", p)).isNull()
        assertThat(LeadLinks.linkPath("../Siglo_XV", p)).isNull()
        assertThat(LeadLinks.linkPath("../File:Cat.jpg", p)).isNull()
        assertThat(LeadLinks.linkPath("../Archivo:Gato.jpg", p)).isNull()
        assertThat(LeadLinks.linkPath("../I/m/Cat.jpg", p)).isNull()
        assertThat(LeadLinks.linkPath("#History", p)).isNull()
        assertThat(LeadLinks.linkPath("https://en.wikipedia.org/wiki/Cat", p)).isNull()
        // Kept: an ordinary article in either layout, and titles that hold a colon or a year inside a name.
        assertThat(LeadLinks.linkPath("Felidae", p)).isEqualTo("A/Felidae")
        assertThat(LeadLinks.linkPath("./Felidae", "Cat")).isEqualTo("Felidae")
        assertThat(LeadLinks.linkPath("./Star_Trek:_Voyager", "Cat")).isEqualTo("Star_Trek:_Voyager")
        assertThat(LeadLinks.linkPath("Halo:_Reach", "Cat")).isEqualTo("Halo:_Reach")
        assertThat(LeadLinks.linkPath("./Apollo_11", "Cat")).isEqualTo("Apollo_11")
        assertThat(LeadLinks.linkPath("./Caf%C3%A9_society#Origins", "Cat")).isEqualTo("Café_society")
    }

    @Test fun theSummaryIsTwoPlainSentencesWithoutThePronunciation() {
        val summary = LeadLinks.parse(lead, "Hagia_Sophia").summary
        assertThat(summary).isEqualTo(
            "Hagia Sophia is a mosque in Istanbul, built in 537 under Justinian I of the Byzantine Empire. " +
                "It was the world's largest cathedral for nearly a thousand years.",
        )
        assertThat(LeadLinks.parse(lead, "Hagia_Sophia").title).isEqualTo("Hagia Sophia")
    }

    @Test fun aLongSummaryIsCutAtAWord() {
        val long = page("<p>${"Word ".repeat(120)}ends here.</p>")
        val summary = LeadLinks.parse(long, "X").summary
        assertThat(summary.length).isAtMost(LeadLinks.MAX_SUMMARY)
        assertThat(summary).endsWith("Word…")
    }

    @Test fun abbreviationsAndDatesDoNotEndASentence() {
        val p = page("<p>Athanasius (c. 296 – 373) was a bishop. He was born in Alexandria in Egypt, a city of the Roman world. He died old.</p>")
        assertThat(LeadLinks.parse(p, "Athanasius").summary)
            .isEqualTo("Athanasius (c. 296 – 373) was a bishop. He was born in Alexandria in Egypt, a city of the Roman world.")
    }

    @Test fun aPageWithoutALeadGivesNothing() {
        val none = LeadLinks.parse("<html><body><h2>Only</h2><p>${"text ".repeat(20)} <a href=\"./X\">x</a></p></body></html>", "Y")
        assertThat(none.links).isEmpty()
        assertThat(none.summary).isEmpty()
    }

    @Test fun aSpanishPageFollowsTheSameRules() {
        val es = """<html><head><title>Santa Sofía</title></head><body>
            <p><b>Santa Sofía</b> (en griego, <a href="./Idioma_griego">Ἁγία Σοφία</a>) es un antiguo templo de <a href="./Estambul">Estambul</a>, construido en el <a href="./Siglo_VI">siglo VI</a> el <a href="./27_de_diciembre">27 de diciembre</a> de <a href="./537">537</a>. ¿Es la mayor? Lo fue durante mil años.</p>
            <h2>Historia</h2></body></html>"""
        val lead = LeadLinks.parse(es, "Santa_Sofía")
        assertThat(lead.links).containsExactly("Idioma_griego", "Estambul").inOrder()
        assertThat(lead.summary).isEqualTo("Santa Sofía (en griego, Ἁγία Σοφία) es un antiguo templo de Estambul, construido en el siglo VI el 27 de diciembre de 537. ¿Es la mayor?")
    }
}
