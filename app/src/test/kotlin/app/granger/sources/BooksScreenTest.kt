package app.granger.sources

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasScrollAction
import app.granger.FakeBookshelf
import app.granger.FakeCatalogSource
import app.granger.FakeDownloads
import app.granger.FakeLibrary
import app.granger.FakePrefs
import app.granger.FakeVolumes
import app.granger.bookEntry
import app.granger.catalogOf
import app.granger.core.ai.DeviceTier
import app.granger.core.catalog.CatalogState
import app.granger.core.catalog.Preset
import app.granger.packEntry
import app.granger.theme.GrangerTheme
import app.granger.volume
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Plan KB2 Task 4.1: Get more → Books by kind, in Explore's Sources style. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h2000dp")
class BooksScreenTest {
    @get:Rule val compose = createComposeRule()

    private val music = packEntry("gutenberg_en_lcc-m", category = "Literature & Drama").copy(
        title = "Music and books on music · Project Gutenberg", family = "gutenberg", zimName = "gutenberg_en_lcc-m", content = "books", shelf = "literature",
    )
    private val military = packEntry("irp.fas.org_en_military-medicine", category = "Medical").copy(
        title = "Military Medicine", family = "irp.fas.org", zimName = "irp.fas.org_en_military-medicine", content = "books", shelf = "manuals",
    )
    private val budding = packEntry("prunelle_en_budding-authors", category = "Literature & Drama").copy(
        title = "Budding authors", family = "prunelle", zimName = "prunelle_en_budding-authors", content = "books", shelf = "children",
    )
    private val wiki = packEntry("wikipedia_en_top", category = "Reference")
    private val hamlet = bookEntry("hamlet").copy(title = "Hamlet", author = "William Shakespeare")

    private fun vm(): SourceViewModel {
        val catalog = FakeCatalogSource()
        catalog.mutable.value = CatalogState(
            catalog = catalogOf(
                listOf(music, military, budding, wiki), books = listOf(hamlet),
                presets = listOf(Preset("granger-classics", "Granger Classics: all books", listOf(hamlet.id))),
            ),
        )
        return SourceViewModel(
            CatalogScope.BOOKS, catalog, FakeDownloads(), FakeLibrary(), FakeBookshelf(), FakeVolumes(listOf(volume("/internal", "Phone"))), FakePrefs(),
            DeviceTier.ASK, CoroutineScope(Dispatchers.Main), io = Dispatchers.Main,
        )
    }

    @Test fun theShelvesAreHeadingsAndTheirRowsOpenTheirLists() {
        val opened = mutableListOf<CatalogScope>()
        var archive = 0
        var imports = 0
        compose.setContent {
            GrangerTheme(night = false) {
                BooksScreen(vm(), onBack = {}, onOpenScope = { opened += it }, onOpenPack = {}, onOpenBook = { _, _ -> }, onArchive = { archive++ }, onImport = { imports++ })
            }
        }
        val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
        for (shelf in listOf("Literature", "Children's books", "Manuals & references")) {
            compose.onNode(hasText(shelf) and heading).assertIsDisplayed()
        }
        // No Learning shelf: nothing is on it.
        compose.onNodeWithText("Learning").assertDoesNotExist()
        compose.onNodeWithText("Granger Classics").performClick()
        compose.onNodeWithText("Project Gutenberg").performClick()
        compose.onNodeWithText("1 subject · From Kiwix").assertIsDisplayed()
        assertThat(opened).containsExactly(CatalogScope.GRANGER, CatalogScope.GUTENBERG).inOrder()
        // A Kiwix book pack is its own row, its provider on its meta line, with Get.
        compose.onNodeWithText("Military Medicine").assertIsDisplayed()
        compose.onNode(hasText("From Kiwix · irp.fas.org", substring = true)).assertIsDisplayed()
        compose.onNode(hasText("From Kiwix · Prunelle", substring = true)).assertIsDisplayed()
        // A website isn't a book.
        compose.onNodeWithText(wiki.title).assertDoesNotExist()
        compose.onNode(hasText("Find a book") and heading).assertIsDisplayed()
        compose.onNodeWithText("Archive.org books").performClick()
        compose.onNodeWithText("Import your own").performClick()
        assertThat(archive).isEqualTo(1)
        assertThat(imports).isEqualTo(1)
    }

    @Test fun searchAllBooksFindsTitlesInEverySource() {
        compose.setContent {
            GrangerTheme(night = false) {
                BooksScreen(vm(), onBack = {}, onOpenScope = {}, onOpenPack = {}, onOpenBook = { _, _ -> }, onArchive = {})
            }
        }
        compose.onNodeWithText("Import your own").assertDoesNotExist()
        compose.onNodeWithText("Search all books").performClick()
        compose.onNodeWithTag(BooksTags.SEARCH_FIELD).performTextInput("hamlet")
        compose.waitForIdle()
        compose.onNodeWithText("Hamlet").assertIsDisplayed()
        compose.onNodeWithText("Literature").assertDoesNotExist()
        compose.onNodeWithTag(BooksTags.SEARCH_FIELD).performTextInput(" zzz")
        compose.waitForIdle()
        compose.onNodeWithText("Nothing matches that search.").assertIsDisplayed()
    }
}
