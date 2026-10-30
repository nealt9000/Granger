package app.granger.player

import app.granger.MainDispatcherRule
import app.granger.core.player.NowPlaying
import app.granger.core.player.PlayerUi
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

class PlayerFocusTest {
    @get:Rule val main = MainDispatcherRule()

    /** The voice ([reading] or paused) with Song A under it ([musicPlaying] or paused), 20 s into the song. */
    private fun mixed(reading: Boolean, musicPlaying: Boolean): PlayerUi {
        val voice = voiceUi(playing = reading)
        val music = musicUi().now as NowPlaying.Music
        return voice.copy(
            now = (voice.now as NowPlaying.Voice).copy(
                music = music, musicPlaying = musicPlaying, musicPositionMs = 20_000L, musicDurationMs = music.track.durationMs,
            ),
        )
    }

    @Test fun theSidePlayingIsShown() {
        assertThat(mixed(reading = false, musicPlaying = true).showsMusicUnder(prefersMusic = false)).isTrue()
        assertThat(mixed(reading = true, musicPlaying = false).showsMusicUnder(prefersMusic = true)).isFalse()
        // Both playing, or both paused: the side the full player last showed.
        assertThat(mixed(reading = true, musicPlaying = true).showsMusicUnder(prefersMusic = false)).isFalse()
        assertThat(mixed(reading = true, musicPlaying = true).showsMusicUnder(prefersMusic = true)).isTrue()
        assertThat(mixed(reading = false, musicPlaying = false).showsMusicUnder(prefersMusic = true)).isTrue()
        // Nothing under the voice: always the voice.
        assertThat(voiceUi(playing = false).showsMusicUnder(prefersMusic = true)).isFalse()
        assertThat(musicUi().showsMusicUnder(prefersMusic = true)).isFalse()
    }

    @Test fun theMusicSideIsTheMusicAsItsOwnPlayer() {
        val shown = mixed(reading = false, musicPlaying = true).oneSide(prefersMusic = false)
        assertThat((shown.now as NowPlaying.Music).track.title).isEqualTo("Song A")
        assertThat(shown.playing).isTrue()
        assertThat(shown.positionMs).isEqualTo(20_000L)
    }

    @Test fun theMiniPlayerShowsAndDrivesThePlayingMusicNotThePausedBook() {
        val hub = FakePlayerHub(mixed(reading = false, musicPlaying = true))
        val vm = PlayerViewModel(hub)
        assertThat(vm.mini.value.now).isInstanceOf(NowPlaying.Music::class.java)
        vm.miniPlayPause()
        vm.secondary()
        assertThat(hub.calls).containsExactly("playPauseMusic", "nextMusic").inOrder()

        // Paused from the mini-player, the music stays shown though both are paused now.
        hub.ui.value = mixed(reading = false, musicPlaying = false)
        idle()
        assertThat(vm.mini.value.now).isInstanceOf(NowPlaying.Music::class.java)
    }

    @Test fun withBothPlayingTheMiniPlayerShowsTheTabTheFullPlayerLastShowed() {
        val focus = PlayerFocus()
        val hub = FakePlayerHub(mixed(reading = true, musicPlaying = true))
        val vm = PlayerViewModel(hub, focus = focus)
        assertThat(vm.mini.value.now).isInstanceOf(NowPlaying.Voice::class.java)
        vm.shownTab(ListenTab.MEDIA)
        idle()
        assertThat(vm.mini.value.now).isInstanceOf(NowPlaying.Music::class.java)
        vm.resetTab()                                       // the full player opens on the side the mini-player shows
        assertThat(vm.tab()).isEqualTo(ListenTab.MEDIA)
        vm.shownTab(ListenTab.READER)
        idle()
        assertThat(vm.mini.value.now).isInstanceOf(NowPlaying.Voice::class.java)
        vm.resetTab()
        assertThat(vm.tab()).isEqualTo(ListenTab.READER)
    }

    @Test fun thePausedBookWithMusicPlayingOpensThePlayerOnMusic() {
        val vm = PlayerViewModel(FakePlayerHub(mixed(reading = false, musicPlaying = true)))
        vm.resetTab()
        assertThat(vm.tab()).isEqualTo(ListenTab.MEDIA)
    }

    @Test fun listensCardShowsThePlayingMusic() {
        val card = nowPlayingCardFor(mixed(reading = false, musicPlaying = true))
        assertThat(card?.kind).isEqualTo(app.granger.listen.CardKind.MUSIC)
        assertThat(card?.label).isEqualTo("Now playing")
    }

    private fun idle() = main.dispatcher.scheduler.advanceUntilIdle()

    private fun nowPlayingCardFor(ui: PlayerUi) =
        app.granger.listen.nowPlayingCard(ui.oneSide(prefersMusic = false), app.granger.core.player.SavedModes(null, null, null))
}
