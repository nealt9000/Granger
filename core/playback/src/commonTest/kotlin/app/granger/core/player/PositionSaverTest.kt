package app.granger.core.player

import app.granger.core.player.PositionSaver.SaveReason
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PositionSaverTest {
    private var clock = 0L
    private val writes = mutableListOf<SaveReason>()
    private val saver = PositionSaver({ clock }) { writes += it }

    @Test fun savesEveryFiveSecondsWhilePlaying() = runTest {
        repeat(12) { clock += 1_000L; saver.onTick(5_000L) }
        assertEquals(listOf(SaveReason.TICK, SaveReason.TICK), (writes).toList())
    }

    @Test fun everyEventWritesImmediately() = runTest {
        SaveReason.entries.filter { it != SaveReason.TICK }.forEach { clock += 100L; saver.onEvent(it) }
        assertEquals(listOf(
            SaveReason.PAUSE, SaveReason.SEEK, SaveReason.CHAPTER, SaveReason.TRACK, SaveReason.STOP,
        ), (writes).toList())
    }

    @Test fun anEventRestartsTheTickClock() = runTest {
        repeat(4) { clock += 1_000L; saver.onTick(5_000L) }
        saver.onEvent(SaveReason.SEEK)                                // at 4 s
        repeat(4) { clock += 1_000L; saver.onTick(5_000L) }           // 8 s: only 4 s since the seek
        assertEquals(listOf(SaveReason.SEEK), (writes).toList())
        clock += 1_000L; saver.onTick(5_000L)                         // 9 s: 5 s since the seek
        assertEquals(listOf(SaveReason.SEEK, SaveReason.TICK), (writes).toList())
    }
}
