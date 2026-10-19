package app.granger.archive

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.granger.core.archive.ArchiveKind
import app.granger.core.archive.ArchiveShelf
import app.granger.core.archive.AudioOffer
import app.granger.sources.ItemStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ArchiveScreenTest {
    @get:Rule val compose = createComposeRule()

    private val spokenNote = "Archive.org dates spoken-word recordings by their text, not the recording, so Granger can't tell " +
        "which are in the public domain. LibriVox recordings all are."

    @Test fun spokenWordNoteOffersLibriVoxAndTheWaiver() {
        val calls = mutableListOf<String>()
        // A row is passed only to pin that nothing of the results shows while the waiver is needed.
        val ui = ArchiveUi(
            kind = ArchiveKind.SPOKEN_WORD, shelf = ArchiveShelf.SPOKEN_MOST_DOWNLOADED, filtered = true, waiverNeeded = true,
            loading = false, filterYear = 1926,
            rows = listOf(ArchiveRow("x", "A Result Row", "", null, ItemStatus.NotInstalled, AudioOffer.Tracks(emptyList(), "VBR MP3", null, 0))),
        )
        compose.setContent {
            ArchiveContent(
                ui = ui, searchText = "", snackbar = SnackbarHostState(),
                onQuery = {}, onTab = {}, onShelf = {}, onRetry = {}, onLoadMore = {},
                onOpenRow = {}, onGetRow = {}, onCancelRow = {}, onRetryRow = {}, onRemoveRow = {},
                onAcceptWaiver = { calls += "accept" }, onOpenLibriVox = { calls += "librivox" }, onBack = {},
            )
        }
        compose.onNodeWithText(spokenNote).assertExists()
        compose.onNodeWithText("Search archive.org audiobooks").assertExists()
        compose.onNodeWithText("A Result Row").assertDoesNotExist()
        compose.onNodeWithText("Poetry").assertDoesNotExist()
        compose.onNodeWithText("Nothing matches that search.").assertDoesNotExist()

        compose.onNodeWithText("LibriVox").performClick()
        assertThat(calls).containsExactly("librivox")

        compose.onNodeWithText("Show everything").performClick()
        compose.onNodeWithText("Show everything on archive.org?").assertExists()
        compose.onNodeWithText(
            "Granger is a browser for archive.org. It doesn't host, check or vouch for anything there, " +
                "and much of it is still under copyright. You're responsible for having the right to download and use what you take.",
        ).assertExists()
        compose.onNodeWithText("I accept").performClick()
        assertThat(calls).containsExactly("librivox", "accept").inOrder()
        compose.onNodeWithText("Show everything on archive.org?").assertDoesNotExist()
    }

    @Test fun musicShowsTheRollingFilterNote() {
        val ui = ArchiveUi(kind = ArchiveKind.MUSIC, shelf = ArchiveShelf.MUSIC_MOST_DOWNLOADED, filtered = true, loading = false, filterYear = 1926)
        compose.setContent {
            ArchiveContent(
                ui = ui, searchText = "", snackbar = SnackbarHostState(),
                onQuery = {}, onTab = {}, onShelf = {}, onRetry = {}, onLoadMore = {},
                onOpenRow = {}, onGetRow = {}, onCancelRow = {}, onRetryRow = {}, onRemoveRow = {},
                onAcceptWaiver = {}, onOpenLibriVox = {}, onBack = {},
            )
        }
        compose.onNodeWithText("Showing public-domain recordings only: published before 1926.").assertExists()
        compose.onNodeWithText("Search archive.org music").assertExists()
        compose.onNodeWithText("78 rpm records").assertExists()
        compose.onNodeWithText("Show everything").assertExists()
        compose.onNodeWithText("LibriVox").assertDoesNotExist()
    }
}
