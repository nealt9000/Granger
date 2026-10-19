package app.granger.core.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AutoRewindTest {
    @Test fun rewindSteps() {
        assertThat(listOf(5_000L, 60_000L, 1_800_000L, 7_200_000L).map(AutoRewind::rewindMs))
            .containsExactly(0L, 3_000L, 10_000L, 30_000L).inOrder()
    }

    @Test fun boundariesBelongToTheLongerStep() {
        assertThat(listOf(9_999L, 10_000L, 299_999L, 300_000L, 3_599_999L, 3_600_000L).map(AutoRewind::rewindMs))
            .containsExactly(0L, 3_000L, 3_000L, 10_000L, 10_000L, 30_000L).inOrder()
    }
}
