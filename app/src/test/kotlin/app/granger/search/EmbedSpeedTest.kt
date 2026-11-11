package app.granger.search

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EmbedSpeedTest {
    @Test fun aFastPhoneKeepsMeaning() {
        val speed = EmbedSpeed()
        repeat(10) { speed.record(1_200) }
        assertThat(speed.slow).isFalse()
        assertThat(speed.useMeaning()).isTrue()
    }

    @Test fun theFirstSearchLoadsTheEmbedderAndDoesNotCount() {
        val speed = EmbedSpeed()
        speed.record(9_000)
        speed.record(1_000); speed.record(1_000)
        assertThat(speed.slow).isFalse()
    }

    @Test fun aSlowPhoneGoesKeywordOnlyAndTriesAgainLater() {
        val speed = EmbedSpeed()
        speed.record(0)
        repeat(3) { speed.record(6_000) }
        assertThat(speed.slow).isTrue()
        val asks = (1..EmbedSpeed.RETRY_AFTER).map { speed.useMeaning() }
        assertThat(asks.count { it }).isEqualTo(1)
        assertThat(asks.last()).isTrue()
        // The retry comes back fast: meaning is back.
        speed.record(1_500)
        assertThat(speed.slow).isFalse()
    }

    @Test fun oneSlowSearchDoesNotTurnMeaningOff() {
        val speed = EmbedSpeed()
        speed.record(0)
        speed.record(1_000); speed.record(9_000); speed.record(1_100)
        assertThat(speed.slow).isFalse()
    }
}
