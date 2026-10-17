package app.granger

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.granger.core.library.BookIds
import app.granger.ebook.EbookReaderScreen
import app.granger.ebook.EbookReaderViewModel
import app.granger.core.library.ReadingTab
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

/** An e-book; [href] and [progression] ask the reader to open there (a search hit) instead of where it left off. */
@Serializable data class BookRoute(val tabId: Long, val href: String? = null, val progression: Double? = null)

/** A place in a book to open at. */
data class BookTarget(val href: String, val progression: Double)

private fun NavDestination.isReading() = hasRoute<ReaderRoute>() || hasRoute<BookRoute>()

private fun NavBackStackEntry.readingTabId(): Long? = when {
    destination.hasRoute<ReaderRoute>() -> toRoute<ReaderRoute>().tabId
    destination.hasRoute<BookRoute>() -> toRoute<BookRoute>().tabId
    else -> null
}

/**
 * The one way to open a reading tab: a book goes to [BookRoute], an article to [ReaderRoute]. A reader already on top
 * is replaced rather than stacked, and showing the same tab again does nothing unless [at] asks for a place in it.
 */
fun NavController.showTab(tab: ReadingTab, at: BookTarget? = null) {
    // Read the back stack now, not the composition's copy: opening a tab suspends, and a second tap
    // meanwhile must replace the reader the first one pushed.
    val top = currentBackStackEntry
    val showing = top?.readingTabId()
    if (tab.id == showing && at == null) return
    val route: Any =
        if (BookIds.isBook(tab.packId)) BookRoute(tab.id, at?.href, at?.progression) else ReaderRoute(tab.id)
    navigate(route) {
        // Switching tabs replaces the reader instead of stacking one per tab.
        if (showing != null && top != null) popUpTo(top.destination.id) { inclusive = true }
    }
}

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
    // True only for the first resume navigation; read when that navigation's transitions are chosen.
    var skipTransition by remember { mutableStateOf(false) }
    var showChromeRequest by rememberSaveable { mutableStateOf(0) }
    val currentTab = viewModel { CurrentTab() }
    val railViewModel = viewModel { RailViewModel(container.sessions, container.library, container.bookshelf, currentTab.id) }
    val rail by railViewModel.ui.collectAsStateWithLifecycle()

    val entry by nav.currentBackStackEntryAsState()
    val destination = entry?.destination
    val readerTabId = entry?.readingTabId()
    LaunchedEffect(readerTabId) { currentTab.id.value = readerTabId }
    // Non-reader screens always show the rail; the reader shows it only with its chrome (a middle tap).
    LaunchedEffect(destination) { if (destination?.isReading() != true) shell.railVisible = true else skipTransition = false }

    // Resuming puts the reader on top of the Library, so Back from it lands there. Once per launch, not per rotation.
    var resumeHandled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!resumeHandled) {
            resumeHandled = true
            if (start is StartScreen.Reader) {
                skipTransition = true
                nav.navigate(if (start.book) BookRoute(start.tabId) else ReaderRoute(start.tabId))
            }
        }
    }

    // Every screen that opens a tab hands over its id; the tab decides which reader shows it.
    val scope = rememberCoroutineScope()
    fun showTab(tabId: Long) {
        scope.launch { container.sessions.tab(tabId)?.let { nav.showTab(it) } }
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
        onEdgeSwipe = {
            // Immersive reader: the swipe brings the chrome (and with it the rail) up; elsewhere it opens the rail.
            if (readerTabId != null && !shell.railVisible) showChromeRequest++ else shell.expanded = !shell.expanded
        },
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
        onRecentSearch = { query ->
            // Replace a Search that is on top; otherwise push, so Back returns to the article it was opened from.
            val searchOnTop = nav.currentBackStackEntry?.destination?.hasRoute<SearchRoute>() == true
            nav.navigate(SearchRoute(query)) { if (searchOnTop) popUpTo<SearchRoute> { inclusive = true } }
        },
        // Over the page, so a middle tap doesn't narrow it and reflow the text.
        railOverContent = readerTabId != null,
    ) {
        NavHost(
            nav,
            startDestination = LibraryRoute((start as? StartScreen.Library)?.notice),
            // The first resume goes straight to the book instead of fading the Library into it.
            enterTransition = { if (skipTransition) EnterTransition.None else fadeIn(tween(700)) },
            exitTransition = { if (skipTransition) ExitTransition.None else fadeOut(tween(700)) },
            popEnterTransition = { fadeIn(tween(700)) },
            popExitTransition = { fadeOut(tween(700)) },
        ) {
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
                    showChromeRequest = showChromeRequest,
                    onSearchText = { text -> nav.navigate(SearchRoute(text)) },
                    onBack = { nav.popBackStack() },
                )
            }
            composable<BookRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<BookRoute>()
                val target = route.href?.let { BookTarget(it, route.progression ?: 0.0) }
                EbookReaderScreen(
                    viewModel = viewModel {
                        EbookReaderViewModel(
                            route.tabId, target, container.bookshelf, container.sessions, container.bookOpener,
                            container.readingPrefs, container.appScope,
                        )
                    },
                    onChromeVisibleChange = { visible -> shell.railVisible = visible },
                    showChromeRequest = showChromeRequest,
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
                    viewModel = viewModel { HistoryViewModel(container.sessions, container.library, container.bookshelf) },
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
