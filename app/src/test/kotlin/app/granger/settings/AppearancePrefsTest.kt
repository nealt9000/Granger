package app.granger.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppearancePrefsTest {
    @get:Rule val compose = createComposeRule()

    private val shared = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("appearance-test", Context.MODE_PRIVATE)

    @Test fun defaultIsLeft() {
        assertThat(SharedAppearancePrefs(shared).railSide.value).isEqualTo(RailSide.LEFT)
    }

    @Test fun choiceIsSavedAndRead() {
        val prefs = SharedAppearancePrefs(shared)
        prefs.setRailSide(RailSide.RIGHT)
        assertThat(prefs.railSide.value).isEqualTo(RailSide.RIGHT)
        // A new instance (the next launch) reads it back.
        assertThat(SharedAppearancePrefs(shared).railSide.value).isEqualTo(RailSide.RIGHT)
        prefs.setRailSide(RailSide.LEFT)
        assertThat(SharedAppearancePrefs(shared).railSide.value).isEqualTo(RailSide.LEFT)
    }

    @Test fun anUnknownStoredValueReadsAsLeft() {
        shared.edit().putString("railSide", "MIDDLE").commit()
        assertThat(SharedAppearancePrefs(shared).railSide.value).isEqualTo(RailSide.LEFT)
    }

    @Test fun toolbarSideRowsChooseAtOnce() {
        var side by mutableStateOf(RailSide.LEFT)
        compose.setContent { AppearanceSection(side) { side = it } }
        compose.onNodeWithText("Appearance").assertExists()
        compose.onNodeWithText("Toolbar side").assertExists()
        compose.onNodeWithText("Left").assertIsSelected()
        compose.onNodeWithText("Right").assertIsNotSelected().performClick()
        compose.runOnIdle { assertThat(side).isEqualTo(RailSide.RIGHT) }
        compose.onNodeWithText("Right").assertIsSelected()
        compose.onNodeWithText("Left").assertIsNotSelected()
    }
}
