package app.granger.shell

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RailCoversTest {
    @Test fun sampleSizeKeepsAtLeastTheTargetOnTheShorterSide() {
        assertThat(RailCovers.sampleSize(1000, 1500, 100)).isEqualTo(8)
        assertThat(RailCovers.sampleSize(90, 90, 100)).isEqualTo(1)
    }
}
