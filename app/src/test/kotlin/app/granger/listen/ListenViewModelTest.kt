package app.granger.listen

import app.granger.FakeShelves
import app.granger.MainDispatcherRule
import app.granger.core.library.CollectionRow
import app.granger.core.library.MediaType
import app.granger.core.library.Shelf
import app.granger.core.library.ShelfCollection
import app.granger.core.player.SavedModes
import app.granger.core.player.SavedMusic
import app.granger.core.player.SavedVoice
import app.granger.core.speech.ReadAddress
import app.granger.core.player.PlayerMode
import app.granger.pack
import app.granger.player.FakePlayerHub
import app.granger.player.audiobook
import app.granger.player.bookUi
import app.granger.player.chapter
import app.granger.player.musicUi
import app.granger.player.track
import app.granger.player.voiceUi
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ListenViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val shelves = FakeShelves()
    private val hub = FakePlayerHub()

    private fun vm() = ListenViewModel(shelves, hub)

    private fun shelf(type: String, vararg names: String) = Shelf(
        MediaType(type.lowercase(), type, builtIn = true, sortOrder = 0),
        names.map { CollectionRow(ShelfCollection("c:$it", it, type.lowercase(), userCreated = false, defaultKey = it), emptyList(), audiobooks = listOf(audiobook(it))) },
    )

    @Test fun theShelvesAreMusicThenAudiobooksAndOnlyAudio() = runTest(main.dispatcher) {
        shelves.shelfList.value = listOf(shelf("Literature", "Wikipedia"), shelf("Audiobooks", "LibriVox"), shelf("Music", "Phone", "Archive.org"))
        val ui = vm().ui.filterNotNull().first()
        assertThat(ui.shelves.map { it.title }).containsExactly("Music", "Audiobooks").inOrder()
        assertThat(ui.shelves[0].collections.map { it.collection.name }).containsExactly("Phone", "Archive.org").inOrder()
        assertThat(ui.empty).isFalse()
    }

    @Test fun aShelfShowsOnlyWhenItHoldsSomething() = runTest(main.dispatcher) {
        shelves.shelfList.value = listOf(shelf("Music", "Phone"), shelf("Audiobooks"))
        assertThat(vm().ui.filterNotNull().first().shelves.map { it.id }).containsExactly("music")
    }

    @Test fun withNoAudioListenIsEmptyAndHasNoCard() = runTest(main.dispatcher) {
        shelves.shelfList.value = listOf(shelf("Literature", "Wikipedia"), shelf("Maps", "OpenStreetMap"))
        val ui = vm().ui.filterNotNull().first()
        assertThat(ui.empty).isTrue()
        assertThat(ui.shelves).isEmpty()
        assertThat(ui.nowPlaying).isNull()
        // The empty state's sources are Get more's audio rows.
        assertThat(AUDIO_SOURCES.map { it.title }).containsExactly("LibriVox", "Archive.org audiobooks", "Archive.org music").inOrder()
    }

    @Test fun nowPlayingFollowsThePlayer() = runTest(main.dispatcher) {
        val vm = vm()
        hub.ui.value = musicUi(track("a", "Blue in Green", artists = "Miles Davis"))
        val music = vm.ui.filterNotNull().first { it.nowPlaying != null }.nowPlaying!!
        assertThat(music).isEqualTo(NowPlayingCard("Now playing", "Blue in Green", "Miles Davis", null, CardKind.MUSIC, live = true, playing = true))
        // Only the position changing doesn't make a new card.
        hub.ui.value = musicUi(track("a", "Blue in Green", artists = "Miles Davis"), positionMs = 90_000L)
        assertThat(vm.ui.value!!.nowPlaying).isSameInstanceAs(music)
        hub.ui.value = voiceUi("Peter Pan", section = "Chapter 3", playing = false)
        val voice = vm.ui.filterNotNull().first { it.nowPlaying?.kind == CardKind.VOICE }.nowPlaying!!
        assertThat(voice.label).isEqualTo("Paused")
        assertThat(voice.subtitle).isEqualTo("Reading · Chapter 3")
    }

    @Test fun theCardSaysWhatEachKindIs() {
        val idle = SavedModes(null, null, null)
        val book = audiobook("t", title = "Treasure Island")
        val reading = nowPlayingCard(voiceUi("Peter Pan"), idle)!!
        assertThat(reading.label).isEqualTo("Reading aloud")
        val listening = nowPlayingCard(bookUi(book, chapter(book, 2, 60_000L), chapterCount = 5).copy(playing = true), idle)!!
        assertThat(listening.kind).isEqualTo(CardKind.AUDIOBOOK)
        assertThat(listening.title).isEqualTo("Chapter 3")
        assertThat(listening.subtitle).isEqualTo("Treasure Island")
        assertThat(listening.label).isEqualTo("Now playing")
    }

    @Test fun withNothingLoadedTheCardIsTheLastPlayed() {
        assertThat(nowPlayingCard(app.granger.core.player.PlayerUi.IDLE, SavedModes(null, null, null))).isNull()
        val saved = SavedModes(SavedMusic(listOf(track("a", "So What")), 0, 10_000L, false, 0), null, PlayerMode.MUSIC)
        val last = nowPlayingCard(app.granger.core.player.PlayerUi.IDLE, saved)!!
        assertThat(last.label).isEqualTo("Last played")
        assertThat(last.title).isEqualTo("So What")
        assertThat(last.live).isFalse()
        assertThat(last.playing).isFalse()
        val voice = SavedVoice("book:pp", "Peter Pan", "Chapter 3", ReadAddress.Text("k", 0, 0), 1f, null)
        val read = nowPlayingCard(app.granger.core.player.PlayerUi.IDLE, saved.copy(voice = voice, lastWasVoice = true, lastMode = null))!!
        assertThat(read.kind).isEqualTo(CardKind.VOICE)
        assertThat(read.label).isEqualTo("Last read")
        assertThat(read.title).isEqualTo("Peter Pan")
    }
}
