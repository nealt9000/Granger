package app.granger.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A reader's bar slot keeps its size while hidden, and a hidden bar takes no taps. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChromeSlotTest {
    @get:Rule val compose = createComposeRule()

    private var visible by mutableStateOf(true)
    private var taps = 0

    private fun show() = compose.setContent {
        Column {
            ChromeSlot(visible, Modifier.testTag("slot")) {
                Box(Modifier.size(120.dp, 64.dp).clickable { taps++ })
            }
            Box(Modifier.size(120.dp, 200.dp).testTag("below"))
        }
    }

    @Test fun hidingKeepsTheSlotsSizeSoWhatIsBelowDoesNotMove() {
        show()
        val slot = compose.onNodeWithTag("slot").getBoundsInRoot()
        val below = compose.onNodeWithTag("below").getBoundsInRoot()
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        assertThat(compose.onNodeWithTag("slot").getBoundsInRoot()).isEqualTo(slot)
        assertThat(compose.onNodeWithTag("below").getBoundsInRoot()).isEqualTo(below)
    }

    @Test fun aHiddenBarTakesNoTaps() {
        show()
        compose.onNodeWithTag("slot").performTouchInput { click(center) }
        compose.runOnIdle { assertThat(taps).isEqualTo(1) }
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        compose.onNodeWithTag("slot").performTouchInput { click(center) }
        compose.runOnIdle { assertThat(taps).isEqualTo(1) }
    }
}
