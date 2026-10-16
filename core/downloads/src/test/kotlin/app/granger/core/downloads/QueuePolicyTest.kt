package app.granger.core.downloads

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class QueuePolicyTest {
    private val gb = 1_000_000_000L
    private val roomy = 1_000 * gb

    private fun check(
        itemBytes: Long = gb, isPack: Boolean = true, installed: Long = 0, queued: Long = 0, replaced: Long = 0,
        cap: Long = 10 * gb, free: Long = roomy, queuedOnVolume: Long = 0,
    ) = QueuePolicy.check(itemBytes, isPack, installed, queued, replaced, cap, free, queuedOnVolume)

    @Test fun underTheCapQueues() {
        assertThat(check(installed = 8 * gb, queued = gb)).isEqualTo(EnqueueResult.Queued)
    }

    @Test fun installedPlusQueuedPacksOverTheCapAreRefused() {
        assertThat(check(itemBytes = gb, installed = 9 * gb, queued = gb / 2))
            .isEqualTo(EnqueueResult.OverCap(10 * gb, 10 * gb + gb / 2))
    }

    @Test fun modelsIgnoreTheCap() {
        assertThat(check(isPack = false, installed = 10 * gb)).isEqualTo(EnqueueResult.Queued)
    }

    @Test fun anUpdateDoesNotCountTheOldSnapshotTwice() {
        assertThat(check(itemBytes = 2 * gb, installed = 9 * gb, replaced = 2 * gb)).isEqualTo(EnqueueResult.Queued)
    }

    @Test fun spaceCountsOtherQueuedDownloadsAndTheReserve() {
        val free = gb + QueuePolicy.RESERVE_BYTES
        assertThat(check(itemBytes = gb, free = free)).isEqualTo(EnqueueResult.Queued)
        assertThat(check(itemBytes = gb, free = free, queuedOnVolume = 300)).isEqualTo(EnqueueResult.NoSpace(300))
        assertThat(check(itemBytes = gb, free = free - 1)).isEqualTo(EnqueueResult.NoSpace(1))
    }

    @Test fun capIsCheckedBeforeSpace() {
        assertThat(check(itemBytes = 11 * gb, free = 0)).isInstanceOf(EnqueueResult.OverCap::class.java)
    }
}
