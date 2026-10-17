package app.granger.ebook

import app.granger.BookTarget
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

    @Test fun targetWithAFragmentStillMatchesItsChapter() = runTest {
        val target = StartLocator.choose(publication, null, BookTarget("c002.xhtml#p4", 0.1))!!
        assertThat(target.href.removeFragment()).isEqualTo(Url("c002.xhtml"))
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
