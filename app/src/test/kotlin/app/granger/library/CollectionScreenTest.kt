package app.granger.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.granger.FakeBookshelf
import app.granger.FakeDownloads
import app.granger.FakeLibrary
import app.granger.FakeReadingSessions
import app.granger.FakeShelves
import app.granger.book
import app.granger.core.library.CollectionRow
import app.granger.core.library.MediaType
import app.granger.core.library.Shelf
import app.granger.core.library.ShelfCollection
import app.granger.library.shelf.LibraryViewPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Plan LV Task 3: a book collection opens as an A–Z list, and remembers the grid. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp")
class CollectionScreenTest {
    @get:Rule val compose = createComposeRule()

    private val shelves = FakeShelves()
    private val prefs = LibraryViewPrefs()

    /** Forty books, The Odyssey among them. */
    private val books = (1..39).map { book("b$it", "Book $it", author = "Author $it") } + book("odyssey", "The Odyssey", author = "Homer")

    init {
        shelves.shelfList.value = listOf(
            Shelf(
                MediaType("lit", "Literature", true, 0),
                listOf(CollectionRow(ShelfCollection("c:gc", "Granger Classics", "lit", false, defaultKey = "gc"), emptyList(), books)),
            ),
        )
    }

    private fun vm() = CollectionViewModel(
        "c:gc", shelves, FakeLibrary(), FakeReadingSessions(), FakeBookshelf(), FakeDownloads(), CoroutineScope(Dispatchers.Main),
        viewPrefs = prefs, compute = Dispatchers.Main,
    )

    private var screen by mutableStateOf<CollectionViewModel?>(null)

    private fun open() {
        if (screen == null) {
            screen = vm()
            compose.setContent { screen?.let { CollectionScreen(it, onOpened = {}, onOpenPack = {}, onGetBooks = {}, onOpenCollection = {}, onBack = {}) } }
        } else {
            // Leave, then come back to a new screen.
            screen = null
            compose.waitForIdle()
            screen = vm()
        }
        compose.waitForIdle()
    }

    private fun header(letter: String) = compose.onNode(hasText(letter) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))

    @Test fun opensAsAnAToZListWithTheStrip() {
        open()
        compose.onNodeWithContentDescription("Show as grid").assertIsDisplayed()
        header("B").assertIsDisplayed()
        compose.onNodeWithText("Find a title or author").assertIsDisplayed()
        compose.onNodeWithContentDescription("Jump to O").assertExists()
    }

    @Test fun theOdysseyFilesUnderO() {
        open()
        compose.onNodeWithContentDescription("Jump to O").performClick()
        header("O").assertIsDisplayed()
        compose.onNodeWithText("The Odyssey").assertIsDisplayed()
    }

    @Test fun theGridSurvivesLeavingAndComingBack() {
        open()
        compose.onNodeWithContentDescription("Show as grid").performClick()
        compose.onNodeWithContentDescription("Show as list").assertIsDisplayed()
        open()
        compose.onNodeWithContentDescription("Show as list").assertIsDisplayed()
        // Still lettered in the grid.
        compose.onNodeWithContentDescription("Jump to O").performClick()
        header("O").assertIsDisplayed()
    }

    @Test fun aSortChangeIsRememberedToo() {
        open()
        compose.onNodeWithText("Author").performClick()
        compose.waitForIdle()
        open()
        // Homer files under H by surname.
        compose.onNodeWithContentDescription("Jump to H").performClick()
        compose.onNodeWithText("The Odyssey").assertIsDisplayed()
    }
}
