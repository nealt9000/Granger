package app.granger.library

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import app.granger.FakeBookshelf
import app.granger.FakeDownloads
import app.granger.FakeLibrary
import app.granger.FakeReadingSessions
import app.granger.FakeShelves
import app.granger.book
import app.granger.core.library.BookOrigin
import app.granger.core.library.CollectionRow
import app.granger.core.library.MediaType
import app.granger.core.library.Shelf
import app.granger.core.library.ShelfCollection
import app.granger.library.shelf.LibraryViewPrefs
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Plan GB Task 3: a Gutenberg pack's books as a collection, with what's searchable shown. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp")
class GutenbergCollectionScreenTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val shelves = FakeShelves()
    private val library = FakeLibrary()
    private val music = app.granger.pack("u-m", "Music and books on music · Project Gutenberg")
        .copy(name = "gutenberg_en_lcc-m", description = "Music and books on music")

    private fun gutenbergBook(id: Int, title: String, author: String, indexed: Boolean = false) =
        book("book:gutenberg:$id", title, author = author).copy(
            origin = BookOrigin.GUTENBERG, sourceRef = "gutenberg_en_lcc-m/$title.$id.epub",
            filePath = File(tmp.root, "gutenberg/$id.epub").absolutePath, sizeBytes = 2_000_000L,
            indexedAt = if (indexed) 5L else null,
        )

    private val copiedOut = mutableListOf<String>()

    private fun open() {
        val books = listOf(
            gutenbergBook(1, "Beethoven", "Ludwig Nohl", indexed = true),
            gutenbergBook(2, "The Art of Fugue", "Anon"),
            gutenbergBook(3, "Chopin and Other Musical Essays", "Henry T. Finck"),
        )
        library.packList.value = listOf(music)
        shelves.shelfList.value = listOf(
            Shelf(
                MediaType("literature", "Literature", true, 0),
                listOf(CollectionRow(ShelfCollection("c:gutenberg-m", "Music and books on music", "literature", false, defaultKey = "gutenberg-m"), emptyList(), books)),
            ),
        )
        val shelf = GutenbergShelf(
            restore = { copiedOut += it.id; true }, indexPending = {}, dir = { File(tmp.root, "gutenberg") },
            scope = CoroutineScope(Dispatchers.Main),
        )
        val vm = CollectionViewModel(
            "c:gutenberg-m", shelves, library, FakeReadingSessions(), FakeBookshelf(), FakeDownloads(), CoroutineScope(Dispatchers.Main),
            viewPrefs = LibraryViewPrefs(), compute = Dispatchers.Main, gutenberg = shelf,
        )
        compose.setContent { CollectionScreen(vm, onOpened = {}, onOpenPack = {}, onGetBooks = {}, onOpenCollection = {}, onBack = {}) }
        compose.waitForIdle()
    }

    @Test fun calledByItsSubjectWithTheProviderAndCountsUnderIt() {
        open()
        compose.onNodeWithText("Music and books on music").assertIsDisplayed()
        compose.onNodeWithContentDescription("Project Gutenberg · 3 books · 1 searchable in full").assertIsDisplayed()
    }

    @Test fun onlyTheIndexedBookCarriesTheFullTextMark() {
        open()
        assertThat(compose.onAllNodesWithContentDescription("Searchable in full").fetchSemanticsNodes()).hasSize(1)
    }

    @Test fun aBooksMenuSaysWhetherItIsSearchableAndOffersNoRemove() {
        open()
        compose.onNodeWithText("The Art of Fugue").performTouchInput { longClick() }
        compose.onNodeWithText("Titles only until you open it").assertIsDisplayed()
        compose.onNodeWithText("Move to collection…").assertIsDisplayed()
        assertThat(compose.onAllNodesWithContentDescription("Remove from phone").fetchSemanticsNodes()).isEmpty()
        compose.onNodeWithText("Remove from phone").assertDoesNotExist()
    }

    @Test fun makeAllSearchableGivesTheSizeFirstAndQueuesTheWork() {
        open()
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Open the pack's own pages").assertIsDisplayed()
        compose.onNodeWithText("Remove pack").assertIsDisplayed()
        compose.onNodeWithText("Make all searchable…").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Make all searchable?").assertIsDisplayed()
        // All three books are still in the pack: 6 MB more.
        compose.onNode(androidx.compose.ui.test.hasText("about 6.0 MB more", substring = true)).assertIsDisplayed()
        assertThat(copiedOut).isEmpty()
        compose.onNodeWithText("Make searchable").performClick()
        compose.waitForIdle()
        assertThat(copiedOut).containsExactly("book:gutenberg:1", "book:gutenberg:2", "book:gutenberg:3")
    }
}
