package app.granger.launch

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionMarkerTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("session", Context.MODE_PRIVATE)

    private fun marker(boot: Long = 1_000_000L) = SessionMarker(prefs) { boot }

    @Test fun firstEverReadIsFirstRun() {
        assertThat(marker().readAndStartSession().third).isTrue()
        assertThat(marker().readAndStartSession().third).isFalse()
    }

    @Test fun cleanExitThenReadIsClean() {
        marker().readAndStartSession()
        marker().markCleanExit()
        assertThat(marker().readAndStartSession()).isEqualTo(Triple(true, false, false))
    }

    @Test fun startWithoutCleanExitIsDirty() {
        marker().readAndStartSession()
        marker().markCleanExit()
        marker().readAndStartSession()
        marker().markDirty()
        assertThat(marker().readAndStartSession().first).isFalse()
    }

    @Test fun bootTimeChangeBeyondToleranceIsReboot() {
        marker(1_000_000L).readAndStartSession()
        marker().markCleanExit()
        assertThat(marker(1_004_000L).readAndStartSession().second).isFalse()
        assertThat(marker(1_020_000L).readAndStartSession().second).isTrue()
    }
}
