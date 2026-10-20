package app.granger.search

import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import app.granger.FakeReadingSessions
import app.granger.core.library.LibrarySearch
import app.granger.core.library.SearchHit
import app.granger.core.library.TitleResults
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** Each result group shows its first few rows, with Show all at its foot when there are more. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SearchShowAllTest {
    @get:Rule val compose = createComposeRule()

    private class Titles(val count: Int) : LibrarySearch {
        override suspend fun titles(query: String) = TitleResults((1..count).map { SearchHit("p", "Pack", "T$it", "Title $it") })
        override suspend fun fullText(query: String) = emptyList<SearchHit>()
    }

    private fun show(titles: Int): SearchViewModel {
        val vm = SearchViewModel(Titles(titles), FakeReadingSessions(), "title")
        compose.setContent {
            SearchScreen(
                vm, onOpenTab = {}, onOpenBook = { _, _ -> }, onOpenAlbum = {}, onOpenArtist = {}, onOpenAudiobook = {},
                onPlaySong = {}, onBack = {},
            )
        }
        compose.waitForIdle()
        return vm
    }

    private val list get() = compose.onNode(hasScrollAction())

    @Test fun aLongGroupShowsItsPreviewThenAllOfIt() {
        show(12)
        list.performScrollToNode(hasText("Show all 12 titles"))
        compose.onNodeWithText("Title 8").assertExists()
        compose.onNodeWithText("Title 9").assertDoesNotExist()
        compose.onNodeWithText("Show all 12 titles").performClick()
        list.performScrollToNode(hasText("Title 12"))
        list.performScrollToNode(hasText("Show fewer titles"))
        compose.onNodeWithText("Show fewer titles").performClick()
        list.performScrollToNode(hasText("Show all 12 titles"))
        compose.onNodeWithText("Title 9").assertDoesNotExist()
    }

    @Test fun aNewQueryCollapsesTheGroupAgain() {
        val vm = show(12)
        list.performScrollToNode(hasText("Show all 12 titles"))
        compose.onNodeWithText("Show all 12 titles").performClick()
        list.performScrollToNode(hasText("Title 12"))
        compose.runOnIdle { vm.onQueryChange("titles") }
        // The debounce runs on the main looper, whose clock Robolectric keeps.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SearchViewModel.DEBOUNCE_MS + 100))
        compose.waitForIdle()
        list.performScrollToNode(hasText("Show all 12 titles"))
        compose.onNodeWithText("Title 9").assertDoesNotExist()
    }

    @Test fun showFewerBringsTheGroupBackIntoView() {
        show(30)
        list.performScrollToNode(hasText("Show all 30 titles"))
        compose.onNodeWithText("Show all 30 titles").performClick()
        list.performScrollToNode(hasText("Show fewer titles"))
        compose.onNodeWithText("Show fewer titles").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Titles").assertIsDisplayed()
    }

    @Test fun aGroupWithinItsPreviewHasNoButton() {
        show(3)
        compose.onNodeWithText("Title 3").assertExists()
        compose.onNodeWithText("Show all", substring = true).assertDoesNotExist()
    }
}
