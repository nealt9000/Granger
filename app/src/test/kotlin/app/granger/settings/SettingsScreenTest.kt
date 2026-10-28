package app.granger.settings

import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.granger.FakeAppearancePrefs
import app.granger.FakeDownloads
import app.granger.FakePrefs
import app.granger.FakeVolumes
import app.granger.MediaSettingsRoute
import app.granger.SettingsRoute
import app.granger.archive.ArchivePrefs
import app.granger.railToolOf
import app.granger.shell.RailTool
import app.granger.volume
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h2400dp") // Tall enough for every section at once.
class SettingsScreenTest {
    @get:Rule val compose = createComposeRule()

    private class NoWaiver : ArchivePrefs {
        override val waiverAccepted: StateFlow<Boolean> = MutableStateFlow(false)
        override fun accept() {}
        override fun withdraw() {}
    }

    private val calls = mutableListOf<String>()

    private fun show(listening: Boolean = true, audiobooks: Boolean = true) = compose.setContent {
        SettingsScreen(
            SettingsViewModel(
                FakeDownloads(), FakeVolumes(listOf(volume("/internal", "Internal storage"))), FakePrefs(), NoWaiver(), FakeAppearancePrefs(),
                appScope = CoroutineScope(Dispatchers.Unconfined),
            ),
            onModels = { calls += "models" },
            onSetUpAgain = { calls += "setup" },
            onBack = {},
            onVoice = if (listening) ({ calls += "voice" }) else null,
            onMediaSettings = if (listening) ({ calls += "media" }) else null,
            audiobooks = audiobooks,
        )
    }

    private fun top(text: String) = compose.onNodeWithText(text).getBoundsInRoot().top

    @Test fun fiveSectionsInOrderEachWithItsRows() {
        show()
        val sections = listOf("Library & storage", "Reading & listening", "Ask", "Appearance", "About")
        assertThat(sections.map(::top)).isInStrictOrder()
        // Each row under its heading.
        assertThat(listOf("Library & storage", "Download on Wi-Fi only", "Archive.org: show everything", "Set up suggestions again", "Reading & listening").map(::top))
            .isInStrictOrder()
        assertThat(listOf("Reading & listening", "Voice", "Music & audiobooks", "Ask", "Models", "Appearance").map(::top)).isInStrictOrder()
        // Readiness is reached from Home and the Library now.
        compose.onNodeWithText("Readiness").assertDoesNotExist()
        for (row in listOf("Set up suggestions again", "Voice", "Music & audiobooks", "Models")) compose.onNodeWithText(row).performClick()
        assertThat(calls).containsExactly("setup", "voice", "media", "models").inOrder()
    }

    /** Plan U2 Decision 7: audiobooks are named only once there's one on the phone. */
    @Test fun withoutAnAudiobookTheRowIsMusic() {
        show(audiobooks = false)
        compose.onNodeWithText("Music").performClick()
        compose.onNodeWithText("Music & audiobooks").assertDoesNotExist()
        assertThat(calls).containsExactly("media")
    }

    @Test fun withoutVoiceOrMusicThereIsNoReadingAndListening() {
        show(listening = false)
        compose.onNodeWithText("Reading & listening").assertDoesNotExist()
        compose.onNodeWithText("Ask").assertExists()
    }

    @Test fun musicAndAudiobooksBelongsToSettings() {
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = SettingsRoute) {
                composable<SettingsRoute> {}
                composable<MediaSettingsRoute> {}
            }
        }
        compose.runOnIdle { nav.navigate(MediaSettingsRoute) }
        compose.runOnIdle { assertThat(railToolOf(nav.currentDestination!!)).isEqualTo(RailTool.SETTINGS) }
    }
}
