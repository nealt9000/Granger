package app.granger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.granger.core.library.ReadingTab
import app.granger.downloads.DownloadsScreen
import app.granger.downloads.DownloadsViewModel
import app.granger.launch.StartScreen
import app.granger.library.CollectionScreen
import app.granger.library.CollectionViewModel
import app.granger.library.LibraryScreen
import app.granger.library.LibraryViewModel
import app.granger.readiness.ReadinessScreen
import app.granger.readiness.ReadinessViewModel
import app.granger.reader.ReaderScreen
import app.granger.reader.ReaderViewModel
import app.granger.search.SearchScreen
import app.granger.search.SearchViewModel
import app.granger.shell.GrangerShell
import app.granger.shell.HistoryScreen
import app.granger.shell.HistoryViewModel
import app.granger.shell.RailTool
import app.granger.shell.RailViewModel
import app.granger.shell.rememberShellState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** [notice] is shown once as a snackbar, e.g. why the app didn't resume reading. */
@Serializable data class LibraryRoute(val notice: String? = null)
@Serializable data class CollectionRoute(val collectionId: String)
@Serializable data class ReaderRoute(val tabId: Long)
@Serializable data class SearchRoute(val query: String = "")
@Serializable object HistoryRoute
@Serializable object DownloadsRoute
@Serializable object ReadinessRoute

/** The tab the reader shows; lives as long as the activity's view models so the rail's flow survives rotation. */
private class CurrentTab : ViewModel() {
    val id = MutableStateFlow<Long?>(null)
}

@Composable
fun GrangerNavHost(container: AppContainer, start: StartScreen) {
    val nav = rememberNavController()
    val shell = rememberShellState()
    val scope = rememberCoroutineScope()
    val currentTab = viewModel { CurrentTab() }
    val railViewModel = viewModel { RailViewModel(container.sessions, container.library, currentTab.id) }
    val rail by railViewModel.ui.collectAsStateWithLifecycle()

    val entry by nav.currentBackStackEntryAsState()
    val destination = entry?.destination
    val readerTabId = entry?.takeIf { destination?.hasRoute<ReaderRoute>() == true }?.toRoute<ReaderRoute>()?.tabId
    LaunchedEffect(readerTabId) { currentTab.id.value = readerTabId }
    // Non-reader screens always show the rail; the reader keeps it too until it goes immersive (Task 9).
    LaunchedEffect(destination) { if (destination?.hasRoute<ReaderRoute>() != true) shell.railVisible = true }

    // Resuming puts the reader on top of the Library, so Back from it lands there. Once per launch, not per rotation.
    var resumeHandled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!resumeHandled) {
            resumeHandled = true
            if (start is StartScreen.Reader) nav.navigate(ReaderRoute(start.tabId))
        }
    }

    fun showTab(tabId: Long) {
        // Read the back stack now, not the composition's copy: a tap's openTab suspends, and a second tap
        // meanwhile must replace the reader the first one pushed.
        val top = nav.currentBackStackEntry
        val showing = top?.takeIf { it.destination.hasRoute<ReaderRoute>() }?.toRoute<ReaderRoute>()?.tabId
        if (tabId == showing) return
        nav.navigate(ReaderRoute(tabId)) {
            // Switching tabs replaces the reader instead of stacking one per tab.
            if (showing != null) popUpTo<ReaderRoute> { inclusive = true }
        }
    }

    /** Opening from Library, Search or History adds a rail tab (or reuses the one showing that article). */
    fun openArticle(packId: String, path: String, title: String) {
        scope.launch { showTab(container.sessions.openTab(packId, path, title)) }
    }

    val selected = when {
        destination == null -> null
        destination.hasRoute<LibraryRoute>() || destination.hasRoute<CollectionRoute>() -> RailTool.LIBRARY
        destination.hasRoute<SearchRoute>() -> RailTool.SEARCH
        destination.hasRoute<DownloadsRoute>() -> RailTool.SETTINGS
        else -> null
    }

    GrangerShell(
        rail = rail,
        expanded = shell.expanded,
        railVisible = shell.railVisible,
        selected = selected,
        onToggleExpanded = { shell.expanded = !shell.expanded },
        onTool = { tool ->
            when (tool) {
                RailTool.SEARCH -> nav.navigate(SearchRoute()) { launchSingleTop = true }
                RailTool.LIBRARY -> if (!nav.popBackStack<LibraryRoute>(inclusive = false)) nav.navigate(LibraryRoute())
                // Until Settings exists (Task 8), the Settings tool opens Downloads.
                RailTool.SETTINGS -> nav.navigate(DownloadsRoute) { launchSingleTop = true }
                RailTool.INQUIRE, RailTool.PLAYER -> Unit // Hidden behind Features flags.
            }
        },
        onTab = ::showTab,
        onCloseTab = railViewModel::closeTab,
        onHistory = { nav.navigate(HistoryRoute) { launchSingleTop = true } },
        onRecentSearch = { query -> nav.navigate(SearchRoute(query)) { popUpTo<SearchRoute> { inclusive = true } } },
    ) {
        NavHost(nav, startDestination = LibraryRoute((start as? StartScreen.Library)?.notice)) {
            composable<LibraryRoute> { backStackEntry ->
                LibraryScreen(
                    viewModel = viewModel { LibraryViewModel(container.shelves, container.library) },
                    packsFolder = container.packDirs().firstOrNull()?.path.orEmpty(),
                    notice = backStackEntry.toRoute<LibraryRoute>().notice,
                    onOpenCollection = { id -> nav.navigate(CollectionRoute(id)) },
                    // Until Task 8 builds the source screens, both rows open Downloads.
                    onOpenSource = { nav.navigate(DownloadsRoute) },
                    onSearch = { nav.navigate(SearchRoute()) },
                    onHistory = { nav.navigate(HistoryRoute) },
                    onReadiness = { nav.navigate(ReadinessRoute) },
                )
            }
            composable<CollectionRoute> { backStackEntry ->
                CollectionScreen(
                    viewModel = viewModel {
                        CollectionViewModel(
                            backStackEntry.toRoute<CollectionRoute>().collectionId,
                            container.shelves, container.library, container.sessions,
                        )
                    },
                    onOpened = ::showTab,
                    onBack = { nav.popBackStack() },
                )
            }
            composable<ReaderRoute> { backStackEntry ->
                ReaderDestination(
                    container = container,
                    tabId = backStackEntry.toRoute<ReaderRoute>().tabId,
                    onBack = { nav.popBackStack() },
                    onSearch = { nav.navigate(SearchRoute()) },
                )
            }
            composable<SearchRoute> { backStackEntry ->
                val query = backStackEntry.toRoute<SearchRoute>().query
                SearchScreen(
                    // Task 10 makes the initial query a SearchViewModel parameter.
                    viewModel = viewModel { SearchViewModel(container.search).apply { if (query.isNotBlank()) onQueryChange(query) } },
                    onOpen = { hit -> openArticle(hit.packId, hit.path, hit.title) },
                    onBack = { nav.popBackStack() },
                )
            }
            composable<HistoryRoute> {
                HistoryScreen(
                    viewModel = viewModel { HistoryViewModel(container.sessions, container.library) },
                    onOpenTab = ::showTab,
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
}

/** Looks the tab up, then shows today's reader on its article. Task 9 moves the lookup into ReaderViewModel. */
@Composable
private fun ReaderDestination(container: AppContainer, tabId: Long, onBack: () -> Unit, onSearch: () -> Unit) {
    var tab by remember { mutableStateOf<ReadingTab?>(null) }
    var gone by remember { mutableStateOf(false) }
    LaunchedEffect(tabId) {
        val found = container.sessions.tab(tabId)
        if (found == null) gone = true else tab = found
    }
    val shown = tab
    when {
        gone -> Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("This bookmark was removed.")
            Button(onClick = onBack) { Text("Back") }
        }
        shown != null -> ReaderScreen(
            viewModel = viewModel {
                // An empty path (a pack opened from the Library with no main page) falls back to the pack's start.
                ReaderViewModel(shown.packId, shown.path.ifEmpty { null }, container.library, container.appScope)
            },
            resolver = container.resolver,
            onBack = onBack,
            onSearch = onSearch,
        )
    }
}
