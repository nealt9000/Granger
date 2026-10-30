package app.granger

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.granger.core.archive.ArchiveKind
import app.granger.sources.CatalogScope
import app.granger.shell.RailTool
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Browse lights for its home, a pack being read and Kiwix; a book being read lights the Library (Plan U3c Decision 1). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BrowseRoutesTest {
    @get:Rule val compose = createComposeRule()

    @Test fun packsLightBrowseAndBooksLightTheLibrary() {
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = LibraryRoute()) {
                composable<LibraryRoute> {}
                composable<BrowseRoute> {}
                composable<ReaderRoute> {}
                composable<BookRoute> {}
                composable<SourceRoute> {}
                composable<ArchiveRoute> {}
                composable<CollectionRoute> {}
                composable<DownloadsRoute> {}
                composable<ReadinessRoute> {}
                composable<GetMoreRoute> {}
            }
        }
        fun toolAt(route: Any, scope: CatalogScope? = null, archiveKind: ArchiveKind? = null): RailTool? {
            compose.runOnIdle { nav.navigate(route) }
            return compose.runOnIdle { railToolOf(nav.currentDestination!!, scope, archiveKind) }
        }
        assertThat(toolAt(BrowseRoute)).isEqualTo(RailTool.BROWSE)
        assertThat(toolAt(ReaderRoute(1L))).isEqualTo(RailTool.BROWSE)
        assertThat(toolAt(SourceRoute(CatalogScope.KIWIX), CatalogScope.KIWIX)).isEqualTo(RailTool.BROWSE)
        assertThat(toolAt(SourceRoute(CatalogScope.KIWIX, "maps"), CatalogScope.KIWIX)).isEqualTo(RailTool.BROWSE)
        // Books are the Library's, read or got; so are Downloads, Readiness and Get more, which cover everything.
        assertThat(toolAt(BookRoute(2L))).isEqualTo(RailTool.LIBRARY)
        assertThat(toolAt(SourceRoute(CatalogScope.GRANGER), CatalogScope.GRANGER)).isEqualTo(RailTool.LIBRARY)
        assertThat(toolAt(ArchiveRoute(ArchiveKind.TEXTS), archiveKind = ArchiveKind.TEXTS)).isEqualTo(RailTool.LIBRARY)
        assertThat(toolAt(CollectionRoute("c:gc"))).isEqualTo(RailTool.LIBRARY)
        assertThat(toolAt(DownloadsRoute)).isEqualTo(RailTool.LIBRARY)
        assertThat(toolAt(ReadinessRoute)).isEqualTo(RailTool.LIBRARY)
        assertThat(toolAt(GetMoreRoute())).isEqualTo(RailTool.LIBRARY)
        assertThat(toolAt(LibraryRoute())).isEqualTo(RailTool.LIBRARY)
        // Models stay with Settings.
        assertThat(toolAt(SourceRoute(CatalogScope.MODELS), CatalogScope.MODELS)).isEqualTo(RailTool.SETTINGS)
    }
}
