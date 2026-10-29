package app.granger.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.granger.FakeLibrary
import app.granger.FakeReadingSessions
import app.granger.FakeZimArchive
import app.granger.core.library.ReadingTab
import app.granger.core.zim.ZimResolver
import app.granger.player.VoiceButton
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The wiki's bars (Plan U3c Decision 4): ←, the page's title, Listen and ⋯ (main page, Random article, Find in page)
 * above; Contents, Search, Aa and the bookmark below.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReaderTopBarTest {
    @get:Rule val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun bars(
        packTitle: String? = "Wikipedia",
        hasPack: Boolean = true,
        hasPage: Boolean = true,
        hasHome: Boolean = true,
        voice: VoiceButton = VoiceButton.IDLE,
        listen: Boolean = true,
    ) = compose.setContent {
        val actions = wikiBarActions(
            packTitle = packTitle, hasPack = hasPack, hasPage = hasPage, hasHome = hasHome, bookmarked = false,
            onBack = { calls += "back" }, onHome = { calls += "home" }, onRandom = { calls += "random" }, onFind = { calls += "find" },
            onSearchPack = { calls += "search" }, onContents = { calls += "contents" }, onTextSettings = { calls += "aa" },
            onToggleBookmark = { calls += "star" },
            onListen = if (listen) ({ calls += "listen" }) else null, voice = voice,
            onPauseResume = { calls += "pause" }, onStop = { calls += "stop" },
        )
        Column {
            ReaderTopBar("Photosynthesis", actions)
            ReaderBottomBar(actions)
        }
    }

    private fun SemanticsNodeInteraction.left() = getBoundsInRoot().left
    private fun SemanticsNodeInteraction.top() = getBoundsInRoot().top

    @Test fun theTopBarIsBackTitleListenMore() {
        bars()
        compose.onNodeWithText("Photosynthesis").assertExists()
        val lefts = listOf(
            compose.onNodeWithContentDescription("Back").left(),
            compose.onNodeWithTag(ReaderBarTags.TITLE).left(),
            compose.onNodeWithContentDescription("Listen").left(),
            compose.onNodeWithContentDescription("More").left(),
        )
        assertThat(lefts).isInStrictOrder()
        compose.onNodeWithContentDescription("Listen").performClick()
        assertThat(calls).containsExactly("listen")
        compose.onNodeWithContentDescription("Stop").assertDoesNotExist()   // Stop only while a session is live
    }

    @Test fun theBottomBarIsContentsSearchAaBookmark() {
        bars()
        val names = listOf("Contents", "Search Wikipedia", "Text settings", "Add bookmark")
        assertThat(names.map { compose.onNodeWithContentDescription(it).left() }).isInStrictOrder()
        // All four sit in the bottom bar, below the top one.
        val top = compose.onNodeWithTag(ReaderBarTags.TOP).getBoundsInRoot().bottom
        for (n in names) assertThat(compose.onNodeWithContentDescription(n).top()).isAtLeast(top)
        for (n in names) compose.onNodeWithContentDescription(n).performClick()
        assertThat(calls).containsExactly("contents", "search", "aa", "star").inOrder()
    }

    @Test fun whileThisPageIsReadStopSitsBesidePauseAndTheStarStays() {
        bars(voice = VoiceButton.READING)
        compose.onNodeWithContentDescription("Listen").assertDoesNotExist()
        compose.onNodeWithContentDescription("Pause").performClick()
        compose.onNodeWithContentDescription("Stop").performClick()
        assertThat(calls).containsExactly("pause", "stop").inOrder()
        assertThat(compose.onNodeWithContentDescription("Pause").left()).isLessThan(compose.onNodeWithContentDescription("Stop").left())
        assertThat(compose.onNodeWithContentDescription("Stop").left()).isLessThan(compose.onNodeWithContentDescription("More").left())
        // The U2 swap is gone: the star never leaves the bottom bar.
        compose.onNodeWithContentDescription("Add bookmark").assertExists()
    }

    @Test fun pausedShowsPlay() {
        bars(voice = VoiceButton.PAUSED)
        compose.onNodeWithContentDescription("Play").performClick()
        compose.onNodeWithContentDescription("Stop").assertExists()
        assertThat(calls).containsExactly("pause")
    }

    @Test fun withoutTheVoiceThereIsNoListen() {
        bars(listen = false)
        compose.onNodeWithContentDescription("Listen").assertDoesNotExist()
    }

    @Test fun moreHoldsTheMainPageRandomAndFind() {
        bars()
        compose.onNodeWithContentDescription("More").performClick()
        // The pack's main page, named for the pack: Home is the app's (the rail's owl).
        compose.onNodeWithText("Wikipedia: main page").performClick()
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Random article").performClick()
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Find in page").performClick()
        assertThat(calls).containsExactly("home", "random", "find").inOrder()
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Home").assertDoesNotExist()
        compose.onNodeWithText("Add bookmark").assertDoesNotExist()
        assertThat(mainPageLabel(null)).isEqualTo("Main page")
    }

    @Test fun theMainPageIsLeftOutWhenThePackHasNone() {
        bars(hasHome = false)
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Wikipedia: main page").assertDoesNotExist()
        compose.onNodeWithText("Random article").assertExists()
    }

    @Test fun searchWithoutAPackTitleSaysThisPack() {
        bars(packTitle = null)
        compose.onNodeWithContentDescription("Search this pack").assertExists()
    }

    @Test fun beforeThePageShowsOnlyBackSearchAndTheMenuWait() {
        bars(hasPage = false)
        compose.onNodeWithContentDescription("Search Wikipedia").assertExists()
        for (n in listOf("Listen", "Contents", "Text settings", "Add bookmark")) compose.onNodeWithContentDescription(n).assertDoesNotExist()
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Find in page").assertDoesNotExist()
    }

    @Test fun anErrorLeavesOnlyBack() {
        bars(hasPack = false, hasPage = false)
        compose.onNodeWithContentDescription("Back").assertExists()
        for (n in listOf("More", "Search Wikipedia", "Contents", "Listen")) compose.onNodeWithContentDescription(n).assertDoesNotExist()
    }

    @Test fun theBottomBarsSearchOpensThePackSearchWithFocus() {
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
        compose.onNodeWithContentDescription("Search Wikipedia").performClick()
        compose.waitForIdle()
        assertThat(viewModel.state.value.packSearch).isNotNull()
        compose.onNode(hasSetTextAction()).assertIsFocused()
    }
}
