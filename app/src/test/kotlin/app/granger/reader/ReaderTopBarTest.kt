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

/** The wiki reader's top bar: ←, Home, the pack's search box, the star and ⋯; no page title. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReaderTopBarTest {
    @get:Rule val compose = createComposeRule()

    private fun bar(packTitle: String? = "Wikipedia", hasHome: Boolean = true, voice: app.granger.player.VoiceButton = app.granger.player.VoiceButton.IDLE, onReadAloud: (() -> Unit)? = null) = compose.setContent {
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
        )
    }

    @Test fun theMenuOffersReadAloud() {
        var taps = 0
        bar(onReadAloud = { taps++ })
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Read aloud").performClick()
        assertThat(taps).isEqualTo(1)
    }

    @Test fun theMenuSaysStopReadingWhileTheVoiceReadsThisPage() {
        bar(voice = app.granger.player.VoiceButton.READING, onReadAloud = {})
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Stop reading").assertExists()
        compose.onNodeWithText("Pause reading").assertExists()
        compose.onNodeWithText("Read aloud").assertDoesNotExist()
    }

    @Test fun withoutTheVoiceTheMenuHasNoReadAloud() {
        bar()
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Read aloud").assertDoesNotExist()
    }

    private fun SemanticsNodeInteraction.left() = getBoundsInRoot().left

    @Test fun noPageTitleIsShown() {
        bar()
        compose.onNodeWithText("Photosynthesis").assertDoesNotExist()
        // TalkBack still hears which page the bar is over.
        compose.onNodeWithContentDescription("Photosynthesis").assertExists()
    }

    @Test fun barOrderIsBackHomeSearchStarMore() {
        bar()
        val lefts = listOf(
            compose.onNodeWithContentDescription("Back").left(),
            compose.onNodeWithContentDescription("Home").left(),
            compose.onNodeWithText("Search Wikipedia").left(),
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

    @Test fun homeHiddenWhenThePackHasNone() {
        bar(hasHome = false)
        compose.onNodeWithContentDescription("Home").assertDoesNotExist()
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
