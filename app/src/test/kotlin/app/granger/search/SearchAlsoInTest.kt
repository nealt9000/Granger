package app.granger.search

import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import app.granger.FakeReadingSessions
import app.granger.core.library.LibrarySearch
import app.granger.core.library.SearchHit
import app.granger.core.library.TitleResults
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Plan SQ: one row per title, the same title in other packs named under it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SearchAlsoInTest {
    @get:Rule val compose = createComposeRule()

    private val voyage = SearchHit("wv", "Wikivoyage (ES)", "Turingia", "Turingia")

    private val search = object : LibrarySearch {
        override suspend fun titles(query: String) =
            TitleResults(listOf(SearchHit("wp", "Wikipedia (ES)", "Turingia", "Turingia", also = listOf(voyage))), ranked = 1)
        override suspend fun fullText(query: String) = emptyList<SearchHit>()
    }

    @Test fun aTitleRowNamesTheOtherPacksThatHaveIt() {
        val vm = SearchViewModel(search, FakeReadingSessions(), "turingia")
        compose.setContent {
            SearchScreen(
                vm, onOpenTab = {}, onOpenBook = { _, _ -> }, onOpenAlbum = {}, onOpenArtist = {}, onOpenAudiobook = {},
                onPlaySong = {}, onBack = {},
            )
        }
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
        compose.onNodeWithText("Wikipedia (ES)").assertExists()
        compose.onNodeWithTag(SearchTags.ALSO_IN, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Also in Wikivoyage (ES)", useUnmergedTree = true).assertExists()
        // TalkBack reaches the other copy as an action of the row.
        val actions = compose.onNodeWithText("Turingia").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertThat(actions.map { it.label }).containsExactly("Open in Wikivoyage (ES)")
    }
}
