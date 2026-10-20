package app.granger.widget

import app.granger.player.FakePlayerHub
import app.granger.player.audiobook
import app.granger.player.bookUi
import app.granger.player.chapter
import app.granger.player.musicUi
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetUpdaterTest {
    private val hub = FakePlayerHub()
    private val renders = mutableListOf<WidgetModel>()

    private fun TestScope.start() {
        WidgetUpdater(hub, backgroundScope) { renders += it }
        runCurrent()
    }

    @Test fun positionTicksDoNotUpdateTheWidget() = runTest {
        hub.ui.value = musicUi(positionMs = 1_000L)
        start()
        assertThat(renders).hasSize(1)
        hub.ui.value = musicUi(positionMs = 2_000L); runCurrent()
        hub.ui.value = musicUi(positionMs = 3_000L).copy(sleepRemainingMs = 60_000L); runCurrent()
        assertThat(renders).hasSize(1)

        hub.ui.value = musicUi(positionMs = 4_000L).copy(playing = false); runCurrent()
        assertThat(renders).hasSize(2)
        assertThat(renders.last().playing).isFalse()
        // Progress is drawn as of that render.
        assertThat(renders.last().progress).isWithin(1e-6f).of(4_000f / 200_000f)
    }

    @Test fun switchingPlayersUpdatesTheWidget() = runTest {
        hub.ui.value = musicUi()
        start()
        val b = audiobook("jb", "The Jungle Book")
        hub.ui.value = bookUi(b, chapter(b, 0, 100_000L, title = "One"), chapterCount = 2); runCurrent()
        assertThat(renders.map { it.secondary }).containsExactly(Secondary.NEXT, Secondary.BACK).inOrder()
        // A chapter change is a change too.
        hub.ui.value = bookUi(b, chapter(b, 1, 100_000L, title = "Two"), chapterCount = 2); runCurrent()
        assertThat(renders).hasSize(3)
        assertThat(renders.last().title).isEqualTo("Two")
    }
}
