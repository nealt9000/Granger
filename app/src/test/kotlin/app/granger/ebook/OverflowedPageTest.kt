package app.granger.ebook

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.util.Url
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OverflowedPageTest {
    private fun answer(scrolled: Int, screen: Int, whole: Int) = "\"$scrolled,$screen,$whole\""

    @Test fun theLastScreenWithLessThanAScreenLeftOverIsTrapped() {
        assertThat(OverflowedPage.isTrapped(answer(800, 400, 1300))).isTrue()
        assertThat(OverflowedPage.isTrapped(answer(0, 400, 450))).isTrue()
    }

    @Test fun whereAScreenOrMoreIsLeftATurnWorks() {
        assertThat(OverflowedPage.isTrapped(answer(0, 400, 1200))).isFalse()
        assertThat(OverflowedPage.isTrapped(answer(400, 400, 1300))).isFalse()
    }

    @Test fun aPageOfWholeScreensIsNotTrappedOnItsLast() {
        assertThat(OverflowedPage.isTrapped(answer(800, 400, 1200))).isFalse()
        // A column's width rounded across the page.
        assertThat(OverflowedPage.isTrapped("\"800,400,1200.5\"")).isFalse()
    }

    @Test fun aTurnStillMovingIsNotTrapped() {
        assertThat(OverflowedPage.isTrapped(answer(650, 400, 1200))).isFalse()
    }

    @Test fun anAnswerThatCantBeReadIsNotTrapped() {
        assertThat(OverflowedPage.isTrapped(null)).isFalse()
        assertThat(OverflowedPage.isTrapped("null")).isFalse()
        assertThat(OverflowedPage.isTrapped("\"1,2\"")).isFalse()
        assertThat(OverflowedPage.isTrapped(answer(0, 0, 0))).isFalse()
    }

    @Test fun theNextChapterFollowsTheHrefItsFragmentAside() {
        val order = listOf(Link(Url("a.xhtml")!!), Link(Url("b.xhtml")!!), Link(Url("c.xhtml")!!))
        assertThat(OverflowedPage.nextChapter(order, Url("a.xhtml#x")!!)).isEqualTo(order[1])
        assertThat(OverflowedPage.nextChapter(order, Url("c.xhtml")!!)).isNull()
        assertThat(OverflowedPage.nextChapter(order, Url("z.xhtml")!!)).isNull()
    }
}
