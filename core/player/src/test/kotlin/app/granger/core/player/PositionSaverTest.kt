package app.granger.core.player

import app.granger.core.player.PositionSaver.SaveReason
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PositionSaverTest {
    private var clock = 0L
    private val writes = mutableListOf<SaveReason>()
    private val saver = PositionSaver({ clock }) { writes += it }

    @Test fun savesEveryFiveSecondsWhilePlaying() = runTest {
        repeat(12) { clock += 1_000L; saver.onTick(5_000L) }
        assertThat(writes).containsExactly(SaveReason.TICK, SaveReason.TICK)
    }

    @Test fun everyEventWritesImmediately() = runTest {
        SaveReason.entries.filter { it != SaveReason.TICK }.forEach { clock += 100L; saver.onEvent(it) }
        assertThat(writes).containsExactly(
            SaveReason.PAUSE, SaveReason.SEEK, SaveReason.CHAPTER, SaveReason.TRACK, SaveReason.STOP,
        ).inOrder()
    }

    @Test fun anEventRestartsTheTickClock() = runTest {
        repeat(4) { clock += 1_000L; saver.onTick(5_000L) }
        saver.onEvent(SaveReason.SEEK)                                // at 4 s
        repeat(4) { clock += 1_000L; saver.onTick(5_000L) }           // 8 s: only 4 s since the seek
        assertThat(writes).containsExactly(SaveReason.SEEK)
        clock += 1_000L; saver.onTick(5_000L)                         // 9 s: 5 s since the seek
        assertThat(writes).containsExactly(SaveReason.SEEK, SaveReason.TICK).inOrder()
    }
}
