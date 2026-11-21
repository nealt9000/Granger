package app.granger.core.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SleepTimerTest {
    private var clock = 0L
    private val timer = SleepTimer { clock }

    @Test fun noTimerHasNoVolumeAndNeverExpires() {
        assertNull(timer.volume())
        assertNull(timer.remainingMs)
        assertFalse(timer.expired())
    }

    @Test fun sleepTimerFadesOverLastTenSeconds() {
        timer.start(SleepSetting.After(60_000L)) { null }
        clock = 40_000L
        assertEquals(1f, timer.volume())
        clock = 50_000L
        assertEquals(1f, timer.volume())
        clock = 55_000L
        assertEquals(5_000L, timer.remainingMs)
        assertEquals(0.5f, timer.volume()!!, 1e-6f)
        assertFalse(timer.expired())
        clock = 60_000L
        assertEquals(0f, timer.volume())
        assertTrue(timer.expired())
        timer.cancel()
        assertNull(timer.volume())
        assertFalse(timer.expired())
    }

    @Test fun endOfChapterExpiresAtChapterEnd() {
        var chapterEndsIn = 30_000L
        timer.start(SleepSetting.EndOfChapter) { chapterEndsIn }
        assertEquals(30_000L, timer.remainingMs)
        assertFalse(timer.expired())
        // A seek moves the end: the lambda is read at query time.
        chapterEndsIn = 8_000L
        assertEquals(0.8f, timer.volume()!!, 1e-6f)
        chapterEndsIn = 0L
        assertTrue(timer.expired())
    }

    @Test fun anEstimatedEndIsShownButNeverFadesOrExpires() {
        var chapterEndsIn = 30_000L
        timer.start(SleepSetting.EndOfChapter, estimated = true) { chapterEndsIn }
        assertEquals(30_000L, timer.remainingMs)
        chapterEndsIn = 5_000L
        assertEquals(1f, timer.volume())
        chapterEndsIn = 0L
        assertEquals(0L, timer.remainingMs)
        assertFalse(timer.expired())                  // the caller stops at the real end itself
        // Only End of chapter can be an estimate; a new start clears it.
        timer.start(SleepSetting.After(1_000L), estimated = true) { null }
        clock = 1_000L
        assertTrue(timer.expired())
    }

    @Test fun endOfChapterWithUnknownChapterKeepsFullVolume() {
        timer.start(SleepSetting.EndOfChapter) { null }
        assertNull(timer.remainingMs)
        assertEquals(1f, timer.volume())
        assertFalse(timer.expired())
    }
}
