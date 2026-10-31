package app.granger.ebook

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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

    private fun label(locator: Locator, positionsCount: Int) =
        ProgressLabel.of(locator, positionsCount).resolve(ApplicationProvider.getApplicationContext<Context>())

    @Test fun withPositions() {
        assertThat(label(locator("ACT III — Scene I", 120, 0.29), 410))
            .isEqualTo("ACT III — Scene I · p. 120 of 410")
    }

    @Test fun withoutPositionsUsesPercent() {
        assertThat(label(locator("Two", 3, 0.294), 0)).isEqualTo("Two · 29%")
    }

    @Test fun blankChapterTitleIsDropped() {
        assertThat(label(locator("  ", 5, 0.1), 50)).isEqualTo("p. 5 of 50")
        assertThat(label(locator(null, null, 0.5), 0)).isEqualTo("50%")
    }
}
