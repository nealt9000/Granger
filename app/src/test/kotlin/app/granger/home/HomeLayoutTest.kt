package app.granger.home

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HomeLayoutTest {
    private fun tokens(parts: List<HomePart>) = parts.map { it.token }

    @Test fun byDefaultContinueComesFirstAndFeedsSitBeforeReadiness() {
        assertThat(tokens(HomeLayout().parts(listOf(7, 3))))
            .containsExactly("continue", "timeline", "play", "wander", "feed:7", "feed:3", "readiness").inOrder()
    }

    @Test fun anArrangedOrderKeepsFeedsWhereTheyWerePutAndNewOnesJoinThem() {
        val arranged = HomeLayout.arranged(listOf(HomePart.Play, HomePart.Feed(3), HomePart.Continue, HomePart.Timeline, HomePart.Wander, HomePart.Readiness), hidden = setOf("wander"))
        assertThat(arranged.order).containsExactly("play", "feed:3", "feeds", "continue", "timeline", "wander", "readiness").inOrder()
        // Feed 9 is new, feed 3 was removed.
        assertThat(tokens(arranged.visible(listOf(9)))).containsExactly("play", "feed:9", "continue", "timeline", "readiness").inOrder()
        assertThat(tokens(arranged.parts(listOf(3, 9)))).containsExactly("play", "feed:3", "feed:9", "continue", "timeline", "wander", "readiness").inOrder()
    }

    @Test fun aSectionMissingFromAnOlderSavedOrderGoesWhereItWouldByDefault() {
        val old = HomeLayout(listOf("readiness", "continue", "timeline", "wander", "feeds"))
        assertThat(tokens(old.parts(emptyList()))).containsExactly("readiness", "continue", "timeline", "play", "wander").inOrder()
    }

    @Test fun moveStepsOverHiddenSections() {
        val layout = HomeLayout(hidden = setOf("timeline"))
        val moved = layout.move("play", up = true, feedIds = emptyList())
        assertThat(tokens(moved.visible(emptyList()))).containsExactly("play", "continue", "wander", "readiness").inOrder()
        assertThat(moved.move("play", up = true, feedIds = emptyList())).isEqualTo(moved)   // already first
    }

    @Test fun encodesAndDecodesAndFallsBackToTheDefault() {
        val layout = HomeLayout(listOf("play", "continue", "feeds"), setOf("play"))
        val (order, hidden) = HomeLayout.encode(layout)
        assertThat(HomeLayout.decode(order, hidden)).isEqualTo(layout)
        assertThat(HomeLayout.decode(null, null)).isEqualTo(HomeLayout())
        assertThat(HomeLayout.decode("", emptySet())).isEqualTo(HomeLayout())
    }
}
