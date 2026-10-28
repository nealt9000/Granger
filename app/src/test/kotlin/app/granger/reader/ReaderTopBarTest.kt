package app.granger.reader

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

/** The wiki reader's top bar: ←, Home, the pack's search box, Listen, the star and ⋯; no page title. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReaderTopBarTest {
    @get:Rule val compose = createComposeRule()

    private fun bar(packTitle: String? = "Wikipedia", hasHome: Boolean = true, voice: app.granger.player.VoiceButton = app.granger.player.VoiceButton.IDLE, onReadAloud: (() -> Unit)? = null,
                    onPauseResume: () -> Unit = {}, onStop: () -> Unit = {}) = compose.setContent {
        ReaderTopBar(
            pageTitle = "Photosynthesis",
            packTitle = packTitle,
            showPageActions = true,
            bookmarked = false,
            showMenu = true,
            hasHome = hasHome,
            onBack = {},
            onSearchPack = {},
            onHome = {},
            onRandom = {},
            onFind = {},
            onToggleBookmark = {},
            voice = voice,
            onReadAloud = onReadAloud,
            onPauseResume = onPauseResume,
            onStop = onStop,
        )
    }

    @Test fun listenIsOnTheBarNotInTheMenu() {
        var taps = 0
        bar(onReadAloud = { taps++ })
        compose.onNodeWithContentDescription("Listen").performClick()
        assertThat(taps).isEqualTo(1)
        compose.onNodeWithContentDescription("Stop").assertDoesNotExist()   // Stop only while a session is live
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Read aloud").assertDoesNotExist()
        compose.onNodeWithText("Stop reading").assertDoesNotExist()
    }

    @Test fun whileThisPageIsReadListenPausesAndStopSitsBesideIt() {
        val calls = mutableListOf<String>()
        bar(voice = app.granger.player.VoiceButton.READING, onReadAloud = { calls += "start" }, onPauseResume = { calls += "pause" }, onStop = { calls += "stop" })
        compose.onNodeWithContentDescription("Listen").assertDoesNotExist()
        compose.onNodeWithContentDescription("Pause").performClick()
        compose.onNodeWithContentDescription("Stop").performClick()
        assertThat(calls).containsExactly("pause", "stop").inOrder()
        assertThat(compose.onNodeWithContentDescription("Pause").left()).isLessThan(compose.onNodeWithContentDescription("Stop").left())
        assertThat(compose.onNodeWithContentDescription("Stop").left()).isLessThan(compose.onNodeWithContentDescription("More").left())
    }

    @Test fun pausedShowsPlay() {
        val calls = mutableListOf<String>()
        bar(voice = app.granger.player.VoiceButton.PAUSED, onReadAloud = {}, onPauseResume = { calls += "play" })
        compose.onNodeWithContentDescription("Play").performClick()
        compose.onNodeWithContentDescription("Stop").assertExists()
        assertThat(calls).containsExactly("play")
    }

    @Test fun withoutTheVoiceThereIsNoListen() {
        bar()
        compose.onNodeWithContentDescription("Listen").assertDoesNotExist()
    }

    private fun SemanticsNodeInteraction.left() = getBoundsInRoot().left

    @Test fun noPageTitleIsShown() {
        bar()
        compose.onNodeWithText("Photosynthesis").assertDoesNotExist()
        // TalkBack still hears which page the bar is over.
        compose.onNodeWithContentDescription("Photosynthesis").assertExists()
    }

    // Wide enough for the whole "Search Wikipedia" (ReaderTopBarFitTest covers a phone).
    @Config(qualifiers = "w600dp-h800dp")
    @Test fun barOrderIsBackMainPageSearchListenStarMore() {
        bar(onReadAloud = {})
        val lefts = listOf(
            compose.onNodeWithContentDescription("Back").left(),
            compose.onNodeWithContentDescription("Wikipedia: main page").left(),
            compose.onNodeWithText("Search Wikipedia").left(),
            compose.onNodeWithContentDescription("Listen").left(),
            compose.onNodeWithContentDescription("Add bookmark").left(),
            compose.onNodeWithContentDescription("More").left(),
        )
        assertThat(lefts).isInStrictOrder()
    }

    @Test fun searchBoxSaysSearchAndThePackTitle() {
        bar(packTitle = "Wikipedia")
        compose.onNodeWithText("Search Wikipedia").assertExists()
    }

    @Test fun searchBoxWithoutAPackTitleSaysThisPack() {
        bar(packTitle = null)
        compose.onNodeWithText("Search this pack").assertExists()
    }

    @Test fun moreHoldsRandomAndFindOnly() {
        bar()
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Random article").assertExists()
        compose.onNodeWithText("Find in page").assertExists()
        compose.onNodeWithText("Home").assertDoesNotExist()
    }

    @Test fun theMainPageButtonNamesThePackNotHome() {
        bar(packTitle = "Wikipedia")
        compose.onNodeWithContentDescription("Wikipedia: main page").assertExists()
        // Home is the app's (the rail's owl), not the pack's.
        compose.onNodeWithContentDescription("Home").assertDoesNotExist()
        assertThat(mainPageLabel(null)).isEqualTo("Main page")
    }

    @Test fun homeHiddenWhenThePackHasNone() {
        bar(hasHome = false)
        compose.onNodeWithContentDescription("Wikipedia: main page").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").assertExists()
    }

    @Test fun tappingTheSearchBoxOpensThePackSearchWithFocus() {
        val library = FakeLibrary().apply { archives["p"] = FakeZimArchive("Wikipedia", setOf("index", "C++")) }
        val sessions = FakeReadingSessions().apply { tabs.value = listOf(ReadingTab(1, "p", "C++", "C++", 1L, onRail = true)) }
        val viewModel = ReaderViewModel(1, library, sessions, CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)
        compose.setContent {
            ReaderScreen(
                viewModel = viewModel,
                resolver = ZimResolver({ library.archives[it] }),
                onChromeVisibleChange = {},
                onSearchText = {},
                onBack = {},
            )
        }
        compose.onNodeWithText("Search Wikipedia").performClick()
        compose.waitForIdle()
        assertThat(viewModel.state.value.packSearch).isNotNull()
        compose.onNode(hasSetTextAction()).assertIsFocused()
    }
}
