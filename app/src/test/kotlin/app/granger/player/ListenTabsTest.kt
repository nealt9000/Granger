package app.granger.player

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.granger.AudiobookShelfRoute
import app.granger.LibraryRoute
import app.granger.ListenPlayerRoute
import app.granger.MusicRoute
import app.granger.openShelfFromPlayer
import app.granger.togglePlayer
import app.granger.core.player.NowPlaying
import app.granger.core.player.PlayerMode
import app.granger.core.player.PlayerUi
import app.granger.core.player.SavedBook
import app.granger.core.player.SavedModes
import app.granger.core.player.SavedMusic
import app.granger.core.player.SavedVoice
import app.granger.core.speech.ReadAddress
import app.granger.theme.GrangerTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The player's two tabs (Plan U2 Decision 4): Reader, and the media tab titled Music or Audiobook after the live mode,
 * else the last one played, else Music. It opens on what's live; the media tab's inactive mode shows its saved state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h1600dp")
class ListenTabsTest {
    @get:Rule val compose = createComposeRule()

    private val book = audiobook("b", title = "The Hobbit")
    private val chapters = listOf(chapter(book, 0, 60_000L, "An Unexpected Party"), chapter(book, 1, 120_000L, "Roast Mutton"))
    private val savedBook = SavedBook(book, chapters, chapters[1], inChapterMs = 65_000L, speed = 1.2f, skipSilence = false, volumeBoostMb = 0, finished = false)
    private val songs = listOf(track("a", "Song A"), track("b", "Song B", album = "Second Album"), track("c", "Song C"))
    private val savedMusic = SavedMusic(songs, index = 1, positionMs = 42_000L, shuffle = true, repeat = 2)
    private val savedVoice = SavedVoice("book:pan", "Peter Pan", "Chapter 3", ReadAddress.Text("book:pan", 4, 0), 1f, null)

    private val shelves = mutableListOf<PlayerMode>()

    private fun show(hub: FakePlayerHub, audiobooks: Boolean = true): PlayerViewModel {
        val vm = PlayerViewModel(hub, audiobooks = flowOf(audiobooks))
        compose.setContent { GrangerTheme { ListenPlayerScreen(vm, onClose = {}, onShelf = { shelves += it }) } }
        return vm
    }

    private fun mediaTitle(title: String) = compose.onNodeWithTag(ListenTabTags.MEDIA).assertTextEquals(title)

    @Test fun theMediaTabIsTitledAfterTheLiveModeThenTheLastThenMusic() {
        val idle = NowPlaying.Idle
        val voice = voiceUi().now
        val under = (voice as NowPlaying.Voice).copy(music = musicUi().now as NowPlaying.Music)
        // Live.
        assertThat(mediaMode(musicUi().now, SavedModes(null, null, PlayerMode.AUDIOBOOK))).isEqualTo(PlayerMode.MUSIC)
        assertThat(mediaMode(bookUi(book, chapters[0], 2).now, SavedModes(null, null, PlayerMode.MUSIC))).isEqualTo(PlayerMode.AUDIOBOOK)
        assertThat(mediaMode(under, SavedModes(null, null, null, lastMedia = PlayerMode.AUDIOBOOK))).isEqualTo(PlayerMode.MUSIC)
        // The last played, kept while the voice reads.
        assertThat(mediaMode(voice, SavedModes(null, null, null, lastWasVoice = true, lastMedia = PlayerMode.AUDIOBOOK))).isEqualTo(PlayerMode.AUDIOBOOK)
        assertThat(mediaMode(idle, SavedModes(null, null, PlayerMode.AUDIOBOOK))).isEqualTo(PlayerMode.AUDIOBOOK)
        // Neither ever played.
        assertThat(mediaMode(idle, SavedModes(null, null, null))).isEqualTo(PlayerMode.MUSIC)
        assertThat(mediaMode(voice, SavedModes.UNKNOWN)).isEqualTo(PlayerMode.MUSIC)
        // Plan U2 Decision 7: without an audiobook on the phone the last one played doesn't name the tab.
        assertThat(mediaMode(idle, SavedModes(null, null, PlayerMode.AUDIOBOOK), audiobooks = false)).isEqualTo(PlayerMode.MUSIC)
        assertThat(mediaMode(voice, SavedModes(null, null, null, lastWasVoice = true, lastMedia = PlayerMode.AUDIOBOOK), audiobooks = false))
            .isEqualTo(PlayerMode.MUSIC)
        assertThat(tabTitle(ListenTab.MEDIA, PlayerMode.AUDIOBOOK)).isEqualTo("Audiobook")
        assertThat(tabTitle(ListenTab.READER, PlayerMode.AUDIOBOOK)).isEqualTo("Reader")
    }

    @Test fun theOpeningTabFollowsWhatsLiveThenTheLastUsed() {
        val none = SavedModes(null, null, null)
        assertThat(listenTab(null, voiceUi().now, none)).isEqualTo(ListenTab.READER)
        assertThat(listenTab(null, musicUi().now, SavedModes(null, null, null, lastWasVoice = true))).isEqualTo(ListenTab.MEDIA)
        assertThat(listenTab(null, bookUi(book, chapters[0], 2).now, none)).isEqualTo(ListenTab.MEDIA)
        assertThat(listenTab(null, NowPlaying.Idle, SavedModes(null, null, null, lastWasVoice = true))).isEqualTo(ListenTab.READER)
        assertThat(listenTab(null, NowPlaying.Idle, SavedModes(null, null, PlayerMode.AUDIOBOOK))).isEqualTo(ListenTab.MEDIA)
        assertThat(listenTab(null, NowPlaying.Idle, none)).isEqualTo(ListenTab.MEDIA)
        // Picked wins over all of them.
        assertThat(listenTab(ListenTab.MEDIA, voiceUi().now, none)).isEqualTo(ListenTab.MEDIA)
    }

    @Test fun withoutAnAudiobookTheMediaTabIsMusic() {
        val hub = FakePlayerHub().apply { saved.value = SavedModes(savedMusic, savedBook, PlayerMode.AUDIOBOOK) }
        val vm = show(hub, audiobooks = false)
        mediaTitle("Music")
        assertThat(vm.mediaMode()).isEqualTo(PlayerMode.MUSIC)
        compose.onNodeWithText("Song B").assertExists()             // the saved queue, paused
    }

    @Test fun twoTabsAndSwitchingCallsNothing() {
        val hub = FakePlayerHub(musicUi()).apply { saved.value = SavedModes(savedMusic, savedBook, PlayerMode.MUSIC, voice = savedVoice) }
        val vm = show(hub)
        compose.onNodeWithTag(ListenTabTags.MEDIA).assertIsSelected()
        mediaTitle("Music")
        compose.onNodeWithText("Song A").assertExists()          // the active mode, live

        compose.onNodeWithTag(ListenTabTags.READER).assertTextEquals("Reader").performClick()
        compose.onNodeWithTag(ReaderTags.RESUME).assertTextEquals("Resume Peter Pan · Chapter 3")
        assertThat(vm.tab()).isEqualTo(ListenTab.READER)

        compose.onNodeWithTag(ListenTabTags.MEDIA).performClick()
        compose.onNodeWithText("Song A").assertExists()
        assertThat(hub.calls).isEmpty()
    }

    @Test fun theVoiceOpensOnReaderAndTheMediaTabIsTheLastPlayedPaused() {
        val hub = FakePlayerHub(voiceUi(title = "Hamlet")).apply {
            saved.value = SavedModes(savedMusic, savedBook, null, lastWasVoice = true, lastMedia = PlayerMode.AUDIOBOOK)
        }
        show(hub)
        compose.onNodeWithTag(ListenTabTags.READER).assertIsSelected()
        compose.onNodeWithTag(ReaderTags.SENTENCE).assertTextEquals("It was found in 1799.")
        mediaTitle("Audiobook")

        compose.onNodeWithTag(ListenTabTags.MEDIA).performClick()
        compose.onNodeWithText("The Hobbit").assertExists()
        compose.onNodeWithText("Roast Mutton · 2 of 2").assertExists()
        compose.onNodeWithText("1:05").assertExists()              // its saved position in the chapter
        compose.onNodeWithText("Book: 2:05 of 3:00 · 69%").assertExists()
        assertThat(hub.calls).isEmpty()

        // A control on the paused book: switch first, then do it.
        compose.onNodeWithContentDescription("Play").performClick()
        compose.onNodeWithContentDescription("Previous chapter").performClick()
        assertThat(hub.calls).containsExactly("activate:AUDIOBOOK", "activate:AUDIOBOOK", "seekToChapter:0").inOrder()
    }

    @Test fun musicUnderTheVoiceShowsOnTheMusicTabWithItsOwnControls() {
        val under = NowPlaying.Music(songs[1], 1, 3, false, 0)
        val voice = voiceUi(title = "Hamlet").let {
            it.copy(now = (it.now as NowPlaying.Voice).copy(music = under, musicPlaying = true, musicPositionMs = 42_000L, musicDurationMs = 180_000L))
        }
        val hub = FakePlayerHub(voice).apply { saved.value = SavedModes(savedMusic, savedBook, null, lastWasVoice = true, lastMedia = PlayerMode.AUDIOBOOK) }
        show(hub)
        mediaTitle("Music")                                                  // the music under the voice is live
        compose.onNodeWithTag(ListenTabTags.MEDIA).performClick()
        compose.onNodeWithText("Song B").assertExists()
        compose.onNodeWithText("Under the voice · 2 of 3").assertExists()
        compose.onNodeWithText("0:42").assertExists()
        compose.onNodeWithContentDescription("Pause").performClick()        // it is playing: the button says so and pauses the music, not the voice
        compose.onNodeWithContentDescription("Next track").performClick()
        assertThat(hub.calls).containsExactly("playPauseMusic", "nextMusic").inOrder()
        // The voice stops: the tab shows the saved queue again, paused.
        hub.ui.value = PlayerUi.IDLE
        hub.saved.value = SavedModes(savedMusic, savedBook, PlayerMode.MUSIC)
        compose.waitForIdle()
        compose.onNodeWithText("Paused · 2 of 3").assertExists()
    }

    @Test fun inactiveMusicShowsTheSavedQueueAndActivatesOnPlay() {
        val hub = FakePlayerHub().apply { saved.value = SavedModes(savedMusic, savedBook, PlayerMode.MUSIC) }
        show(hub)
        mediaTitle("Music")
        compose.onNodeWithText("Song B").assertExists()
        compose.onNodeWithText("Second Album").assertExists()
        compose.onNodeWithText("0:42").assertExists()
        compose.onNodeWithText("Paused · 2 of 3").assertExists()
        compose.onNodeWithContentDescription("Shuffle on").assertExists()
        compose.onNodeWithContentDescription("Repeat one").assertExists()
        compose.onNodeWithContentDescription("Shuffle on").performClick()   // shows the state only
        assertThat(hub.calls).isEmpty()

        compose.onNodeWithContentDescription("Next track").performClick()
        assertThat(hub.calls).containsExactly("activate:MUSIC", "next").inOrder()
    }

    @Test fun nothingEverPlayedSaysSoOnMusic() {
        val hub = FakePlayerHub().apply { saved.value = SavedModes(null, null, null) }
        show(hub)
        mediaTitle("Music")
        compose.onNodeWithText("Nothing played yet").assertExists()
        compose.onNodeWithText("Music shelf").performClick()
        assertThat(shelves).containsExactly(PlayerMode.MUSIC)
        assertThat(hub.calls).isEmpty()
    }

    @Test fun idleOpensOnTheLastUsed() {
        val hub = FakePlayerHub().apply { saved.value = SavedModes(savedMusic, savedBook, PlayerMode.AUDIOBOOK) }
        show(hub)
        mediaTitle("Audiobook")
        compose.onNodeWithText("The Hobbit").assertExists()
        compose.onNodeWithText("Audiobook · paused").assertExists()
        compose.onNodeWithContentDescription("Play").performClick()
        assertThat(hub.calls).containsExactly("activate:AUDIOBOOK")
    }

    @Test fun idleAfterTheVoiceOpensOnReaderWithResume() {
        val hub = FakePlayerHub().apply { saved.value = SavedModes(savedMusic, null, null, voice = savedVoice, lastWasVoice = true, lastMedia = PlayerMode.MUSIC) }
        show(hub)
        compose.onNodeWithTag(ListenTabTags.READER).assertIsSelected()
        compose.onNodeWithTag(ReaderTags.RESUME).assertExists()
        compose.onNodeWithText("Read aloud").assertExists()            // the bar's label
        mediaTitle("Music")
    }

    @Test fun eachMediaTabLinksToItsShelfLiveAndInactive() {
        val hub = FakePlayerHub(musicUi()).apply { saved.value = SavedModes(null, savedBook, PlayerMode.MUSIC) }
        show(hub)
        compose.onNodeWithText("Music shelf").performClick()              // live music
        hub.ui.value = PlayerUi.IDLE
        hub.saved.value = SavedModes(null, savedBook, PlayerMode.AUDIOBOOK)
        compose.onNodeWithText("Audiobooks shelf").performClick()         // the saved book
        hub.ui.value = bookUi(book, chapters[0], chapterCount = 2)
        compose.onNodeWithText("Audiobooks shelf").performClick()         // live book
        assertThat(shelves).containsExactly(PlayerMode.MUSIC, PlayerMode.AUDIOBOOK, PlayerMode.AUDIOBOOK).inOrder()
        assertThat(hub.calls).isEmpty()
    }

    @Test fun shelfLinkClosesThePlayerSoBackReturnsToWhereItWasOpened() {
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = LibraryRoute()) {
                composable<LibraryRoute> {}
                composable<MusicRoute> {}
                composable<AudiobookShelfRoute> {}
                composable<ListenPlayerRoute> {}
            }
        }
        compose.runOnIdle { nav.togglePlayer() }
        compose.runOnIdle { nav.openShelfFromPlayer(PlayerMode.MUSIC) }
        compose.runOnIdle {
            assertThat(nav.currentBackStackEntry!!.toRoute<MusicRoute>().collectionId).isNull()
            assertThat(nav.previousBackStackEntry?.destination?.hasRoute<LibraryRoute>()).isTrue()
        }
        // Opened from All music: the link just closes the player, without stacking a second shelf.
        compose.runOnIdle { nav.togglePlayer() }
        compose.runOnIdle { nav.openShelfFromPlayer(PlayerMode.MUSIC) }
        compose.runOnIdle {
            assertThat(nav.currentDestination?.hasRoute<MusicRoute>()).isTrue()
            assertThat(nav.previousBackStackEntry?.destination?.hasRoute<LibraryRoute>()).isTrue()
        }
        compose.runOnIdle { nav.togglePlayer() }
        compose.runOnIdle { nav.openShelfFromPlayer(PlayerMode.AUDIOBOOK) }
        compose.runOnIdle {
            assertThat(nav.currentBackStackEntry!!.toRoute<AudiobookShelfRoute>().collectionId).isNull()
            assertThat(nav.previousBackStackEntry?.destination?.hasRoute<MusicRoute>()).isTrue()
            assertThat(nav.currentBackStack.value.none { it.destination.hasRoute<ListenPlayerRoute>() }).isTrue()
        }
    }
}
