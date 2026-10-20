package app.granger.reader

import androidx.activity.ComponentActivity
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Leaving a reader brings its chrome back for the return; rotating it doesn't, so full-screen reading survives a turn. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChromeBackOnReturnTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var left = 0
    private var disposed = 0
    private var shown by mutableStateOf(true)

    private fun show() = compose.setContent {
        if (shown) {
            ChromeBackOnReturn { left++ }
            DisposableEffect(Unit) { onDispose { disposed++ } }
        }
    }

    @Test fun leavingTheScreenCountsAsLeft() {
        show()
        compose.runOnIdle { shown = false }
        compose.waitForIdle()
        assertThat(disposed).isEqualTo(1)
        assertThat(left).isEqualTo(1)
    }

    @Test fun aRotationDoesNotCountAsLeft() {
        show()
        compose.waitForIdle()
        compose.activityRule.scenario.recreate()
        // The old activity's composition went away with it, yet the reader wasn't left.
        assertThat(disposed).isEqualTo(1)
        assertThat(left).isEqualTo(0)
    }
}
