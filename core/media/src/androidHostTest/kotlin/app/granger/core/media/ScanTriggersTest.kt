package app.granger.core.media

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ScanTriggersTest {
    @Test fun appStartScansAtOnce() = runTest {
        var scans = 0
        ScanTriggers(backgroundScope) { scans++ }.appStarted()
        runCurrent()
        assertThat(scans).isEqualTo(1)
    }

    @Test fun mediaChangesAreDebouncedTwoSeconds() = runTest {
        var scans = 0
        val triggers = ScanTriggers(backgroundScope) { scans++ }
        triggers.mediaChanged()
        advanceTimeBy(1_500)
        triggers.mediaChanged()
        triggers.mediaChanged()
        advanceTimeBy(1_999)
        runCurrent()
        assertThat(scans).isEqualTo(0)
        advanceTimeBy(2)
        runCurrent()
        assertThat(scans).isEqualTo(1)
    }
}
