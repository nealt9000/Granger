package app.granger

import android.os.Bundle
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StartBookWorkTest {
    @Test fun aFreshStartScansThenIndexes() = runTest {
        val shelf = FakeBookshelf()
        var indexed = 0
        startBookWork(null, this, shelf) { indexed++ }
        advanceUntilIdle()
        assertThat(shelf.refreshes).isEqualTo(1)
        assertThat(indexed).isEqualTo(1)
    }

    @Test fun rotationStartsNothing() = runTest {
        val shelf = FakeBookshelf()
        var indexed = 0
        assertThat(startBookWork(Bundle(), this, shelf) { indexed++ }).isNull()
        advanceUntilIdle()
        assertThat(shelf.refreshes).isEqualTo(0)
        assertThat(indexed).isEqualTo(0)
    }

    @Test fun aFailedScanStillIndexes() = runTest {
        val shelf = object : app.granger.core.library.Bookshelf by FakeBookshelf() {
            override suspend fun refresh() = error("SD card gone")
        }
        var indexed = 0
        startBookWork(null, this, shelf) { indexed++ }
        advanceUntilIdle()
        assertThat(indexed).isEqualTo(1)
    }
}
