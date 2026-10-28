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

    @Test fun eachRowsRouteIsTodaysSourceScreen() {
        assertThat(routeOf(GetMoreLink.KIWIX)).isEqualTo(SourceRoute(CatalogScope.KIWIX))
        assertThat(routeOf(GetMoreLink.GRANGER_CLASSICS)).isEqualTo(SourceRoute(CatalogScope.GRANGER))
        assertThat(routeOf(GetMoreLink.ARCHIVE_ORG_BOOKS)).isEqualTo(ArchiveRoute(ArchiveKind.TEXTS))
        assertThat(routeOf(GetMoreLink.LIBRIVOX)).isEqualTo(LibriVoxRoute)
        assertThat(routeOf(GetMoreLink.ARCHIVE_ORG_AUDIOBOOKS)).isEqualTo(ArchiveRoute(ArchiveKind.SPOKEN_WORD))
        assertThat(routeOf(GetMoreLink.ARCHIVE_ORG_MUSIC)).isEqualTo(ArchiveRoute(ArchiveKind.MUSIC))
        assertThat(routeOf(GetMoreLink.MODELS)).isEqualTo(SourceRoute(CatalogScope.MODELS))
    }

    @Test fun theSourcesBelongToTheLibraryButModelsToSettings() {
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = GetMoreRoute) {
                composable<GetMoreRoute> {}
                composable<SourceRoute> {}
            }
        }
        compose.runOnIdle { assertThat(railToolOf(nav.currentDestination!!)).isEqualTo(RailTool.LIBRARY) }
        compose.runOnIdle { nav.navigate(SourceRoute(CatalogScope.KIWIX)) }
        compose.runOnIdle {
            val source = nav.currentDestination!!
            assertThat(railToolOf(source, CatalogScope.KIWIX)).isEqualTo(RailTool.LIBRARY)
            assertThat(railToolOf(source, CatalogScope.GRANGER)).isEqualTo(RailTool.LIBRARY)
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
