package app.granger.core.downloads

import androidx.work.ListenableWorker
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorkResultTest {
    @Test fun outcomesMapToWorkResults() {
        assertThat(RunOutcome.INSTALLED.toWorkResult()).isEqualTo(ListenableWorker.Result.success())
        assertThat(RunOutcome.GONE.toWorkResult()).isEqualTo(ListenableWorker.Result.success())
        assertThat(RunOutcome.RETRY.toWorkResult()).isEqualTo(ListenableWorker.Result.retry())
        assertThat(RunOutcome.FAILED.toWorkResult()).isEqualTo(ListenableWorker.Result.failure())
    }
}
