package app.granger.core.downloads

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TransferRateTest {
    private val rate = TransferRate(windowMillis = 20_000, warmUpMillis = 10_000)

    private fun steady(bytesPerSecond: Long, from: Long, seconds: Int) {
        for (s in 1..seconds) rate.add(bytesPerSecond, from + s * 1_000L)
    }

    @Test fun nothingUntilTenSecondsOfData() {
        assertThat(rate.bytesPerSecond(0)).isNull()
        steady(2_000_000, from = 0, seconds = 9)
        assertThat(rate.bytesPerSecond(9_999)).isNull()
        steady(2_000_000, from = 9_000, seconds = 2)
        assertThat(rate.bytesPerSecond(11_000)).isEqualTo(2_000_000)
    }

    @Test fun theWindowRollsSoAnOldBurstFadesOut() {
        // A fast start, then slow: after 20 s of slow data only the slow rate is left.
        steady(10_000_000, from = 0, seconds = 10)
        steady(1_000_000, from = 10_000, seconds = 20)
        assertThat(rate.bytesPerSecond(30_000)).isEqualTo(1_000_000)
        // Halfway, it's between the two.
        val mixed = TransferRate().apply {
            for (s in 1..10) add(10_000_000, s * 1_000L)
            for (s in 11..20) add(1_000_000, s * 1_000L)
        }
        assertThat(mixed.bytesPerSecond(20_000)).isGreaterThan(1_000_000L)
        assertThat(mixed.bytesPerSecond(20_000)).isLessThan(10_000_000L)
    }

    @Test fun aStallFallsToZero() {
        steady(1_000_000, from = 0, seconds = 12)
        assertThat(rate.bytesPerSecond(12_000)).isEqualTo(1_000_000)
        assertThat(rate.bytesPerSecond(22_000)).isEqualTo(500_000)
        assertThat(rate.bytesPerSecond(40_000)).isEqualTo(0)
    }

    @Test fun resetStartsTheWarmUpAgain() {
        steady(1_000_000, from = 0, seconds = 12)
        rate.reset()
        rate.add(1_000_000, 13_000)
        assertThat(rate.bytesPerSecond(14_000)).isNull()
    }
}
