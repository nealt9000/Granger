package app.granger.reader

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.granger.BookRoute
import app.granger.HomeRoute
import app.granger.LibraryRoute
import app.granger.ReaderRoute
import app.granger.SearchRoute
import app.granger.leaveRemovedTab
import app.granger.openTab
import app.granger.showTab
import app.granger.FakeLibrary
import app.granger.FakeReadingSessions
import app.granger.FakeZimArchive
import app.granger.core.library.BookIds
import app.granger.core.library.BookOrigin
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

/**
 * ← and system Back step back through the tab's pages, the WebView's own history first, then leave the reader; a tab
 * opened over a reader from the rail, Home or Search leaves it underneath, for Back to return to.
 */
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

    /** A back stack of the routes alone, starting at the Library, for where Back goes between screens. */
    private fun routes(): NavHostController {
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = LibraryRoute()) {
                composable<LibraryRoute> {}
                composable<HomeRoute> {}
                composable<SearchRoute> {}
                composable<ReaderRoute> {}
                composable<BookRoute> {}
            }
        }
        compose.waitForIdle()
        return nav
    }

    private fun NavHostController.readers(): List<Long> = compose.runOnIdle {
        currentBackStack.value.mapNotNull {
            when {
                it.destination.hasRoute<ReaderRoute>() -> it.toRoute<ReaderRoute>().tabId
                it.destination.hasRoute<BookRoute>() -> it.toRoute<BookRoute>().tabId
                else -> null
            }
        }
    }

    private val article = ReadingTab(1, "p", "C++", "C++", 1L, onRail = true)
    private val other = ReadingTab(2, "p", "Photosynthesis", "Photosynthesis", 2L, onRail = true)
    private val book = ReadingTab(3, BookIds.of(BookOrigin.IMPORT, "b"), "", "A book", 3L, onRail = true)

    @Test fun aTabOpenedFromTheRailOverAReaderLeavesItUnderneath() {
        // Plan U3c Decision 7: the rail's open item stacks on the page you're on, so Back returns to it.
        val nav = routes()
        compose.runOnIdle { nav.openTab(article) }
        compose.runOnIdle { nav.openTab(other) }
        assertThat(nav.readers()).containsExactly(1L, 2L).inOrder()
        compose.runOnIdle { nav.openTab(book) }
        assertThat(nav.readers()).containsExactly(1L, 2L, 3L).inOrder()
        compose.runOnIdle { nav.popBackStack() }
        assertThat(compose.runOnIdle { nav.currentBackStackEntry!!.toRoute<ReaderRoute>().tabId }).isEqualTo(2L)
        compose.runOnIdle { nav.popBackStack() }
        assertThat(compose.runOnIdle { nav.currentBackStackEntry!!.toRoute<ReaderRoute>().tabId }).isEqualTo(1L)
    }

    @Test fun theTabOnTopAgainIsNoMove() {
        val nav = routes()
        compose.runOnIdle { nav.openTab(article) }
        compose.runOnIdle { nav.openTab(article) }
        assertThat(nav.readers()).containsExactly(1L)
    }

    @Test fun fromHomeOrSearchOverAReaderBackStepsThroughThemToIt() {
        val nav = routes()
        compose.runOnIdle { nav.openTab(article) }
        compose.runOnIdle { nav.navigate(SearchRoute("leaf")) }
        compose.runOnIdle { nav.openTab(other) }
        compose.runOnIdle { nav.popBackStack() }
        assertThat(compose.runOnIdle { nav.currentDestination!!.hasRoute<SearchRoute>() }).isTrue()
        compose.runOnIdle { nav.popBackStack() }
        assertThat(compose.runOnIdle { nav.currentBackStackEntry!!.toRoute<ReaderRoute>().tabId }).isEqualTo(1L)
        compose.runOnIdle { nav.navigate(HomeRoute) }
        compose.runOnIdle { nav.openTab(other) }
        assertThat(nav.readers()).containsExactly(1L, 2L).inOrder()
    }

    /** Plan U3c walkthrough: removing the open item from the rail closes it and goes Home. */
    @Test fun removingTheTabOnScreenGoesHome() {
        val nav = routes()
        compose.runOnIdle { nav.navigate(HomeRoute) }
        compose.runOnIdle { nav.openTab(article) }
        compose.runOnIdle { nav.openTab(other) }
        // A tab not on screen leaves the reader where it is.
        compose.runOnIdle { nav.leaveRemovedTab(article.id) }
        assertThat(nav.readers()).containsExactly(1L, 2L).inOrder()
        compose.runOnIdle { nav.leaveRemovedTab(other.id) }
        assertThat(compose.runOnIdle { nav.currentDestination!!.hasRoute<HomeRoute>() }).isTrue()
        assertThat(nav.readers()).isEmpty()
    }

    @Test fun removingTheTabOnScreenWithNoHomeUnderneathReplacesItWithHome() {
        val nav = routes()
        compose.runOnIdle { nav.openTab(article) }
        compose.runOnIdle { nav.leaveRemovedTab(article.id) }
        assertThat(compose.runOnIdle { nav.currentDestination!!.hasRoute<HomeRoute>() }).isTrue()
        assertThat(nav.readers()).isEmpty()
        compose.runOnIdle { nav.popBackStack() }
        assertThat(compose.runOnIdle { nav.currentDestination!!.hasRoute<LibraryRoute>() }).isTrue()
    }

    @Test fun showTabStillReplacesAReaderOnTop() {
        // The plain open (a citation, a pack's main page) swaps the reader rather than stacking one per tab.
        val nav = routes()
        compose.runOnIdle { nav.showTab(article) }
        compose.runOnIdle { nav.showTab(other) }
        assertThat(nav.readers()).containsExactly(2L)
    }

    private fun View.findWebView(): ReaderWebView? {
        if (this is ReaderWebView) return this
        if (this is ViewGroup) for (i in 0 until childCount) getChildAt(i).findWebView()?.let { return it }
        return null
    }
}
