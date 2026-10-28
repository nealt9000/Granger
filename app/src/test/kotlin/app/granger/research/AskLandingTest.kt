package app.granger.research

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.granger.core.catalog.ModelRole
import app.granger.modelEntry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Ask's title and the librarian's empty state (Plan U3a Decision 8). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp")
class AskLandingTest {
    @get:Rule val compose = createComposeRule()

    private val e2b = modelEntry("gemma-4-e2b-gpu", ModelRole.SMALL, size = 2_000_000_000).copy(title = "Gemma 4 E2B (answers questions)")
    private val embedder = modelEntry("embeddinggemma", ModelRole.EMBEDDER, size = 200_000_000)
    private val idle = LibrarianUi(listOf(e2b, embedder), bytes = 2_200_000_000, bytesDone = 0, downloading = false, failed = false)

    @Test fun theTitleSaysAskAndPreview() {
        compose.setContent { AskTitle() }
        compose.onNodeWithText("Ask").assertIsDisplayed()
        compose.onNodeWithText("Preview").assertIsDisplayed()
    }

    @Test fun missingModelsOfferTheLibrarianInPlace() {
        var gets = 0
        compose.setContent { LibrarianNotice(idle) { gets++ } }
        compose.onNodeWithText("Ask needs the librarian on this phone: Gemma 4 E2B, 2.2 GB.").assertIsDisplayed()
        compose.onNodeWithText("Get the librarian · 2.2 GB").performClick()
        assertThat(gets).isEqualTo(1)
    }

    @Test fun aDownloadShowsItsProgress() {
        compose.setContent { LibrarianNotice(idle.copy(bytesDone = 1_100_000_000, downloading = true)) {} }
        compose.onNodeWithText("Downloading the librarian · 1.1 GB of 2.2 GB").assertIsDisplayed()
        compose.onNodeWithText("Get the librarian · 2.2 GB").assertDoesNotExist()
    }

    @Test fun aStoppedDownloadOffersTryAgain() {
        var gets = 0
        compose.setContent { LibrarianNotice(idle.copy(failed = true)) { gets++ } }
        compose.onNodeWithText("The librarian's download stopped.").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        assertThat(gets).isEqualTo(1)
    }

    @Test fun theChatsSheetListsRecentChatsAndOpensOne() {
        val opened = mutableListOf<Long>()
        val chats = listOf(app.granger.core.library.ChatSummary(3, "how do you grow cassava?", 30), app.granger.core.library.ChatSummary(2, "who wrote 1984?", 20))
        compose.setContent { ChatsSheet(chats, onDismiss = {}) { opened += it } }
        compose.onNodeWithText("Recent chats").assertIsDisplayed()
        compose.onNodeWithText("who wrote 1984?").performClick()
        assertThat(opened).containsExactly(2L)
    }
}
