package app.granger.reader

import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
import app.granger.settings.RailSide
import app.granger.shell.ChromeSlideMs
import app.granger.shell.GrangerShell
import app.granger.shell.NarrowRailWidth
import app.granger.shell.RailUi
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The Wikipedia reader opens, and comes back, with its chrome showing over the page; a middle tap or a scroll down by
 * hand slides it away, a scroll up brings it back, and the page keeps its size throughout (Plan U3c Decision 5).
 */
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

    private lateinit var root: View

    private fun show() = compose.setContent {
        root = LocalView.current.rootView
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
        compose.onNodeWithTag(ReaderBarTags.BOTTOM).assertIsDisplayed()
        assertThat(railVisible.last()).isTrue()
        // The bars are over the page (Plan U3c Decision 5): it still has the whole screen.
        val bar = compose.onNodeWithContentDescription("Back").getBoundsInRoot()
        val page = compose.onNodeWithTag(ReaderTags.PAGE).getBoundsInRoot()
        assertThat(page.top).isEqualTo(0.dp)
        assertThat(bar.top).isAtLeast(page.top)
        assertThat(bar.bottom).isLessThan(page.bottom)
    }

    private fun assertChromeAway() {
        compose.onNodeWithContentDescription("Back").assertDoesNotExist()
        compose.onNodeWithTag(ReaderBarTags.BOTTOM).assertDoesNotExist()
        assertThat(railVisible.last()).isFalse()
    }

    private fun pageBounds() = compose.onNodeWithTag(ReaderTags.PAGE).getBoundsInRoot()

    @Test fun opensWithTheToolbarAndTheRailShowing() {
        show()
        compose.waitForIdle()
        assertChromeShowing()
    }

    @Test fun aMiddleTapGoesFullScreenAndAnotherBringsTheToolbarBackWithoutResizingThePage() {
        show()
        compose.waitForIdle()
        val page = pageBounds()
        compose.runOnIdle { viewModel.toggleChrome() }
        compose.waitForIdle()
        assertChromeAway()
        assertThat(pageBounds()).isEqualTo(page)
        compose.runOnIdle { viewModel.toggleChrome() }
        compose.waitForIdle()
        assertChromeShowing()
        assertThat(pageBounds()).isEqualTo(page)
    }

    @Test fun aScrollDownByHandSlidesTheChromeAwayAndAScrollUpBringsItBack() {
        show()
        compose.waitForIdle()
        val page = pageBounds()
        // Under 24 dp down: still there.
        compose.runOnIdle { viewModel.onUserScroll(10f, byHand = true); viewModel.onUserScroll(10f, byHand = true) }
        compose.waitForIdle()
        assertChromeShowing()
        compose.runOnIdle { viewModel.onUserScroll(10f, byHand = true) }
        compose.waitForIdle()
        assertChromeAway()
        assertThat(pageBounds()).isEqualTo(page)
        // Up, but under 48 dp: still away. Past it: back.
        compose.runOnIdle { viewModel.onUserScroll(-40f, byHand = true) }
        compose.waitForIdle()
        assertChromeAway()
        compose.runOnIdle { viewModel.onUserScroll(-10f, byHand = true) }
        compose.waitForIdle()
        assertChromeShowing()
        assertThat(pageBounds()).isEqualTo(page)
    }

    @Test fun thePagesOwnScrollsLeaveTheChromeAlone() {
        // Restoring the place, a Contents jump: scrolls no finger made.
        show()
        compose.waitForIdle()
        compose.runOnIdle { viewModel.onUserScroll(2_000f, byHand = false) }
        compose.waitForIdle()
        assertChromeShowing()
        compose.runOnIdle { viewModel.toggleChrome() }
        compose.waitForIdle()
        compose.runOnIdle { viewModel.onUserScroll(-2_000f, byHand = false) }
        compose.waitForIdle()
        assertChromeAway()
    }

    @Test fun theTopBarSlidesUpAwayRatherThanFading() {
        show()
        compose.waitForIdle()
        val settled = compose.onNodeWithTag(ReaderBarTags.TOP).getBoundsInRoot()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            viewModel.toggleChrome()
            Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeBy(ChromeSlideMs / 2L)
        val sliding = compose.onNodeWithTag(ReaderBarTags.TOP).getBoundsInRoot()
        assertThat(sliding.top).isLessThan(settled.top)
        val bottom = compose.onNodeWithTag(ReaderBarTags.BOTTOM).getBoundsInRoot()
        assertThat(bottom.bottom).isGreaterThan(pageBounds().bottom)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithTag(ReaderBarTags.TOP).assertDoesNotExist()
    }

    @Test fun thePageIsPaddedForTheBarsOnceNotPerToggle() {
        // The padding is the bars' height (with their insets, none here), the same with the chrome up or away.
        show()
        compose.waitForIdle()
        val web = compose.runOnIdle { root.findWebView()!! }
        fun padding(): String? = compose.runOnIdle {
            web.applyChromePadding()
            shadowOf(web).lastEvaluatedJavascript
        }
        assertThat(padding()).isEqualTo(chromePaddingScript(64, 56))
        compose.runOnIdle { viewModel.toggleChrome() }
        compose.waitForIdle()
        assertThat(padding()).isEqualTo(chromePaddingScript(64, 56))
        compose.runOnIdle { viewModel.toggleChrome() }
        compose.waitForIdle()
        assertThat(padding()).isEqualTo(chromePaddingScript(64, 56))
    }

    /** The reader in the shell, as the app shows it: the rail on [side], over the page and sliding with the chrome. */
    private fun showInShell(side: RailSide) = compose.setContent {
        root = LocalView.current.rootView
        var chrome by remember { mutableStateOf(true) }
        GrangerShell(
            rail = RailUi(emptyList()), expanded = false, railVisible = chrome, selected = null,
            onToggleExpanded = {}, onEdgeSwipe = {}, onTool = {}, onTab = {}, onHistory = {}, onSearch = {},
            side = side, reading = true,
        ) {
            ReaderScreen(
                viewModel = viewModel,
                resolver = ZimResolver({ library.archives[it] }),
                onChromeVisibleChange = { railVisible += it; chrome = it },
                onSearchText = {},
                onBack = {},
            )
        }
    }

    @Test fun theLinesStartClearOfTheRailWithTheChromeUpOrAway() {
        // The rail is over the page's left edge; the page pads its side for it, once, not per toggle.
        showInShell(RailSide.LEFT)
        compose.waitForIdle()
        val web = compose.runOnIdle { root.findWebView()!! }
        fun padding(): String? = compose.runOnIdle {
            web.applyChromePadding()
            shadowOf(web).lastEvaluatedJavascript
        }
        val rail = compose.onNodeWithContentDescription("Home").getBoundsInRoot()
        val page = pageBounds()
        val textStart = page.left + NarrowRailWidth
        assertThat(rail.right).isAtMost(textStart)
        assertThat(padding()).isEqualTo(chromePaddingScript(64, 56, leftDp = NarrowRailWidth.value.toInt()))
        compose.runOnIdle { viewModel.toggleChrome() }
        compose.waitForIdle()
        assertChromeAway()
        assertThat(pageBounds()).isEqualTo(page)
        assertThat(padding()).isEqualTo(chromePaddingScript(64, 56, leftDp = NarrowRailWidth.value.toInt()))
    }

    @Test fun aRailOnTheRightKeepsTheLinesEndsClear() {
        showInShell(RailSide.RIGHT)
        compose.waitForIdle()
        val web = compose.runOnIdle { root.findWebView()!! }
        val rail = compose.onNodeWithContentDescription("Home").getBoundsInRoot()
        assertThat(rail.left).isAtLeast(pageBounds().right - NarrowRailWidth)
        val padding = compose.runOnIdle {
            web.applyChromePadding()
            shadowOf(web).lastEvaluatedJavascript
        }
        assertThat(padding).isEqualTo(chromePaddingScript(64, 56, rightDp = NarrowRailWidth.value.toInt()))
    }

    /** A portrait cutout (the front camera) stays clear of the book's words with the bars away; the box doesn't move. */
    @Test fun aBooksPageBoxIsClearOfTheCameraCutout() {
        var chrome by mutableStateOf(true)
        lateinit var view: View
        var density = Density(1f)
        compose.setContent {
            view = LocalView.current
            density = LocalDensity.current
            GrangerShell(
                rail = RailUi(emptyList()), expanded = false, railVisible = chrome, selected = null,
                onToggleExpanded = {}, onEdgeSwipe = {}, onTool = {}, onTab = {}, onHistory = {}, onSearch = {},
                reading = true,
            ) {
                Box(Modifier.fillMaxSize().windowInsetsPadding(ReaderBookPageInsets)) { Box(Modifier.fillMaxSize().testTag("book")) }
            }
        }
        val cutoutPx = 90
        compose.runOnIdle {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(0, cutoutPx, 0, 0))
                .build()
            ViewCompat.dispatchApplyWindowInsets(view, insets)
        }
        compose.waitForIdle()
        val book = compose.onNodeWithTag("book").getBoundsInRoot()
        assertThat(book.top).isEqualTo(with(density) { cutoutPx.toDp() })
        compose.runOnIdle { chrome = false }
        compose.waitForIdle()
        assertThat(compose.onNodeWithTag("book").getBoundsInRoot()).isEqualTo(book)
    }

    @Test fun aBooksPageBoxIsClearOfTheRail() {
        // The e-book and PDF navigator's box (Readium can't run here): padded for the rail's place, chrome up or away.
        var chrome by mutableStateOf(true)
        compose.setContent {
            GrangerShell(
                rail = RailUi(emptyList()), expanded = false, railVisible = chrome, selected = null,
                onToggleExpanded = {}, onEdgeSwipe = {}, onTool = {}, onTab = {}, onHistory = {}, onSearch = {},
                reading = true,
            ) {
                Box(Modifier.fillMaxSize().windowInsetsPadding(ReaderBookPageInsets)) { Box(Modifier.fillMaxSize().testTag("book")) }
            }
        }
        compose.waitForIdle()
        val rail = compose.onNodeWithContentDescription("Home").getBoundsInRoot()
        val book = compose.onNodeWithTag("book").getBoundsInRoot()
        assertThat(book.left).isAtLeast(rail.right)
        compose.runOnIdle { chrome = false }
        compose.waitForIdle()
        assertThat(compose.onNodeWithTag("book").getBoundsInRoot()).isEqualTo(book)
    }

    private fun View.findWebView(): ReaderWebView? {
        if (this is ReaderWebView) return this
        if (this is ViewGroup) for (i in 0 until childCount) getChildAt(i).findWebView()?.let { return it }
        return null
    }

    @Test fun returningToTheTabShowsTheToolbarAgain() {
        show()
        compose.waitForIdle()
        compose.runOnIdle { viewModel.toggleChrome() }
        compose.waitForIdle()
        assertChromeAway()
        // Another screen on top, then back to the tab (its view model kept on the back stack).
        compose.runOnIdle { shown = false }
        compose.waitForIdle()
        compose.runOnIdle { shown = true }
        compose.waitForIdle()
        assertChromeShowing()
    }

    @Test fun theBridgeHandsTheScrollOnToTheMainThread() {
        val scrolls = mutableListOf<Pair<Float, Boolean>>()
        val bridge = GrangerBridge(onToggleChrome = {}, onUserScroll = { dy, hand -> scrolls += dy to hand })
        bridge.onUserScroll(-30.5, true)
        bridge.onUserScroll(400.0, false)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertThat(scrolls).containsExactly(-30.5f to true, 400f to false).inOrder()
    }

    @Test fun theBridgeKeepsATappedLinksWordsForItsAddress() {
        val bridge = GrangerBridge(onToggleChrome = {})
        assertThat(bridge.linkTextFor("https://zim.granger.invalid/z/docs/water.pdf")).isEmpty()
        bridge.noteLink("https://zim.granger.invalid/z/docs/water%20store.pdf", "  Storing water\n for a year ")
        // Kept at once, not posted: the WebView asks before the main thread runs anything else.
        assertThat(bridge.linkTextFor("https://zim.granger.invalid/z/docs/water store.pdf#page=2")).isEqualTo("Storing water for a year")
        assertThat(bridge.linkTextFor("https://zim.granger.invalid/z/docs/other.pdf")).isEmpty()
    }
}
