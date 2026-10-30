package app.granger.sources

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.granger.ArchiveRoute
import app.granger.GetMoreRoute
import app.granger.LibriVoxRoute
import app.granger.SourceRoute
import app.granger.core.archive.ArchiveKind
import app.granger.railToolOf
import app.granger.routeOf
import app.granger.shell.RailTool
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h1200dp")
class GetMoreScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun theFourSectionsListEverySourceOnce() {
        assertThat(GET_MORE_SECTIONS.map { it.first })
            .containsExactly("Knowledge packs", "Books", "Audiobooks & music", "Models & voices").inOrder()
        assertThat(GET_MORE_SECTIONS.flatMap { it.second }).containsExactlyElementsIn(GetMoreLink.entries)
        // Project Gutenberg is books, after Granger Classics (Plan GB Decision 7); Kiwix libraries after it (Plan KB).
        assertThat(GET_MORE_SECTIONS.single { it.first == "Books" }.second)
            .containsExactly(GetMoreLink.GRANGER_CLASSICS, GetMoreLink.GUTENBERG, GetMoreLink.KIWIX_BOOKS, GetMoreLink.ARCHIVE_ORG_BOOKS).inOrder()
        assertThat(GetMoreLink.KIWIX_BOOKS.title).isEqualTo("Kiwix libraries")
        // Archive.org once per kind, its kind in the name.
        assertThat(GetMoreLink.entries.map { it.title }.filter { it.startsWith("Archive.org") })
            .containsExactly("Archive.org books", "Archive.org audiobooks", "Archive.org music")
    }

    @Test fun eachRowOpensItsSource() {
        val opened = mutableListOf<GetMoreLink>()
        compose.setContent { GetMoreScreen(onOpen = { opened += it }, onBack = {}) }
        compose.onNodeWithText("Knowledge packs").assertIsDisplayed()
        for (link in GetMoreLink.entries) compose.onNodeWithText(link.title).performClick()
        assertThat(opened).containsExactlyElementsIn(GetMoreLink.entries).inOrder()
    }

    /** Plan KB walkthrough 2: the Library's + is books alone, with Import your own; Listen's, audio alone. */
    @Test fun theLibrarysGetMoreOffersOnlyBooksAndListensOnlyAudio() {
        assertThat(getMoreSections(GetMoreFor.BOOKS).flatMap { it.second })
            .containsExactly(GetMoreLink.GRANGER_CLASSICS, GetMoreLink.GUTENBERG, GetMoreLink.KIWIX_BOOKS, GetMoreLink.ARCHIVE_ORG_BOOKS).inOrder()
        assertThat(getMoreSections(GetMoreFor.LISTEN).flatMap { it.second })
            .containsExactly(GetMoreLink.LIBRIVOX, GetMoreLink.ARCHIVE_ORG_AUDIOBOOKS, GetMoreLink.ARCHIVE_ORG_MUSIC).inOrder()
        assertThat(getMoreSections(null)).isEqualTo(GET_MORE_SECTIONS)

        val opened = mutableListOf<GetMoreLink>()
        var imports = 0
        compose.setContent { GetMoreScreen(onOpen = { opened += it }, onBack = {}, only = GetMoreFor.BOOKS, onImport = { imports++ }) }
        compose.onNodeWithText("Get books").assertIsDisplayed()
        for (link in GetMoreLink.entries - getMoreSections(GetMoreFor.BOOKS).flatMap { it.second }.toSet()) {
            compose.onNodeWithText(link.title).assertDoesNotExist()
        }
        compose.onNodeWithText("Knowledge packs").assertDoesNotExist()
        compose.onNodeWithText("Audiobooks & music").assertDoesNotExist()
        for (link in getMoreSections(GetMoreFor.BOOKS).flatMap { it.second }) compose.onNodeWithText(link.title).performClick()
        compose.onNodeWithText(IMPORT_YOUR_OWN).performClick()
        assertThat(opened).containsExactlyElementsIn(getMoreSections(GetMoreFor.BOOKS).flatMap { it.second }).inOrder()
        assertThat(imports).isEqualTo(1)
    }

    @Test fun listensGetMoreHasNoBooksNorImport() {
        compose.setContent { GetMoreScreen(onOpen = {}, onBack = {}, only = GetMoreFor.LISTEN) }
        compose.onNodeWithText("Get audiobooks & music").assertIsDisplayed()
        for (link in getMoreSections(GetMoreFor.LISTEN).flatMap { it.second }) compose.onNodeWithText(link.title).assertIsDisplayed()
        for (link in listOf(GetMoreLink.KIWIX, GetMoreLink.KIWIX_BOOKS, GetMoreLink.GUTENBERG, GetMoreLink.MODELS)) {
            compose.onNodeWithText(link.title).assertDoesNotExist()
        }
        compose.onNodeWithText(IMPORT_YOUR_OWN).assertDoesNotExist()
    }

    @Test fun eachRowsRouteIsTodaysSourceScreen() {
        assertThat(routeOf(GetMoreLink.KIWIX)).isEqualTo(SourceRoute(CatalogScope.KIWIX))
        assertThat(routeOf(GetMoreLink.GRANGER_CLASSICS)).isEqualTo(SourceRoute(CatalogScope.GRANGER))
        assertThat(routeOf(GetMoreLink.GUTENBERG)).isEqualTo(SourceRoute(CatalogScope.GUTENBERG))
        assertThat(routeOf(GetMoreLink.KIWIX_BOOKS)).isEqualTo(SourceRoute(CatalogScope.KIWIX_BOOKS))
        assertThat(routeOf(GetMoreLink.ARCHIVE_ORG_BOOKS)).isEqualTo(ArchiveRoute(ArchiveKind.TEXTS))
        assertThat(routeOf(GetMoreLink.LIBRIVOX)).isEqualTo(LibriVoxRoute)
        assertThat(routeOf(GetMoreLink.ARCHIVE_ORG_AUDIOBOOKS)).isEqualTo(ArchiveRoute(ArchiveKind.SPOKEN_WORD))
        assertThat(routeOf(GetMoreLink.ARCHIVE_ORG_MUSIC)).isEqualTo(ArchiveRoute(ArchiveKind.MUSIC))
        assertThat(routeOf(GetMoreLink.MODELS)).isEqualTo(SourceRoute(CatalogScope.MODELS))
    }

    /** Kiwix's packs are Browse's (Plan U3c Decision 1). */
    @Test fun theSourcesBelongToTheLibraryButKiwixToBrowseAndModelsToSettings() {
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = GetMoreRoute()) {
                composable<GetMoreRoute> {}
                composable<SourceRoute> {}
            }
        }
        compose.runOnIdle { assertThat(railToolOf(nav.currentDestination!!)).isEqualTo(RailTool.LIBRARY) }
        // Listen's Get more is Listen's; the Library's (books) and Downloads' stay the Library's.
        compose.runOnIdle { assertThat(railToolOf(nav.currentDestination!!, getMoreFor = GetMoreFor.LISTEN)).isEqualTo(RailTool.LISTEN) }
        compose.runOnIdle { assertThat(railToolOf(nav.currentDestination!!, getMoreFor = GetMoreFor.BOOKS)).isEqualTo(RailTool.LIBRARY) }
        compose.runOnIdle { nav.navigate(SourceRoute(CatalogScope.KIWIX)) }
        compose.runOnIdle {
            val source = nav.currentDestination!!
            assertThat(railToolOf(source, CatalogScope.KIWIX)).isEqualTo(RailTool.BROWSE)
            assertThat(railToolOf(source, CatalogScope.GRANGER)).isEqualTo(RailTool.LIBRARY)
            assertThat(railToolOf(source, CatalogScope.GUTENBERG)).isEqualTo(RailTool.LIBRARY)
            assertThat(railToolOf(source, CatalogScope.KIWIX_BOOKS)).isEqualTo(RailTool.LIBRARY)
            assertThat(railToolOf(source, CatalogScope.MODELS)).isEqualTo(RailTool.SETTINGS)
        }
    }

    private val installed = ItemRow("med", "Medicine", "eng", "", 1_000, ItemStatus.Installed, packId = "u-med", ask = AskReach.KEYWORDS)

    @Test fun anInstalledPackOpensAndRemoveIsInItsOverflow() {
        val calls = mutableListOf<String>()
        compose.setContent {
            CatalogItemRow(installed, onGet = {}, onCancel = {}, onRetry = {}, onRemove = { calls += "remove" }, onOpen = { calls += "open" })
        }
        compose.onNodeWithText("Ask: keywords only").assertIsDisplayed()
        compose.onNodeWithText("Open").performClick()
        compose.onNodeWithText("Remove").assertDoesNotExist()
        compose.onNodeWithContentDescription("More for Medicine").performClick()
        compose.onNodeWithText("Remove").performClick()
        assertThat(calls).containsExactly("open", "remove").inOrder()
    }

    @Test fun anUpdateOffersOpenAndUpdate() {
        val calls = mutableListOf<String>()
        compose.setContent {
            CatalogItemRow(
                installed.copy(status = ItemStatus.UpdateAvailable),
                onGet = { calls += "get" }, onCancel = {}, onRetry = {}, onRemove = { calls += "remove" }, onOpen = { calls += "open" },
            )
        }
        compose.onNodeWithText("Open").performClick()
        compose.onNodeWithText("Update").performClick()
        compose.onNodeWithContentDescription("More for Medicine").assertIsDisplayed()
        assertThat(calls).containsExactly("open", "get").inOrder()
    }

    @Test fun anInstalledItemThatIsntAPackKeepsRemove() {
        val calls = mutableListOf<String>()
        compose.setContent {
            CatalogItemRow(installed.copy(packId = null, ask = null), onGet = {}, onCancel = {}, onRetry = {}, onRemove = { calls += "remove" })
        }
        compose.onNodeWithText("Open").assertDoesNotExist()
        compose.onNodeWithText("Remove").performClick()
        assertThat(calls).containsExactly("remove")
    }
}
