package app.granger

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.granger.bookmarks.BookmarksScreen
import app.granger.bookmarks.BookmarksViewModel
import app.granger.downloads.DownloadsScreen
import app.granger.downloads.DownloadsViewModel
import app.granger.library.LibraryScreen
import app.granger.library.LibraryViewModel
import app.granger.readiness.ReadinessScreen
import app.granger.readiness.ReadinessViewModel
import app.granger.reader.ReaderScreen
import app.granger.reader.ReaderViewModel
import app.granger.search.SearchScreen
import app.granger.search.SearchViewModel
import kotlinx.serialization.Serializable

@Serializable object LibraryRoute
@Serializable data class ReaderRoute(val packId: String, val path: String? = null)
@Serializable object SearchRoute
@Serializable object BookmarksRoute
@Serializable object DownloadsRoute
@Serializable object ReadinessRoute

@Composable
fun GrangerNavHost(container: AppContainer) {
    val nav = rememberNavController()
    NavHost(nav, startDestination = LibraryRoute) {
        composable<LibraryRoute> {
            LibraryScreen(
                viewModel = viewModel { LibraryViewModel(container.library) },
                packsFolder = container.packDirs().firstOrNull()?.path.orEmpty(),
                onOpenPack = { nav.navigate(ReaderRoute(it)) },
                onSearch = { nav.navigate(SearchRoute) },
                onBookmarks = { nav.navigate(BookmarksRoute) },
                onDownloads = { nav.navigate(DownloadsRoute) },
                onReadiness = { nav.navigate(ReadinessRoute) },
            )
        }
        composable<ReaderRoute> { entry ->
            val route = entry.toRoute<ReaderRoute>()
            ReaderScreen(
                viewModel = viewModel { ReaderViewModel(route.packId, route.path, container.library, container.appScope) },
                resolver = container.resolver,
                onBack = { nav.popBackStack() },
                onSearch = { nav.navigate(SearchRoute) },
            )
        }
        composable<SearchRoute> {
            SearchScreen(
                viewModel = viewModel { SearchViewModel(container.search) },
                onOpen = { hit -> nav.navigate(ReaderRoute(hit.packId, hit.path)) },
                onBack = { nav.popBackStack() },
            )
        }
        composable<BookmarksRoute> {
            BookmarksScreen(
                viewModel = viewModel { BookmarksViewModel(container.library, container.appScope) },
                onOpen = { bookmark -> nav.navigate(ReaderRoute(bookmark.packId, bookmark.path)) },
                onBack = { nav.popBackStack() },
            )
        }
        composable<DownloadsRoute> {
            DownloadsScreen(
                viewModel = viewModel {
                    DownloadsViewModel(
                        container.catalog, container.downloads, container.library, container.volumes,
                        container.downloadPrefs, container.deviceTier, container.appScope,
                    )
                },
                onBack = { nav.popBackStack() },
            )
        }
        composable<ReadinessRoute> {
            ReadinessScreen(
                viewModel = viewModel {
                    ReadinessViewModel(
                        container.downloads, container.library, container.volumes,
                        container.deviceTier, container.totalRamBytes, container.appScope,
                    )
                },
                onBack = { nav.popBackStack() },
            )
        }
    }
}
