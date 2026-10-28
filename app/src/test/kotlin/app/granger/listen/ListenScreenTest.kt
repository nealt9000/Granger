package app.granger.listen

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.granger.core.library.CollectionRow
import app.granger.core.library.ShelfCollection
import app.granger.library.KindRow
import app.granger.player.audiobook
import app.granger.sources.GetMoreLink
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h1000dp")
class ListenScreenTest {
    @get:Rule val compose = createComposeRule()
    private val calls = mutableListOf<String>()

    private val actions = ListenActions(
        onOpenPlayer = { calls += "player" },
        onPlayPause = { calls += "playPause" },
        onOpenMusic = { calls += "music:$it" },
        onOpenAudiobooks = { calls += "audiobooks:$it" },
        onOpenCollection = { calls += "collection:$it" },
        onOpenSource = { calls += "source:$it" },
        onFindOnPhone = { calls += "phone" },
        onGetMore = { calls += "more" },
    )

    private fun row(type: String, name: String) = CollectionRow(
        ShelfCollection("c:$name", name, type, userCreated = false, defaultKey = name), emptyList(), audiobooks = listOf(audiobook(name)),
    )

    @Test fun withNoAudioItOffersTheSourcesAndThePhone() {
        compose.setContent { ListenContent(ListenUi(nowPlaying = null, shelves = emptyList()), actions) }
        compose.onNodeWithText("Nothing to listen to yet").assertExists()
        for (link in AUDIO_SOURCES) compose.onNodeWithText(link.title).performClick()
        compose.onNodeWithText(FIND_ON_PHONE).performClick()
        assertThat(calls).containsExactly(
            "source:${GetMoreLink.LIBRIVOX}", "source:${GetMoreLink.ARCHIVE_ORG_AUDIOBOOKS}", "source:${GetMoreLink.ARCHIVE_ORG_MUSIC}", "phone",
        ).inOrder()
        compose.onNodeWithTag(NOW_PLAYING_TAG).assertDoesNotExist()
    }

    @Test fun theShelvesHaveTheLibrarysRows() {
        val ui = ListenUi(
            nowPlaying = null,
            shelves = listOf(
                KindRow("music", "Music", "music", listOf(row("music", "Phone"))),
                KindRow("audiobooks", "Audiobooks", "audiobooks", listOf(row("audiobooks", "LibriVox"))),
            ),
        )
        compose.setContent { ListenContent(ui, actions) }
        compose.onNodeWithText("Nothing to listen to yet").assertDoesNotExist()
        compose.onNodeWithText("All music").performClick()
        compose.onNodeWithText("Phone").performClick()
        compose.onNodeWithText("All audiobooks").performClick()
        compose.onNodeWithText("LibriVox").performClick()
        // A shelf collapses, as in the Library.
        compose.onNodeWithText("Music").performClick()
        compose.onNodeWithText("All music").assertDoesNotExist()
        assertThat(calls).containsExactly("music:null", "music:c:Phone", "audiobooks:null", "audiobooks:c:LibriVox").inOrder()
    }

    @Test fun theNowPlayingCardOpensThePlayerAndPlays() {
        val card = NowPlayingCard("Now playing", "Blue in Green", "Miles Davis", null, CardKind.MUSIC, live = true, playing = true)
        val music = KindRow("music", "Music", "music", listOf(row("music", "Phone")))
        compose.setContent { ListenContent(ListenUi(card, shelves = listOf(music)), actions) }
        compose.onNodeWithText("Now playing").assertExists()
        compose.onNodeWithText("Blue in Green").performClick()
        compose.onNodeWithContentDescription("Pause").performClick()
        // The header: Get more, and the phone's own music in the overflow.
        compose.onNodeWithContentDescription("Get more").performClick()
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText(FIND_ON_PHONE).performClick()
        assertThat(calls).containsExactly("player", "playPause", "more", "phone").inOrder()
    }
}
