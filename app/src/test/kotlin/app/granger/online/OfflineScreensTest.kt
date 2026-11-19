package app.granger.online

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.granger.explore.Plate
import app.granger.explore.PlateView
import app.granger.theme.GrangerTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Plan O3 Tasks 4 and 6: what greys out offline, Search's line, and the "what it would take" sheet. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OfflineScreensTest {
    @get:Rule val compose = createComposeRule()

    private class Marks(val states: Map<String, Availability>) : MarkSource {
        override val version = MutableStateFlow(0)
        override suspend fun of(packId: String, path: String) = states[path] ?: Availability.Online
    }

    private class Reach(start: ReachState) : ReachControl {
        override val state = MutableStateFlow(start)
        override fun setLocalOnly(on: Boolean) { state.value = if (on) ReachState.LOCAL_ONLY else ReachState.ONLINE }
    }

    private val marks = Marks(mapOf("Kept" to Availability.OnPhone(true), "Read" to Availability.Saved))

    private fun plates(reach: ReachState) = compose.setContent {
        GrangerTheme {
            CompositionLocalProvider(LocalMarks provides marks, LocalReach provides Reach(reach)) {
                Column { for (p in listOf("Kept", "Read", "Web")) PlateView(Plate("p/$p", p, article = "wikipedia_en_online" to p), 14.sp, null, Modifier.size(120.dp)) {} }
            }
        }
    }

    @Test fun offlineOnlyTheOnlineOnlyPlateGreys() {
        plates(ReachState.LOCAL_ONLY)
        compose.waitForIdle()
        compose.onAllNodesWithTag(MarkTags.NEEDS_INTERNET, useUnmergedTree = true).assertCountEquals(1)
    }

    @Test fun onlineNothingGreys() {
        plates(ReachState.ONLINE)
        compose.waitForIdle()
        compose.onAllNodesWithTag(MarkTags.NEEDS_INTERNET, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun searchSaysOnlineResultsComeWithAConnection() {
        compose.setContent { GrangerTheme { CompositionLocalProvider(LocalReach provides Reach(ReachState.NO_SIGNAL)) { OfflineSearchLine() } } }
        compose.onNodeWithText("Online results appear when you're connected.").assertExists()
    }

    @Test fun theSheetOffersTheKeepAndThePack() {
        var kept = false
        var pack: String? = null
        val take = WouldTake("wikipedia_en_online", "Photosynthesis", "Photosynthesis", 1_900_000, PackOffer("wikipedia_en_top_mini", "Wikipedia Top", 2_700_000_000))
        compose.setContent { GrangerTheme { WouldTakeSheet(take, onKeepLater = { kept = true }, onGetPack = { pack = it }, onDismiss = {}) } }
        compose.onNodeWithText("Photosynthesis is online only").assertExists()
        compose.onNodeWithTag(WouldTakeTags.PACK).assertExists()
        compose.onNodeWithTag(WouldTakeTags.KEEP).performClick()
        assertThat(kept).isTrue()
        assertThat(pack).isNull()
    }
}
