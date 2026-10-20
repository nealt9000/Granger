package app.granger.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import app.granger.FakeLibrary
import app.granger.FakeReadingSessions
import app.granger.FakeZimArchive
import app.granger.core.library.ReadingTab
import app.granger.core.zim.ZimResolver
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Wikipedia reader opens, and comes back, with its toolbar showing; a middle tap goes full screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReaderScreenChromeTest {
    @get:Rule val compose = createComposeRule()

    private val library = FakeLibrary().apply { archives["p"] = FakeZimArchive("p", setOf("index", "C++")) }
    private val sessions = FakeReadingSessions().apply {
        tabs.value = listOf(ReadingTab(1, "p", "C++", "C++", 1L, onRail = true))
    }
    private val viewModel = ReaderViewModel(1, library, sessions, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)

    /** What the screen last told the shell about the rail. */
    private val railVisible = mutableListOf<Boolean>()

    private var shown by mutableStateOf(true)

    private fun show() = compose.setContent {
        if (shown) {
            ReaderScreen(
                viewModel = viewModel,
                resolver = ZimResolver({ library.archives[it] }),
                onChromeVisibleChange = { railVisible += it },
                onSearchText = {},
                onBack = {},
            )
        }
    }

    private fun assertChromeShowing() {
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        assertThat(railVisible.last()).isTrue()
        // The page is laid out below the top bar, not drawn under it.
        val bar = compose.onNodeWithContentDescription("Back").getBoundsInRoot()
        assertThat(compose.onNodeWithTag(ReaderTags.PAGE).getBoundsInRoot().top).isAtLeast(bar.bottom)
    }

    @Test fun opensWithTheToolbarAndTheRailShowing() {
        show()
        compose.waitForIdle()
        assertChromeShowing()
    }

    @Test fun aMiddleTapGoesFullScreenAndAnotherBringsTheToolbarBack() {
        show()
        compose.waitForIdle()
        compose.runOnIdle { viewModel.toggleChrome() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Back").assertDoesNotExist()
        assertThat(railVisible.last()).isFalse()
        assertThat(compose.onNodeWithTag(ReaderTags.PAGE).getBoundsInRoot().top).isEqualTo(0.dp)
        compose.runOnIdle { viewModel.toggleChrome() }
        compose.waitForIdle()
        assertChromeShowing()
    }

    @Test fun returningToTheTabShowsTheToolbarAgain() {
        show()
        compose.waitForIdle()
        compose.runOnIdle { viewModel.toggleChrome() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Back").assertDoesNotExist()
        // Another screen on top, then back to the tab (its view model kept on the back stack).
        compose.runOnIdle { shown = false }
        compose.waitForIdle()
        compose.runOnIdle { shown = true }
        compose.waitForIdle()
        assertChromeShowing()
    }
}
