package app.granger

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Locale

class BytesTest {
    @Test fun formatsDecimalUnits() {
        assertThat(formatBytes(512, Locale.US)).isEqualTo("512 B")
        assertThat(formatBytes(2_000_000_000, Locale.US)).isEqualTo("2.0 GB")
        assertThat(formatBytes(1_047_708_672, Locale.US)).isEqualTo("1.0 GB")
        assertThat(formatBytes(350_000_000, Locale.US)).isEqualTo("350 MB")
        assertThat(formatBytes(12_400, Locale.US)).isEqualTo("12 KB")
        assertThat(formatBytes(0, Locale.US)).isEqualTo("0 B")
    }

    @Test fun theNumberTakesTheLocalesDecimalSeparator() {
        assertThat(formatBytes(2_000_000_000, Locale.forLanguageTag("es"))).isEqualTo("2,0 GB")
        assertThat(formatBytes(1_500_000, Locale.GERMANY)).isEqualTo("1,5 MB")
    }
}
