package app.granger.online

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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

/** Plan O3 Tasks 2 and 3: the switch flips Local only; the banner says which offline it is. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalOnlyTest {
    @get:Rule val compose = createComposeRule()

    private class FakeReach(start: ReachState) : ReachControl {
        override val state = MutableStateFlow(start)
        override fun setLocalOnly(on: Boolean) { state.value = if (on) ReachState.LOCAL_ONLY else ReachState.ONLINE }
    }

    private fun show(reach: FakeReach) = compose.setContent {
        GrangerTheme { CompositionLocalProvider(LocalReach provides reach) { androidx.compose.foundation.layout.Column { LocalOnlyButton(); ReachBanner() } } }
    }

    @Test fun theSwitchFlipsLocalOnlyAndTheBannerFollows() {
        val reach = FakeReach(ReachState.ONLINE)
        show(reach)
        compose.onAllNodesWithTag(LocalOnlyTags.BANNER).assertCountEquals(0)
        compose.onNodeWithTag(LocalOnlyTags.SWITCH).performClick()
        assertThat(reach.state.value).isEqualTo(ReachState.LOCAL_ONLY)
        compose.onNodeWithText("Local only. Showing what this phone holds.").assertExists()
        compose.onNodeWithTag(LocalOnlyTags.TURN_OFF).performClick()
        assertThat(reach.state.value).isEqualTo(ReachState.ONLINE)
    }

    @Test fun noConnectionSaysSoWithNoTurnOff() {
        show(FakeReach(ReachState.NO_SIGNAL))
        compose.onNodeWithText("No connection. Showing what this phone holds.").assertExists()
        compose.onAllNodesWithTag(LocalOnlyTags.TURN_OFF).assertCountEquals(0)
    }
}
