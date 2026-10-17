package app.granger

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
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
import app.granger.settings.SettingsScreen
import app.granger.settings.SettingsViewModel
import app.granger.sources.CatalogScope
import app.granger.sources.SourceScreen
import app.granger.sources.SourceViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** [notice] is shown once as a snackbar, e.g. why the app didn't resume reading. */
@Serializable data class LibraryRoute(val notice: String? = null)
@Serializable data class CollectionRoute(val collectionId: String)
@Serializable data class ReaderRoute(val tabId: Long)
@Serializable data class SearchRoute(val query: String = "")
@Serializable object HistoryRoute
@Serializable data class SourceRoute(val scope: CatalogScope)
@Serializable object SettingsRoute
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
    // Non-reader screens always show the rail; the reader shows it only with its chrome (a middle tap).
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

    val selected = when {
        destination == null -> null
        destination.hasRoute<LibraryRoute>() || destination.hasRoute<CollectionRoute>() -> RailTool.LIBRARY
        destination.hasRoute<SearchRoute>() -> RailTool.SEARCH
        destination.hasRoute<SettingsRoute>() || destination.hasRoute<ReadinessRoute>() ||
            destination.hasRoute<SourceRoute>() -> RailTool.SETTINGS
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
                RailTool.SETTINGS -> nav.navigate(SettingsRoute) { launchSingleTop = true }
                RailTool.INQUIRE, RailTool.PLAYER -> Unit // Hidden behind Features flags.
            }
        },
        onTab = ::showTab,
        onCloseTab = railViewModel::closeTab,
        onHistory = { nav.navigate(HistoryRoute) { launchSingleTop = true } },
        onRecentSearch = { query -> nav.navigate(SearchRoute(query)) { popUpTo<SearchRoute> { inclusive = true } } },
        // Over the page, so a middle tap doesn't narrow it and reflow the text.
        railOverContent = readerTabId != null,
    ) {
        NavHost(nav, startDestination = LibraryRoute((start as? StartScreen.Library)?.notice)) {
            composable<LibraryRoute> { backStackEntry ->
                LibraryScreen(
                    viewModel = viewModel { LibraryViewModel(container.shelves, container.library) },
                    packsFolder = container.packDirs().firstOrNull()?.path.orEmpty(),
                    notice = backStackEntry.toRoute<LibraryRoute>().notice,
                    onOpenCollection = { id -> nav.navigate(CollectionRoute(id)) },
                    onOpenSource = { source -> nav.navigate(SourceRoute(source)) },
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
                val tabId = backStackEntry.toRoute<ReaderRoute>().tabId
                ReaderScreen(
                    viewModel = viewModel { ReaderViewModel(tabId, container.library, container.sessions, container.appScope) },
                    resolver = container.resolver,
                    onChromeVisibleChange = { visible -> shell.railVisible = visible },
                    onSearchText = { text -> nav.navigate(SearchRoute(text)) },
                    onBack = { nav.popBackStack() },
                )
            }
            composable<SearchRoute> { backStackEntry ->
                val query = backStackEntry.toRoute<SearchRoute>().query
                SearchScreen(
                    viewModel = viewModel { SearchViewModel(container.search, container.sessions, query) },
                    onOpenTab = ::showTab,
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
            composable<SourceRoute> { backStackEntry ->
                val source = backStackEntry.toRoute<SourceRoute>().scope
                SourceScreen(
                    viewModel = viewModel {
                        SourceViewModel(
                            source, container.catalog, container.downloads, container.library, container.volumes,
                            container.downloadPrefs, container.deviceTier, container.appScope,
                        )
                    },
                    title = source.title,
                    onBack = { nav.popBackStack() },
                )
            }
            composable<SettingsRoute> {
                SettingsScreen(
                    viewModel = viewModel {
                        SettingsViewModel(container.downloads, container.volumes, container.downloadPrefs, container.appScope)
                    },
                    onModels = { nav.navigate(SourceRoute(CatalogScope.MODELS)) },
                    onReadiness = { nav.navigate(ReadinessRoute) },
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
