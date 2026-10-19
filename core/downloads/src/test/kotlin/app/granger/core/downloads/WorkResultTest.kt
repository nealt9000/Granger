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

    @Test fun failedFileInAGroupLetsTheRestContinue() {
        assertThat(RunOutcome.FAILED.toWorkResult(grouped = true)).isEqualTo(ListenableWorker.Result.success())
    }

    @Test fun ungroupedFailureStillFails() {
        assertThat(RunOutcome.FAILED.toWorkResult(grouped = false)).isEqualTo(ListenableWorker.Result.failure())
    }

    @Test fun aGroupedRetryStillWaitsSoTheChainRunsOneFileAtATime() {
        assertThat(RunOutcome.RETRY.toWorkResult(grouped = true)).isEqualTo(ListenableWorker.Result.retry())
        assertThat(RunOutcome.INSTALLED.toWorkResult(grouped = true)).isEqualTo(ListenableWorker.Result.success())
        assertThat(RunOutcome.GONE.toWorkResult(grouped = true)).isEqualTo(ListenableWorker.Result.success())
    }

    @Test fun theTenthAttemptIsTheLast() {
        // runAttemptCount starts at 0, so attempt 10 has a count of 9.
        assertThat(DownloadWorker.isLastAttempt(8)).isFalse()
        assertThat(DownloadWorker.isLastAttempt(9)).isTrue()
    }
}
