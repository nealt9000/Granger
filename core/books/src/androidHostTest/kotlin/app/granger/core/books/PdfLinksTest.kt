package app.granger.core.books

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PdfLinksTest {
    @get:Rule val tmp = TemporaryFolder()

    @Before fun setUp() = PdfFixtures.init(ApplicationProvider.getApplicationContext())

    @Test fun aContentsPagesLinksAreReadHoweverTheyNameTheirPage() {
        val links = PdfLinks.read(PdfFixtures.linked(tmp.root))
        // A direct destination, a GoTo action and a named destination.
        assertThat(links.map { it.page to it.target }).containsExactly(0 to 1, 0 to 2, 0 to 3).inOrder()
        val box = PdfFixtures.LINK_RECTS[1]
        assertThat(listOf(links[1].left, links[1].bottom, links[1].right, links[1].top)).containsExactly(box[0], box[1], box[2], box[3]).inOrder()
    }

    @Test fun aPdfWithoutLinksOrUnreadableHasNone() {
        assertThat(PdfLinks.read(PdfFixtures.text(tmp.root))).isEmpty()
        assertThat(PdfLinks.read(PdfFixtures.truncated(tmp.root))).isEmpty()
    }

    @Test fun aTappedBoxIsMatchedEitherWayUpAndNearestThePageShown() {
        val links = listOf(
            PdfLink(0, 72f, 600f, 300f, 620f, 5),
            PdfLink(9, 72f, 600f, 300f, 620f, 12),
            PdfLink(0, 72f, 570f, 300f, 590f, 7),
        )
        // PDFium gives the box top first; a fraction of a point off still matches.
        assertThat(PdfLinks.target(links, 72.4f, 620f, 300f, 600f, nearPage = 1)).isEqualTo(5)
        assertThat(PdfLinks.target(links, 72f, 620f, 300f, 600f, nearPage = 8)).isEqualTo(12)
        assertThat(PdfLinks.target(links, 72f, 590f, 300f, 570f, nearPage = 8)).isEqualTo(7)
        assertThat(PdfLinks.target(links, 10f, 100f, 50f, 80f, nearPage = 0)).isNull()
    }
}
