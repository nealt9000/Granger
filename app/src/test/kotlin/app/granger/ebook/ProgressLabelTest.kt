package app.granger.ebook

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProgressLabelTest {
    private fun locator(title: String?, position: Int?, total: Double?) = Locator(
        href = Url("c1.xhtml")!!,
        mediaType = MediaType.XHTML,
        title = title,
        locations = Locator.Locations(position = position, totalProgression = total),
    )

    @Test fun withPositions() {
        assertThat(ProgressLabel.of(locator("ACT III — Scene I", 120, 0.29), 410))
            .isEqualTo("ACT III — Scene I · p. 120 of 410")
    }

    @Test fun withoutPositionsUsesPercent() {
        assertThat(ProgressLabel.of(locator("Two", 3, 0.294), 0)).isEqualTo("Two · 29%")
    }

    @Test fun blankChapterTitleIsDropped() {
        assertThat(ProgressLabel.of(locator("  ", 5, 0.1), 50)).isEqualTo("p. 5 of 50")
        assertThat(ProgressLabel.of(locator(null, null, 0.5), 0)).isEqualTo("50%")
    }
}
