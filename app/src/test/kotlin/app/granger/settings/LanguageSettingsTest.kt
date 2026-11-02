package app.granger.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/** Settings → Appearance → Language (Plan L2 Task 3). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LanguageSettingsTest {
    @get:Rule val compose = createComposeRule()

    private val chosen = mutableListOf<AppLanguage>()

    private fun show(phone: Locale) = compose.setContent {
        var current by remember { mutableStateOf(AppLanguage.SYSTEM) }
        LanguageSettingsContent(current, phone, { current = it; chosen += it }, onBack = {})
    }

    @Test fun eachLanguageIsNamedInItself() {
        show(Locale.forLanguageTag("es-MX"))
        compose.onNodeWithText("App language").assertExists()
        compose.onNodeWithText("Follow the phone (Español)").assertIsSelected()
        compose.onNodeWithText("English").assertExists()
        compose.onNodeWithText("Español").performClick()
        compose.onNodeWithText("Español").assertIsSelected()
        assertThat(chosen).containsExactly(AppLanguage.SPANISH)
    }

    @Config(qualifiers = "es")
    @Test fun inSpanish() {
        show(Locale.forLanguageTag("en-US"))
        compose.onNodeWithText("Idioma").assertExists()
        compose.onNodeWithText("Idioma de la app").assertExists()
        compose.onNodeWithText("Igual que el teléfono (English)").assertIsSelected()
        compose.onNodeWithText("English").performClick()
        assertThat(chosen).containsExactly(AppLanguage.ENGLISH)
    }

    @Test fun theLibrarysLanguagesShowOnlyWithTwoOffered() {
        compose.setContent { LanguageSettingsContent(AppLanguage.SYSTEM, Locale.US, {}, {}, LibraryLanguages(listOf("en"), listOf("en"), {})) }
        compose.onNodeWithText("Your library's languages").assertDoesNotExist()
    }

    @Test fun theLibrarysLanguagesTickAndMakeMain() {
        val changes = mutableListOf<List<String>>()
        compose.setContent {
            var library by remember { mutableStateOf(listOf("en")) }
            LanguageSettingsContent(
                AppLanguage.SYSTEM, Locale.US, {}, {},
                LibraryLanguages(library, listOf("en", "es"), { library = it; changes += it }),
            )
        }
        compose.onNodeWithText("Your library's languages").assertExists()
        // The app language's radio row is first; the library's row is the second of each name.
        compose.onAllNodesWithText("Español")[1].performClick()
        compose.onNodeWithText("Main language").assertExists()
        compose.onNodeWithText("Make main").performClick()
        assertThat(changes).containsExactly(listOf("en", "es"), listOf("es", "en")).inOrder()
    }

    @Config(qualifiers = "es")
    @Test fun theLibrarysLanguagesInSpanish() {
        compose.setContent { LanguageSettingsContent(AppLanguage.SYSTEM, Locale.US, {}, {}, LibraryLanguages(listOf("es"), listOf("en", "es"), {})) }
        compose.onNodeWithText("Idiomas de tu biblioteca").assertExists()
    }
}
