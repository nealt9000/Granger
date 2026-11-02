package app.granger.core.downloads

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class QueuePolicyTest {
    private val gb = 1_000_000_000L

    @Test fun spaceCountsOtherQueuedDownloadsAndTheReserve() {
        val free = gb + QueuePolicy.RESERVE_BYTES
        assertThat(QueuePolicy.check(itemBytes = gb, freeBytes = free, queuedOnVolumeBytes = 0)).isEqualTo(EnqueueResult.Queued)
        assertThat(QueuePolicy.check(itemBytes = gb, freeBytes = free, queuedOnVolumeBytes = 300)).isEqualTo(EnqueueResult.NoSpace(300))
        assertThat(QueuePolicy.check(itemBytes = gb, freeBytes = free - 1, queuedOnVolumeBytes = 0)).isEqualTo(EnqueueResult.NoSpace(1))
    }

    @Test fun thereIsNoStorageCap() {
        // Far more than any phone holds already installed is not part of the check: only the volume's free space is.
        assertThat(QueuePolicy.check(itemBytes = 500 * gb, freeBytes = 1_000 * gb, queuedOnVolumeBytes = 400 * gb))
            .isEqualTo(EnqueueResult.Queued)
    }
}
