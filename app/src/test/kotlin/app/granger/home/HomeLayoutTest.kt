package app.granger.home

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Home as your desk (Plan O3, Neal 2026-11-19: A + B): Continue, Ready offline, the Timeline, up to two feeds. */
class HomeLayoutTest {
    private fun tokens(parts: List<HomePart>) = parts.map { it.token }

    @Test fun byDefaultContinueThenReadyOfflineThenTheTimelineThenTheFeeds() {
        assertThat(tokens(HomeLayout().parts(listOf(7, 3))))
            .containsExactly("continue", "readiness", "timeline", "feed:7", "feed:3").inOrder()
    }

    @Test fun atMostTwoFeedsShow() {
        assertThat(tokens(HomeLayout().visible(listOf(7, 3, 5)))).containsExactly("continue", "readiness", "timeline", "feed:7", "feed:3").inOrder()
        // Arrange still lists the third, so it can be moved up.
        assertThat(tokens(HomeLayout().parts(listOf(7, 3, 5)))).contains("feed:5")
    }

    @Test fun anArrangedOrderKeepsFeedsWhereTheyWerePutAndNewOnesJoinThem() {
        val arranged = HomeLayout.arranged(listOf(HomePart.Feed(3), HomePart.Continue, HomePart.Timeline, HomePart.Readiness), hidden = setOf("timeline"))
        assertThat(arranged.order).containsExactly("feed:3", "feeds", "continue", "timeline", "readiness").inOrder()
        // Feed 9 is new, feed 3 was removed.
        assertThat(tokens(arranged.visible(listOf(9)))).containsExactly("feed:9", "continue", "readiness").inOrder()
        assertThat(tokens(arranged.parts(listOf(3, 9)))).containsExactly("feed:3", "feed:9", "continue", "timeline", "readiness").inOrder()
    }

    @Test fun moveStepsOverHiddenSections() {
        val layout = HomeLayout(hidden = setOf("readiness"))
        assertThat(tokens(layout.move("timeline", up = true, feedIds = emptyList()).visible(emptyList()))).containsExactly("timeline", "continue").inOrder()
    }

    @Test fun aSavedOrderFromBeforeThisHomeStartsFromTheNewDefault() {
        // Play and Wander have left Home: an order that names them is from before, and Readiness moves up to second.
        val old = HomeLayout.decode("continue,timeline,play,wander,feeds,readiness", setOf("wander", "feed:4"))
        assertThat(old.order).isEqualTo(HomeLayout.DEFAULT)
        assertThat(old.hidden).containsExactly("feed:4")
        val now = HomeLayout.decode("timeline,continue,readiness,feeds", setOf("readiness"))
        assertThat(now.order).containsExactly("timeline", "continue", "readiness", "feeds").inOrder()
        assertThat(HomeLayout.decode(null, null).order).isEqualTo(HomeLayout.DEFAULT)
        val (order, hidden) = HomeLayout.encode(now)
        assertThat(HomeLayout.decode(order, hidden)).isEqualTo(now)
    }
}
