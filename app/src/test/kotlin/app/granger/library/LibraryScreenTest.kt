package app.granger.library

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun mediaHintLinksToTheDownloadSourcesAndSettings() {
        val calls = mutableListOf<String>()
        compose.setContent {
            MediaHintRow(
                text = "No music or audiobooks yet",
                links = mediaHintLinks(listOf("zim")),
                onOpenSource = { calls += it.name },
                onMediaSettings = { calls += "settings" },
            )
        }
        compose.onNodeWithText("No music or audiobooks yet").assertExists()
        compose.onNodeWithText("LibriVox").performClick()
        compose.onNodeWithText("Archive.org audiobooks").performClick()
        compose.onNodeWithText("Archive.org music").performClick()
        compose.onNodeWithText("Settings").performClick()
        assertThat(calls).containsExactly("LIBRIVOX", "ARCHIVE_ORG_SPOKEN", "ARCHIVE_ORG_MUSIC", "settings").inOrder()
    }
}
