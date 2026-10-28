package app.granger.core.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SleepTimerTest {
    private var clock = 0L
    private val timer = SleepTimer { clock }

    @Test fun noTimerHasNoVolumeAndNeverExpires() {
        assertThat(timer.volume()).isNull()
        assertThat(timer.remainingMs).isNull()
        assertThat(timer.expired()).isFalse()
    }

    @Test fun sleepTimerFadesOverLastTenSeconds() {
        timer.start(SleepSetting.After(60_000L)) { null }
        clock = 40_000L
        assertThat(timer.volume()).isEqualTo(1f)
        clock = 50_000L
        assertThat(timer.volume()).isEqualTo(1f)
        clock = 55_000L
        assertThat(timer.remainingMs).isEqualTo(5_000L)
        assertThat(timer.volume()).isWithin(1e-6f).of(0.5f)
        assertThat(timer.expired()).isFalse()
        clock = 60_000L
        assertThat(timer.volume()).isEqualTo(0f)
        assertThat(timer.expired()).isTrue()
        timer.cancel()
        assertThat(timer.volume()).isNull()
        assertThat(timer.expired()).isFalse()
    }

    @Test fun endOfChapterExpiresAtChapterEnd() {
        var chapterEndsIn = 30_000L
        timer.start(SleepSetting.EndOfChapter) { chapterEndsIn }
        assertThat(timer.remainingMs).isEqualTo(30_000L)
        assertThat(timer.expired()).isFalse()
        // A seek moves the end: the lambda is read at query time.
        chapterEndsIn = 8_000L
        assertThat(timer.volume()).isWithin(1e-6f).of(0.8f)
        chapterEndsIn = 0L
        assertThat(timer.expired()).isTrue()
    }

    @Test fun anEstimatedEndIsShownButNeverFadesOrExpires() {
        var chapterEndsIn = 30_000L
        timer.start(SleepSetting.EndOfChapter, estimated = true) { chapterEndsIn }
        assertThat(timer.remainingMs).isEqualTo(30_000L)
        chapterEndsIn = 5_000L
        assertThat(timer.volume()).isEqualTo(1f)
        chapterEndsIn = 0L
        assertThat(timer.remainingMs).isEqualTo(0L)
        assertThat(timer.expired()).isFalse()                  // the caller stops at the real end itself
        // Only End of chapter can be an estimate; a new start clears it.
        timer.start(SleepSetting.After(1_000L), estimated = true) { null }
        clock = 1_000L
        assertThat(timer.expired()).isTrue()
    }

    @Test fun endOfChapterWithUnknownChapterKeepsFullVolume() {
        timer.start(SleepSetting.EndOfChapter) { null }
        assertThat(timer.remainingMs).isNull()
        assertThat(timer.volume()).isEqualTo(1f)
        assertThat(timer.expired()).isFalse()
    }
}
