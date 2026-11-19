package app.granger.online

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.granger.explore.Plate
import app.granger.explore.PlateView
import app.granger.theme.GrangerTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size

/** Plan O2 Tasks 4 and 5: the marks (dot, ring, nothing) and the Keep button. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MarksTest {
    @get:Rule val compose = createComposeRule()

    private class FakeMarks(val states: Map<String, Availability>) : MarkSource {
        override val version = MutableStateFlow(0)
        var keptNow: Boolean? = null
        override suspend fun of(packId: String, path: String) = states[path] ?: Availability.Online
        override fun kept(packId: String, path: String) = keptNow
        override suspend fun keepSize(packId: String, path: String): Long? = 180_000
        override suspend fun keep(packId: String, path: String): Boolean { keptNow = true; version.value++; return true }
        override suspend fun remove(packId: String, path: String) { keptNow = null; version.value++ }
    }

    private val marks = FakeMarks(
        mapOf("Kept" to Availability.OnPhone(full = true), "Short" to Availability.OnPhone(full = false), "Read" to Availability.Saved),
    )

    private fun plate(path: String) = Plate("p/$path", path, article = "wikipedia_en_online" to path)

    @Test fun eachStateHasItsMarkAndOnlineHasNone() {
        compose.setContent {
            GrangerTheme {
                CompositionLocalProvider(LocalMarks provides marks) {
                    androidx.compose.foundation.layout.Column {
                        for (p in listOf("Kept", "Short", "Read", "Web")) PlateView(plate(p), 14.sp, null, androidx.compose.ui.Modifier.androidxSize()) {}
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onAllNodesWithTag(MarkTags.ON_PHONE, useUnmergedTree = true).assertCountEquals(2)
        compose.onAllNodesWithTag(MarkTags.SAVED, useUnmergedTree = true).assertCountEquals(1)
    }

    @Test fun marksReadTheirStateForTalkBack() {
        compose.setContent {
            GrangerTheme {
                androidx.compose.foundation.layout.Column {
                    Mark(Availability.OnPhone(full = true)); Mark(Availability.OnPhone(full = false)); Mark(Availability.Saved); Mark(Availability.Online)
                }
            }
        }
        compose.onNodeWithContentDescription("On this phone").assertExists()
        compose.onNodeWithContentDescription("On this phone (shorter)").assertExists()
        compose.onNodeWithContentDescription("Saved for now").assertExists()
    }

    @Test fun keepShowsItsSizeThenRemove() {
        compose.setContent {
            GrangerTheme { CompositionLocalProvider(LocalMarks provides marks) { KeepButton("wikipedia_en_online", "Knowth") } }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Keep · ", substring = true).assertExists()
        compose.onNodeWithTag(KeepTags.KEEP).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Kept · Remove").assertExists().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(KeepTags.KEEP).assertExists()
    }

    @Test fun aPacksArticleHasNoKeepButton() {
        compose.setContent {
            GrangerTheme { CompositionLocalProvider(LocalMarks provides marks) { KeepButton("wikipedia_en_top_maxi", "Knowth") } }
        }
        compose.onAllNodesWithTag(KeepTags.KEEP).assertCountEquals(0)
    }
}

private fun androidx.compose.ui.Modifier.androidxSize() = this.size(120.dp)
