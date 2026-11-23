package app.granger.search

import android.os.Looper
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import app.granger.FakeReadingSessions
import app.granger.core.library.LibrarySearch
import app.granger.core.library.SearchHit
import app.granger.core.library.TitleResults
import app.granger.core.research.BestMatches
import app.granger.core.research.Passage
import app.granger.core.research.SourceKind
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** Plan SQ: Best matches lead Search, each with its sentence, three first and up to ten on Show more. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SearchBestMatchesTest {
    @get:Rule val compose = createComposeRule()

    private object NoTitles : LibrarySearch {
        override suspend fun titles(query: String) = TitleResults(emptyList())
        override suspend fun fullText(query: String) = emptyList<SearchHit>()
    }

    private fun match(n: Int): BestMatches.Match {
        val text = "Intro sentence $n. Cool the burn with running water, case $n."
        val at = text.indexOf("Cool")
        return BestMatches.Match(
            Passage(SourceKind.ARTICLE, "wikimed", "Burn $n", packTitle = "WikiMed", location = "Burn_$n", text = text),
            at until text.length,
        )
    }

    @Test fun matchesLeadWithTheirSentenceAndOpenAtThePassage() {
        val opened = mutableListOf<BestMatches.Match>()
        val vm = SearchViewModel(
            NoTitles, FakeReadingSessions(), "treat a burn",
            best = BestMatchSearch { kotlinx.coroutines.flow.flowOf(BestMatches.Result((1..5).map(::match), listOf("treat", "burn"), byMeaning = true, final = true)) },
        )
        compose.setContent {
            SearchScreen(
                vm, onOpenTab = {}, onOpenBook = { _, _ -> }, onOpenAlbum = {}, onOpenArtist = {}, onOpenAudiobook = {},
                onPlaySong = {}, onBack = {}, onOpenMatch = { opened += it },
            )
        }
        // A seeded query skips the keystroke debounce; Best matches still wait their own pause.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SearchViewModel.BEST_PAUSE_MS + 100))
        compose.waitForIdle()
        compose.onNodeWithText("Best matches").assertExists()
        compose.onNodeWithText("“Cool the burn with running water, case 1.”").assertExists()
        compose.onNodeWithText("Burn 3").assertExists()
        compose.onNodeWithText("Burn 4").assertDoesNotExist()
        val list = compose.onNode(hasScrollAction())
        list.performScrollToNode(hasText("Show more matches"))
        compose.onNodeWithText("Show more matches").performClick()
        list.performScrollToNode(hasText("Burn 5"))
        list.performScrollToNode(hasText("Burn 2"))
        compose.onNodeWithText("Burn 2").performClick()
        assertThat(opened.single().passage.location).isEqualTo("Burn_2")
    }

    /** Plan I7 Task 7c: an album's and an audiobook's media cards, each with Listen's headphones and its place in words. */
    private fun showMediaCards() {
        val album = Passage(SourceKind.MEDIA, "album:holst|the planets", "The Planets", packTitle = "Your music", location = "", text = "Album: The Planets")
        val book = Passage(SourceKind.MEDIA, "librivox-1", "The Jungle Book", packTitle = "Your audiobooks", location = "", text = "Audiobook: The Jungle Book")
        val vm = SearchViewModel(
            NoTitles, FakeReadingSessions(), "planets",
            best = BestMatchSearch { kotlinx.coroutines.flow.flowOf(BestMatches.Result(listOf(BestMatches.Match(album, null), BestMatches.Match(book, null)), listOf("planets"), byMeaning = false, final = true)) },
        )
        compose.setContent {
            SearchScreen(vm, onOpenTab = {}, onOpenBook = { _, _ -> }, onOpenAlbum = {}, onOpenArtist = {}, onOpenAudiobook = {}, onPlaySong = {}, onBack = {})
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SearchViewModel.BEST_PAUSE_MS + 100))
        compose.waitForIdle()
    }

    @Test fun mediaCardsHaveTheHeadphonesAndSayWhereTheyAre() {
        showMediaCards()
        compose.onNodeWithText("Your music").assertExists()
        compose.onNodeWithText("Your audiobooks").assertExists()
        compose.onNodeWithTag(SearchTags.kind(SearchLines.MatchKind.MUSIC), useUnmergedTree = true).assertContentDescriptionEquals("Music")
        compose.onNodeWithTag(SearchTags.kind(SearchLines.MatchKind.AUDIOBOOK), useUnmergedTree = true).assertContentDescriptionEquals("Audiobook")
    }

    @Test @Config(qualifiers = "es") fun mediaCardsSayWhereTheyAreInSpanish() {
        showMediaCards()
        compose.onNodeWithText("Tu música").assertExists()
        compose.onNodeWithText("Tus audiolibros").assertExists()
        compose.onNodeWithText("Your music").assertDoesNotExist()
    }
}
