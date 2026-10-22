package app.granger.home

import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import org.junit.Test

class DayPageTest {
    /** The shape mwoffliner gives a Wikipedia day article: sections, headings with ids, references, nested lists. */
    private val html = """
        <html><head><title>October 22</title></head><body>
        <h1>October 22</h1>
        <p>October 22 is the 295th day of the year.</p>
        <section><h2 id="Events">Events</h2>
          <section><h3 id="Pre-1600">Pre-1600</h3>
            <ul><li>794 – Emperor Kanmu relocates the Japanese capital to Heian-kyō (now Kyoto).<sup class="reference">[1]</sup></li>
                <li>1383 – The male line of the Portuguese House of Burgundy becomes extinct.</li></ul>
          </section>
          <section><h3 id="1601–1900">1601–1900</h3>
            <ul><li>1797 – André-Jacques Garnerin makes the first recorded parachute jump.<span class="mw-editsection">edit</span></li>
                <li>1879 – Thomas Edison tests his carbon filament lamp.
                    <ul><li>It burns for 13.5 hours.</li></ul></li></ul>
          </section>
        </section>
        <section><h2 id="Births">Births</h2><ul><li>1811 – Franz Liszt, Hungarian pianist and composer (d. 1886)</li><li>x</li></ul></section>
        <section><h2 id="Deaths">Deaths</h2><ul><li>1906 – Paul Cézanne, French painter (b. 1839)</li></ul></section>
        <section><h2 id="Holidays_and_observances">Holidays and observances</h2><ul><li>Not one of the three lists.</li></ul></section>
        </body></html>
    """.trimIndent()

    @Test fun readsTheThreeListsAndNothingElse() {
        val page = DayPage.parse(html)
        assertThat(page.events).containsExactly(
            "794 – Emperor Kanmu relocates the Japanese capital to Heian-kyō (now Kyoto).",
            "1383 – The male line of the Portuguese House of Burgundy becomes extinct.",
            "1797 – André-Jacques Garnerin makes the first recorded parachute jump.",
            "It burns for 13.5 hours.",
        ).inOrder()
        assertThat(page.births).containsExactly("1811 – Franz Liszt, Hungarian pianist and composer (d. 1886)")
        assertThat(page.deaths).containsExactly("1906 – Paul Cézanne, French painter (b. 1839)")
        assertThat(page.isEmpty).isFalse()
    }

    @Test fun headingsByTextAndTheOlderSpanMarkupWork() {
        val old = """<body><h2><span class="mw-headline" id="Events">Events</span></h2><ul><li>1 – Something happened here.</li></ul>
            <h2>Births</h2><ul><li>2 – Someone was born here.</li></ul></body>"""
        val page = DayPage.parse(old)
        assertThat(page.events).containsExactly("1 – Something happened here.")
        assertThat(page.births).containsExactly("2 – Someone was born here.")
        assertThat(DayPage.parse("<body><p>Nothing</p></body>").isEmpty).isTrue()
    }

    @Test fun longLinesAreCut() {
        val long = "1 – " + "word ".repeat(80)
        val page = DayPage.parse("<body><h2 id=\"Deaths\">Deaths</h2><ul><li>$long</li></ul></body>")
        assertThat(page.deaths.single().length).isAtMost(220)
        assertThat(page.deaths.single()).endsWith("…")
    }

    @Test fun theTitleAndPathsFollowWikipedia() {
        val day = LocalDate.of(2026, 10, 22)
        assertThat(DayPage.title(day)).isEqualTo("October 22")
        assertThat(DayPage.paths(day)).containsExactly("October_22", "A/October_22", "October 22").inOrder()
        assertThat(DayPage.title(LocalDate.of(2026, 3, 1))).isEqualTo("March 1")
    }
}
