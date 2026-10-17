package app.granger

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StartBookWorkTest {
    @Test fun firstStartInAProcessScansThenIndexes() = runTest {
        val shelf = FakeBookshelf()
        var indexed = 0
        startBookWork(AtomicBoolean(false), this, shelf) { indexed++ }
        advanceUntilIdle()
        assertThat(shelf.refreshes).isEqualTo(1)
        assertThat(indexed).isEqualTo(1)
    }

    @Test fun runsOncePerProcessSoRotationStartsNothing() = runTest {
        val shelf = FakeBookshelf()
        var indexed = 0
        val process = AtomicBoolean(false)
        assertThat(startBookWork(process, this, shelf) { indexed++ }).isNotNull()
        // Rotation: a new activity in the same process.
        assertThat(startBookWork(process, this, shelf) { indexed++ }).isNull()
        advanceUntilIdle()
        assertThat(shelf.refreshes).isEqualTo(1)
        assertThat(indexed).isEqualTo(1)
        // A process restored after death starts with a fresh flag and runs again.
        startBookWork(AtomicBoolean(false), this, shelf) { indexed++ }
        advanceUntilIdle()
        assertThat(shelf.refreshes).isEqualTo(2)
        assertThat(indexed).isEqualTo(2)
    }

    @Test fun aFailedScanStillIndexes() = runTest {
        val shelf = object : app.granger.core.library.Bookshelf by FakeBookshelf() {
            override suspend fun refresh() = error("SD card gone")
        }
        var indexed = 0
        startBookWork(AtomicBoolean(false), this, shelf) { indexed++ }
        advanceUntilIdle()
        assertThat(indexed).isEqualTo(1)
    }
}
