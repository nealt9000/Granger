package app.granger.core.ai

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DeviceTierTest {
    // Values are what ActivityManager.MemoryInfo.totalMem reports, which is below the marketed RAM.
    @Test fun fourGbPhoneIsLibraryOnly() {
        assertThat(DeviceTiers.forTotalRam(3_650_000_000L)).isEqualTo(DeviceTier.LIBRARY_ONLY)
    }

    @Test fun sixGbPhoneGetsAsk() {
        assertThat(DeviceTiers.forTotalRam(5_480_000_000L)).isEqualTo(DeviceTier.ASK)
    }

    @Test fun eightGbPixel6GetsAsk() {
        assertThat(DeviceTiers.forTotalRam(7_300_000_000L)).isEqualTo(DeviceTier.ASK)
        assertThat(DeviceTiers.forTotalRam(7_650_000_000L)).isEqualTo(DeviceTier.ASK)
    }

    @Test fun twelveGbPhoneGetsAsk() {
        assertThat(DeviceTiers.forTotalRam(11_400_000_000L)).isEqualTo(DeviceTier.ASK)
    }

    @Test fun boundariesAreInclusive() {
        assertThat(DeviceTiers.forTotalRam(DeviceTiers.ASK_MIN_BYTES)).isEqualTo(DeviceTier.ASK)
        assertThat(DeviceTiers.forTotalRam(DeviceTiers.ASK_MIN_BYTES - 1)).isEqualTo(DeviceTier.LIBRARY_ONLY)
    }
}
