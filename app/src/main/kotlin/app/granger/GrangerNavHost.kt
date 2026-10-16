package app.granger

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.granger.library.LibraryScreen
import app.granger.library.LibraryViewModel
import app.granger.reader.ReaderScreen
import app.granger.reader.ReaderViewModel
import kotlinx.serialization.Serializable

@Serializable object LibraryRoute
@Serializable data class ReaderRoute(val packId: String, val path: String? = null)

@Composable
fun GrangerNavHost(container: AppContainer) {
    val nav = rememberNavController()
    NavHost(nav, startDestination = LibraryRoute) {
        composable<LibraryRoute> {
            LibraryScreen(
                viewModel = viewModel { LibraryViewModel(container.library) },
                packsFolder = container.packDirs().firstOrNull()?.path.orEmpty(),
                onOpenPack = { nav.navigate(ReaderRoute(it)) },
                onSearch = {}, // Task 10
                onBookmarks = {}, // Task 11
            )
        }
        composable<ReaderRoute> { entry ->
            val route = entry.toRoute<ReaderRoute>()
            ReaderScreen(
                viewModel = viewModel { ReaderViewModel(route.packId, route.path, container.library, container.appScope) },
                resolver = container.resolver,
                onBack = { nav.popBackStack() },
                onSearch = {}, // Task 10
            )
        }
    }
}
