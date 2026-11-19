package app.granger.online

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.granger.theme.GrangerTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Plan OB call 5: each hint shows once where its idea first appears; × or the act itself puts it away; Settings resets. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HintsTest {
    @get:Rule val compose = createComposeRule()

    private class MemoryStore : HintStore {
        val marks = mutableMapOf<String, Boolean>()
        override fun seen(key: String) = marks[key] == true
        override fun mark(key: String, seen: Boolean) { marks[key] = seen }
    }

    private class FakeReach(start: ReachState) : ReachControl {
        override val state = MutableStateFlow(start)
        override fun setLocalOnly(on: Boolean) { state.value = if (on) ReachState.LOCAL_ONLY else ReachState.ONLINE }
    }

    @Test fun eachHintShowsOnceAndResetBringsThemBack() {
        val store = MemoryStore()
        val hints = Hints(store)
        assertThat(hints.keep.value).isTrue()
        assertThat(hints.offline.value).isTrue()
        hints.keepDone()
        hints.offlineDone()
        assertThat(Hints(store).keep.value).isFalse()
        assertThat(Hints(store).offline.value).isFalse()
        hints.reset()
        assertThat(Hints(store).keep.value).isTrue()
        assertThat(hints.offline.value).isTrue()
    }

    @Test fun theOfflineHintRidesUnderTheBannerAndGoesWithX() {
        val hints = Hints(MemoryStore())
        var outfitting = 0
        compose.setContent {
            GrangerTheme {
                CompositionLocalProvider(LocalReach provides FakeReach(ReachState.LOCAL_ONLY), LocalHints provides hints) {
                    ReachBanner(onOutfitting = { outfitting++ })
                }
            }
        }
        compose.onNodeWithText("Greyed things need the internet. Outfitting shows what to keep.").assertExists()
        compose.onNodeWithText("Outfitting").performClick()
        assertThat(outfitting).isEqualTo(1)
        compose.onNodeWithContentDescription("Close").performClick()
        compose.onAllNodesWithTag(HintTags.OFFLINE).assertCountEquals(0)
        assertThat(hints.offline.value).isFalse()
    }

    @Test fun theKeepHintIsOnlyForAnOnlineArticleWhileOnline() {
        val hints = Hints(MemoryStore())
        val reach = FakeReach(ReachState.ONLINE)
        compose.setContent {
            GrangerTheme {
                CompositionLocalProvider(LocalReach provides reach, LocalHints provides hints) {
                    androidx.compose.foundation.layout.Column {
                        KeepHint("wikipedia_en_online", "Knowth")
                        KeepHint("wikipedia_en_top_maxi", "Knowth")
                    }
                }
            }
        }
        compose.onAllNodesWithTag(HintTags.KEEP).assertCountEquals(1)
        reach.state.value = ReachState.LOCAL_ONLY
        compose.onAllNodesWithTag(HintTags.KEEP).assertCountEquals(0)
        reach.state.value = ReachState.ONLINE
        compose.onNodeWithTag(HintTags.KEEP).assertExists()
        compose.onNodeWithContentDescription("Close").performClick()
        compose.onAllNodesWithTag(HintTags.KEEP).assertCountEquals(0)
    }
}
