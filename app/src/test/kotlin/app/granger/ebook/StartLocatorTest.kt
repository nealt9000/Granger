package app.granger.ebook

import app.granger.BookTarget
import app.granger.core.books.TextQuote
import app.granger.testPdfPublication
import app.granger.testPublication
import com.google.common.truth.Truth.assertThat
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
class StartLocatorTest {
    private val publication = testPublication()

    private fun savedJson(href: String, progression: Double = 0.4, total: Double = 0.5): String =
        Locator(
            href = Url(href)!!,
            mediaType = MediaType.XHTML,
            title = "Saved",
            locations = Locator.Locations(progression = progression, totalProgression = total),
        ).toJSON().toString()

    @Test fun aPdfTargetOpensAtItsPageWithoutTheQuote() = runTest {
        val pdf = testPdfPublication(pages = 3)
        val quote = TextQuote("before ", "sand filter", " after")
        val at = StartLocator.choose(pdf, null, BookTarget("page/2", 0.4, quote))!!
        assertThat(at.locations.position).isEqualTo(2)
        assertThat(at.locations.fragments).containsExactly("page=2")
        assertThat(at.text.highlight).isNull()
        // Past the last page (an older edition's index): the last page.
        assertThat(StartLocator.choose(pdf, null, BookTarget("page/9", 0.0))!!.locations.position).isEqualTo(3)
        // Not a page: the saved place, else the start.
        assertThat(StartLocator.choose(pdf, null, BookTarget("OEBPS/c.xhtml", 0.0))).isNull()
    }

    @Test fun startLocatorPrefersRouteTargetThenSavedThenStart() = runTest {
        val saved = savedJson("c003.xhtml", progression = 0.25)

        val target = StartLocator.choose(publication, saved, BookTarget("c002.xhtml", 0.75))!!
        assertThat(target.href).isEqualTo(Url("c002.xhtml"))
        assertThat(target.locations.progression).isEqualTo(0.75)

        // A target outside the reading order (a stale search hit) is skipped for the saved place.
        val fromSaved = StartLocator.choose(publication, saved, BookTarget("gone.xhtml", 0.5))!!
        assertThat(fromSaved.href).isEqualTo(Url("c003.xhtml"))
        assertThat(fromSaved.locations.progression).isEqualTo(0.25)

        assertThat(StartLocator.choose(publication, null, null)).isNull()
    }

    @Test fun targetQuoteBecomesTheLocatorText() = runTest {
        val quote = TextQuote("PORTIA. The ", "quality of mercy", " is not strain'd")
        val target = StartLocator.choose(publication, null, BookTarget("c002.xhtml", 0.39, quote))!!
        // Readium finds this text on the page before it falls back to the progression.
        assertThat(target.text.highlight).isEqualTo("quality of mercy")
        assertThat(target.text.before).isEqualTo("PORTIA. The ")
        assertThat(target.text.after).isEqualTo(" is not strain'd")
        assertThat(target.locations.progression).isEqualTo(0.39)

        val plain = StartLocator.choose(publication, null, BookTarget("c002.xhtml", 0.39))!!
        assertThat(plain.text.highlight).isNull()
    }

    @Test fun targetWithAFragmentStillMatchesItsChapter() = runTest {
        val target = StartLocator.choose(publication, null, BookTarget("c002.xhtml#p4", 0.1))!!
        assertThat(target.href.removeFragment()).isEqualTo(Url("c002.xhtml"))
    }

    @Test fun targetHrefIsADecodedZipPath() = runTest {
        // The book index stores decoded zip paths; Readium's reading order holds them percent-encoded.
        val spaced = testPublication(chapters = listOf("OEBPS/Title Page.xhtml", "OEBPS/Chapter 1.xhtml", "OEBPS/Café.xhtml"))
        val chapter = StartLocator.choose(spaced, null, BookTarget("OEBPS/Chapter 1.xhtml#x", 0.3))!!
        assertThat(chapter.href).isEqualTo(Url.fromDecodedPath("OEBPS/Chapter 1.xhtml"))
        assertThat(chapter.locations.fragments).containsExactly("x")
        assertThat(chapter.locations.progression).isEqualTo(0.3)

        val accented = StartLocator.choose(spaced, null, BookTarget("OEBPS/Café.xhtml", 0.0))!!
        assertThat(accented.href).isEqualTo(Url.fromDecodedPath("OEBPS/Café.xhtml"))

        // A place Readium saved (its JSON holds the encoded href) still matches its chapter.
        val savedJson = Locator(
            href = Url.fromDecodedPath("OEBPS/Chapter 1.xhtml")!!,
            mediaType = MediaType.XHTML,
            locations = Locator.Locations(progression = 0.6, totalProgression = 0.5),
        ).toJSON().toString()
        val saved = StartLocator.choose(spaced, savedJson, null)!!
        assertThat(saved.href).isEqualTo(Url.fromDecodedPath("OEBPS/Chapter 1.xhtml"))
        assertThat(saved.locations.progression).isEqualTo(0.6)
    }

    @Test fun staleHrefFallsBackToTotalProgression() = runTest {
        // A rebuilt edition renamed its chapter files; the saved place is kept by how far through the book it was.
        val locator = StartLocator.choose(publication, savedJson("c009.xhtml", total = 0.5), null)!!
        assertThat(locator.href.removeFragment()).isEqualTo(Url("c002.xhtml"))
    }

    @Test fun garbageSavedJsonStartsAtTheBeginning() = runTest {
        assertThat(StartLocator.choose(publication, "not json {", null)).isNull()
        assertThat(StartLocator.choose(publication, "{\"type\":\"text/html\"}", null)).isNull()
        assertThat(StartLocator.choose(publication, "", null)).isNull()
    }
}
