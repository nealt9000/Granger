package app.granger.reader

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import app.granger.FakeLibrary
import app.granger.FakeReadingSessions
import app.granger.FakeZimArchive
import app.granger.core.library.ReadingTab
import app.granger.core.zim.ZimResolver
import app.granger.core.zim.ZimUrls
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** ← and system Back step back through the tab's pages, the WebView's own history first, then leave the reader. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReaderBackTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val library = FakeLibrary().apply { archives["p"] = FakeZimArchive("p", setOf("index", "C++", "Photosynthesis")) }
    private val sessions = FakeReadingSessions()
    private var left = 0
    private lateinit var viewModel: ReaderViewModel

    private fun url(path: String) = ZimUrls.forEntry("p", path)

    /** Shows tab 1, on the last of [stack], with the earlier pages stored behind it. */
    private fun show(vararg stack: String): ReaderWebView {
        sessions.tabs.value = listOf(ReadingTab(1, "p", stack.last(), stack.last(), 1L, onRail = true, backStack = stack.joinToString("\n")))
        viewModel = ReaderViewModel(1, library, sessions, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)
        compose.setContent {
            ReaderScreen(
                viewModel = viewModel,
                resolver = ZimResolver({ library.archives[it] }),
                onChromeVisibleChange = {},
                onSearchText = {},
                onBack = { left++ },
            )
        }
        compose.waitForIdle()
        return compose.activity.window.decorView.findWebView()!!
    }

    private fun arrow() {
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
    }

    private fun systemBack() {
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun stack() = sessions.tabs.value.single().backStack.split('\n')

    @Test fun backWithWebHistoryGoesBackInTheWebView() {
        val web = show("C++", "Photosynthesis")
        shadowOf(web).pushEntryToHistory(url("C++"))
        shadowOf(web).pushEntryToHistory(url("Photosynthesis"))
        systemBack()
        assertThat(shadowOf(web).goBackInvocations).isEqualTo(1)
        assertThat(stack()).containsExactly("C++")
        assertThat(left).isEqualTo(0)
    }

    @Test fun backWithoutWebHistoryLoadsTheStoredPreviousPath() {
        // As after a rail switch or process death: the WebView starts with only the tab's page.
        val web = show("C++", "Photosynthesis")
        arrow()
        assertThat(shadowOf(web).lastLoadedUrl).isEqualTo(url("C++"))
        assertThat(shadowOf(web).goBackInvocations).isEqualTo(0)
        assertThat(left).isEqualTo(0)
    }

    @Test fun backAtTheFirstPageLeavesTheReader() {
        show("C++")
        systemBack()
        assertThat(left).isEqualTo(1)
    }

    @Test fun arrowAndSystemBackDoTheSame() {
        val web = show("index", "C++", "Photosynthesis")
        arrow()
        assertThat(shadowOf(web).lastLoadedUrl).isEqualTo(url("C++"))
        systemBack()
        assertThat(shadowOf(web).lastLoadedUrl).isEqualTo(url("index"))
        assertThat(left).isEqualTo(0)
        arrow()
        assertThat(left).isEqualTo(1)
        systemBack()
        assertThat(left).isEqualTo(2)
    }

    @Test fun theFindBarAndPackSearchTakeBackFirst() {
        val web = show("C++", "Photosynthesis")
        compose.runOnIdle { viewModel.startFind() }
        systemBack()
        assertThat(viewModel.state.value.findQuery).isNull()
        compose.runOnIdle { viewModel.startPackSearch() }
        systemBack()
        assertThat(viewModel.state.value.packSearch).isNull()
        assertThat(shadowOf(web).lastLoadedUrl).isEqualTo(url("Photosynthesis"))
        assertThat(stack()).containsExactly("C++", "Photosynthesis").inOrder()
        assertThat(left).isEqualTo(0)
    }

    private fun View.findWebView(): ReaderWebView? {
        if (this is ReaderWebView) return this
        if (this is ViewGroup) for (i in 0 until childCount) getChildAt(i).findWebView()?.let { return it }
        return null
    }
}
