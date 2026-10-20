package app.granger.widget

import app.granger.core.player.PlayerMode
import app.granger.core.player.SavedModes
import app.granger.core.player.SavedMusic
import app.granger.player.FakePlayerHub
import app.granger.player.audiobook
import app.granger.player.bookUi
import app.granger.player.chapter
import app.granger.player.musicUi
import app.granger.player.track
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class WidgetActionsTest {
    @Test fun playWhenNothingLoadedResumesTheSavedPlayer() = runTest {
        val hub = FakePlayerHub()
        hub.saved.value = SavedModes(SavedMusic(listOf(track("a")), 0, 0L, false, 0), null, PlayerMode.MUSIC)
        playFromWidget(hub)
        assertThat(hub.calls).containsExactly("activate:MUSIC")
    }

    @Test fun playWhileLoadedToggles() = runTest {
        val hub = FakePlayerHub(musicUi())
        playFromWidget(hub)
        assertThat(hub.calls).containsExactly("playPause")
    }

    @Test fun secondaryIsNextForMusicAndBackForBooks() {
        val hub = FakePlayerHub(musicUi())
        secondaryFromWidget(hub)
        val b = audiobook("jb")
        hub.ui.value = bookUi(b, chapter(b, 0, 1_000L), chapterCount = 1)
        secondaryFromWidget(hub)
        assertThat(hub.calls).containsExactly("next", "skipBack").inOrder()
    }
}
