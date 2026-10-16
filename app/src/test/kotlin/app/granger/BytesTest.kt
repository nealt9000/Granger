package app.granger

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BytesTest {
    @Test fun formatsDecimalUnits() {
        assertThat(formatBytes(512)).isEqualTo("512 B")
        assertThat(formatBytes(2_000_000_000)).isEqualTo("2.0 GB")
        assertThat(formatBytes(1_047_708_672)).isEqualTo("1.0 GB")
        assertThat(formatBytes(350_000_000)).isEqualTo("350 MB")
        assertThat(formatBytes(12_400)).isEqualTo("12 KB")
        assertThat(formatBytes(0)).isEqualTo("0 B")
    }
}
