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
@Config(sdk = [35], qualifiers = "w400dp-h800dp") // Tall enough for the whole Appearance section.
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

    @Test fun openToDefaultsToResumeAndIsSaved() {
        val prefs = SharedAppearancePrefs(shared)
        assertThat(prefs.openTo.value).isEqualTo(OpenTo.RESUME)
        prefs.setOpenTo(OpenTo.HOME)
        assertThat(prefs.openTo.value).isEqualTo(OpenTo.HOME)
        assertThat(SharedAppearancePrefs(shared).openTo.value).isEqualTo(OpenTo.HOME)
        shared.edit().putString("openTo", "ELSEWHERE").commit()
        assertThat(SharedAppearancePrefs(shared).openTo.value).isEqualTo(OpenTo.RESUME)
    }

    @Test fun openToRowsChooseAtOnce() {
        var openTo by mutableStateOf(OpenTo.RESUME)
        compose.setContent { AppearanceSection(RailSide.LEFT, {}, openTo, onOpenTo = { openTo = it }) }
        compose.onNodeWithText("Open to").assertExists()
        compose.onNodeWithText("Where I left off").assertIsSelected()
        compose.onNodeWithText("Home").assertIsNotSelected().performClick()
        compose.runOnIdle { assertThat(openTo).isEqualTo(OpenTo.HOME) }
        compose.onNodeWithText("Home").assertIsSelected()
    }

    @Test fun themeFollowsThePhoneByDefaultAndIsSaved() {
        val prefs = SharedAppearancePrefs(shared)
        assertThat(prefs.theme.value).isEqualTo(ThemeChoice.SYSTEM)
        prefs.setTheme(ThemeChoice.FIELD)
        assertThat(SharedAppearancePrefs(shared).theme.value).isEqualTo(ThemeChoice.FIELD)
        shared.edit().putString("theme", "SEPIA").commit()
        assertThat(SharedAppearancePrefs(shared).theme.value).isEqualTo(ThemeChoice.SYSTEM)
    }

    @Test fun themeChoiceResolvesAgainstThePhone() {
        assertThat(ThemeChoice.SYSTEM.night(phoneDark = true)).isTrue()
        assertThat(ThemeChoice.SYSTEM.night(phoneDark = false)).isFalse()
        assertThat(ThemeChoice.FIELD.night(phoneDark = true)).isFalse()
        assertThat(ThemeChoice.NIGHT.night(phoneDark = false)).isTrue()
    }

    @Test fun themeRowsChooseAtOnce() {
        var theme by mutableStateOf(ThemeChoice.SYSTEM)
        compose.setContent { AppearanceSection(RailSide.LEFT, {}, theme = theme, onTheme = { theme = it }) }
        compose.onNodeWithText("Theme").assertExists()
        compose.onNodeWithText("Follow the phone").assertIsSelected()
        compose.onNodeWithText("Night (dark)").assertIsNotSelected().performClick()
        compose.runOnIdle { assertThat(theme).isEqualTo(ThemeChoice.NIGHT) }
        compose.onNodeWithText("Field (light)").performClick()
        compose.runOnIdle { assertThat(theme).isEqualTo(ThemeChoice.FIELD) }
    }

    @Test fun toolbarSideRowsChooseAtOnce() {
        var side by mutableStateOf(RailSide.LEFT)
        compose.setContent { AppearanceSection(side, { side = it }) }
        compose.onNodeWithText("Appearance").assertExists()
        compose.onNodeWithText("Toolbar side").assertExists()
        compose.onNodeWithText("Left").assertIsSelected()
        compose.onNodeWithText("Right").assertIsNotSelected().performClick()
        compose.runOnIdle { assertThat(side).isEqualTo(RailSide.RIGHT) }
        compose.onNodeWithText("Right").assertIsSelected()
        compose.onNodeWithText("Left").assertIsNotSelected()
    }
}
