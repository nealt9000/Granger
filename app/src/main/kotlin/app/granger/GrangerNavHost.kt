package app.granger

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.granger.library.LibraryScreen
import app.granger.library.LibraryViewModel
import kotlinx.serialization.Serializable

@Serializable object LibraryRoute

@Composable
fun GrangerNavHost(container: AppContainer) {
    val nav = rememberNavController()
    NavHost(nav, startDestination = LibraryRoute) {
        composable<LibraryRoute> {
            LibraryScreen(
                viewModel = viewModel { LibraryViewModel(container.library) },
                packsFolder = container.packDirs().firstOrNull()?.path.orEmpty(),
                onOpenPack = {}, // Task 9
                onSearch = {}, // Task 10
                onBookmarks = {}, // Task 11
            )
        }
    }
}
