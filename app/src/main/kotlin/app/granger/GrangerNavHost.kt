package app.granger

import android.util.Log
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
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
import app.granger.archive.ArchiveScreen
import app.granger.archive.ArchiveViewModel
import app.granger.core.library.BookIds
import app.granger.core.library.ReadingTab
import app.granger.ebook.Dictionary
import app.granger.ebook.DictionaryChoice
import app.granger.ebook.EbookReaderScreen
import app.granger.ebook.EbookReaderViewModel
import app.granger.ebook.SelectionBridge
import app.granger.launch.StartScreen
import app.granger.library.CollectionScreen
import app.granger.library.CollectionViewModel
import app.granger.library.ImportRunner
import app.granger.library.LibraryScreen
import app.granger.library.displayNameOf
import app.granger.library.LibraryViewModel
import app.granger.library.SourceLink
import app.granger.reader.ReaderScreen
import app.granger.reader.ReaderViewModel
import app.granger.readiness.ReadinessScreen
import app.granger.readiness.ReadinessViewModel
import app.granger.search.SearchScreen
import app.granger.search.SearchViewModel
import app.granger.settings.SettingsScreen
import app.granger.settings.SettingsViewModel
import app.granger.shell.GrangerShell
import app.granger.shell.HistoryScreen
import app.granger.shell.HistoryViewModel
import app.granger.shell.RailTool
import app.granger.shell.RailViewModel
import app.granger.shell.rememberShellState
import app.granger.sources.CatalogScope
import app.granger.sources.SourceScreen
import app.granger.sources.SourceViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import app.granger.core.books.TextQuote
import app.granger.core.ai.DeviceTier
import app.granger.core.research.Passage
import app.granger.core.research.SourceKind
import app.granger.research.CitationTarget
import app.granger.research.ResearchScreen
import app.granger.research.ResearchViewModel

/** [notice] is shown once as a snackbar, e.g. why the app didn't resume reading. */
@Serializable data class LibraryRoute(val notice: String? = null)
@Serializable data class CollectionRoute(val collectionId: String)
/** An article; [fragment] scrolls its first page to that section (a cited passage's heading). */
@Serializable data class ReaderRoute(val tabId: Long, val fragment: String? = null)

/**
 * An e-book; [href] and [progression] ask the reader to open there (a search hit) instead of where it left off.
 * [highlight], with [before] and [after] around it, is the hit's text, which the reader finds on the page and marks.
 */
@Serializable data class BookRoute(
    val tabId: Long,
    val href: String? = null,
    val progression: Double? = null,
    val highlight: String? = null,
    val before: String? = null,
    val after: String? = null,
) {
    /** The place this route asks for, if any. */
    fun target(): BookTarget? = href?.let { h ->
        BookTarget(h, progression ?: 0.0, highlight?.takeIf { it.isNotBlank() }?.let { TextQuote(before.orEmpty(), it, after.orEmpty()) })
    }
}

/** A place in a book to open at; [quote] is the text there to find and mark, which beats [progression] when found. */
data class BookTarget(val href: String, val progression: Double, val quote: TextQuote? = null)

private fun NavDestination.isReading() = hasRoute<ReaderRoute>() || hasRoute<BookRoute>()

private fun NavBackStackEntry.readingTabId(): Long? = when {
    destination.hasRoute<ReaderRoute>() -> toRoute<ReaderRoute>().tabId
    destination.hasRoute<BookRoute>() -> toRoute<BookRoute>().tabId
    else -> null
}

/**
 * The one way to open a reading tab: a book goes to [BookRoute], an article to [ReaderRoute]. A reader already on top
 * is replaced rather than stacked ([keepCurrent] stacks instead), and showing the same tab again does nothing unless
 * [at] (a book) or [fragment] (an article's section) asks for a place in it.
 */
fun NavController.showTab(tab: ReadingTab, at: BookTarget? = null, keepCurrent: Boolean = false, fragment: String? = null) {
    // Read the back stack now, not the composition's copy: opening a tab suspends, and a second tap
    // meanwhile must replace the reader the first one pushed.
    val top = currentBackStackEntry
    val showing = top?.readingTabId()
    if (tab.id == showing && at == null && fragment == null) return
    val route: Any =
        if (BookIds.isBook(tab.packId)) {
            BookRoute(tab.id, at?.href, at?.progression, at?.quote?.highlight, at?.quote?.before, at?.quote?.after)
        } else {
            ReaderRoute(tab.id, fragment)
        }
    navigate(route) {
        // Switching tabs replaces the reader instead of stacking one per tab.
        // keepCurrent stacks it on the reader instead (a dictionary entry over the book), so Back returns to the page.
        if (showing != null && top != null && !keepCurrent) popUpTo(top.destination.id) { inclusive = true }
    }
}

@Serializable data class SearchRoute(val query: String = "")
@Serializable object HistoryRoute
@Serializable data class SourceRoute(val scope: CatalogScope)
@Serializable object ArchiveRoute
@Serializable object SettingsRoute
@Serializable object ReadinessRoute

/**
 * A Research chat: [chatId] opens a kept one, 0 starts a new one. [quote] is a reader's selection, from [quoteSourceId]
 * (a pack or book id) titled [quoteTitle], at [quoteLocation] (an entry path or a book href) and [quoteProgression].
 */
@Serializable data class ResearchRoute(
    val chatId: Long = 0,
    val quote: String? = null,
    val quoteSourceId: String? = null,
    val quoteTitle: String? = null,
    val quoteLocation: String? = null,
    val quoteProgression: Double? = null,
) {
    /** The selection as the passage the first question is asked about. */
    fun quotePassage(): Passage? = quote?.let {
        Passage(
            kind = SourceKind.SELECTION,
            sourceId = quoteSourceId.orEmpty(),
            sourceTitle = quoteTitle.orEmpty(),
            location = quoteLocation.orEmpty(),
            progression = quoteProgression ?: 0.0,
            text = it,
        )
    }

    companion object {
        /** Longer selections are cut before they go into the route. */
        const val MAX_QUOTE = 2_000

        /** Research about a selection in a reader. */
        fun about(text: String, sourceId: String, title: String, location: String, progression: Double? = null) =
            ResearchRoute(
                quote = text.take(MAX_QUOTE),
                quoteSourceId = sourceId,
                quoteTitle = title,
                quoteLocation = location,
                quoteProgression = progression,
            )
    }
}

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
    val railViewModel = viewModel {
        RailViewModel(container.sessions, container.library, container.bookshelf, container.researchChats, currentTab.id)
    }
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

    // What Define does in the e-book reader; the selection menu reads it live through SelectionBridge.
    val appContext = LocalContext.current.applicationContext
    val dictionary by produceState<DictionaryChoice>(DictionaryChoice.None) {
        val handler = try {
            withContext(Dispatchers.IO) { Dictionary.hasProcessTextHandler(appContext) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        try {
            container.library.packs.collect {
                value = Dictionary.choose(it, handler)
                SelectionBridge.dictionaryAvailable = value != DictionaryChoice.None
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Granger", "Couldn't read the installed packs for Define", e)
            value = DictionaryChoice.None
            SelectionBridge.dictionaryAvailable = false
        }
    }
    /** Opens [word]'s Wiktionary entry over the book, so Back returns to the page; false when there is none. */
    suspend fun lookUpInWiktionary(packId: String, word: String): Boolean {
        val hit = withContext(Dispatchers.IO) { container.library.archive(packId)?.suggest(word, 5) }
            ?.let { Dictionary.pick(it, word) } ?: return false
        val tab = container.sessions.openTab(packId, hit.path, hit.title).let { container.sessions.tab(it) } ?: return false
        nav.showTab(tab, keepCurrent = true)
        return true
    }

    val selected = when {
        destination == null -> null
        destination.hasRoute<LibraryRoute>() || destination.hasRoute<CollectionRoute>() ||
            destination.hasRoute<ArchiveRoute>() -> RailTool.LIBRARY
        destination.hasRoute<SearchRoute>() -> RailTool.SEARCH
        destination.hasRoute<ResearchRoute>() -> RailTool.RESEARCH
        destination.hasRoute<SettingsRoute>() || destination.hasRoute<ReadinessRoute>() ||
            destination.hasRoute<SourceRoute>() -> RailTool.SETTINGS
        else -> null
    }

    // Research runs only on phones of the ASK tier or better; below that, the rail and the selection menus leave it out.
    val showResearch = container.deviceTier >= DeviceTier.ASK
    SideEffect { SelectionBridge.researchAvailable = showResearch }
    /** Opens [route], replacing a Research chat on top (whose view model would otherwise stay); else pushes it. */
    fun openResearch(route: ResearchRoute) {
        val researchOnTop = nav.currentBackStackEntry?.destination?.hasRoute<ResearchRoute>() == true
        nav.navigate(route) { if (researchOnTop) popUpTo<ResearchRoute> { inclusive = true } }
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
                RailTool.RESEARCH -> nav.navigate(ResearchRoute()) { launchSingleTop = true }
                RailTool.PLAYER -> Unit // Hidden behind Features.PLAYER.
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
        onRecentChat = { chatId -> openResearch(ResearchRoute(chatId)) },
        showResearch = showResearch,
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
                    viewModel = viewModel {
                        val context = appContext
                        LibraryViewModel(
                            container.shelves, container.library,
                            ImportRunner(
                                container.bookshelf,
                                open = { uri -> context.contentResolver.openInputStream(uri) },
                                nameOf = { uri -> displayNameOf(context, uri) },
                            ),
                            container.appScope,
                        )
                    },
                    packsFolder = container.packDirs().firstOrNull()?.path.orEmpty(),
                    notice = backStackEntry.toRoute<LibraryRoute>().notice,
                    onOpenCollection = { id -> nav.navigate(CollectionRoute(id)) },
                    onOpenSource = { source ->
                        when (source) {
                            SourceLink.GRANGER -> nav.navigate(SourceRoute(CatalogScope.GRANGER))
                            SourceLink.KIWIX -> nav.navigate(SourceRoute(CatalogScope.KIWIX))
                            SourceLink.ARCHIVE_ORG -> nav.navigate(ArchiveRoute)
                        }
                    },
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
                            container.bookshelf, container.downloads, container.appScope, container.archiveSidecarDir,
                        )
                    },
                    onOpened = ::showTab,
                    onGetBooks = { nav.navigate(SourceRoute(CatalogScope.GRANGER)) },
                    onBack = { nav.popBackStack() },
                )
            }
            composable<ReaderRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<ReaderRoute>()
                ReaderScreen(
                    viewModel = viewModel {
                        ReaderViewModel(route.tabId, container.library, container.sessions, container.appScope, fragment = route.fragment)
                    },
                    resolver = container.resolver,
                    onChromeVisibleChange = { visible -> shell.railVisible = visible },
                    showChromeRequest = showChromeRequest,
                    onSearchText = { text -> nav.navigate(SearchRoute(text)) },
                    onResearch = { text, packId, path, title -> nav.navigate(ResearchRoute.about(text, packId, title, path)) },
                    onBack = { nav.popBackStack() },
                )
            }
            composable<BookRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<BookRoute>()
                val target = route.target()
                EbookReaderScreen(
                    viewModel = viewModel {
                        EbookReaderViewModel(
                            route.tabId, target, container.bookshelf, container.sessions, container.bookOpener,
                            container.readingPrefs, container.appScope,
                            savedState = createSavedStateHandle(),
                        )
                    },
                    onChromeVisibleChange = { visible -> shell.railVisible = visible },
                    showChromeRequest = showChromeRequest,
                    dictionary = dictionary,
                    onSearchText = { text -> nav.navigate(SearchRoute(text)) },
                    onResearch = { text, bookId, title, href, progression ->
                        nav.navigate(ResearchRoute.about(text, bookId, title, href, progression))
                    },
                    onLookUp = ::lookUpInWiktionary,
                    onBack = { nav.popBackStack() },
                )
            }
            composable<SearchRoute> { backStackEntry ->
                val query = backStackEntry.toRoute<SearchRoute>().query
                SearchScreen(
                    viewModel = viewModel { SearchViewModel(container.search, container.sessions, query) },
                    onOpenTab = ::showTab,
                    onOpenBook = { tabId, at ->
                        scope.launch { container.sessions.tab(tabId)?.let { nav.showTab(it, at) } }
                    },
                    onBack = { nav.popBackStack() },
                )
            }
            composable<ResearchRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<ResearchRoute>()
                ResearchScreen(
                    viewModel = viewModel {
                        ResearchViewModel(
                            route.chatId.takeIf { it != 0L }, route.quotePassage(),
                            container.researchChats, container.research, container.modelManager, container.sessions,
                            container.library, container.bookshelf, container.appScope,
                            savedState = createSavedStateHandle(),
                        )
                    },
                    focusInput = route.quote != null,
                    onOpenCitation = { target ->
                        // Pushed over the chat (Research isn't a reader), so Back returns to it.
                        scope.launch {
                            when (target) {
                                is CitationTarget.Article -> container.sessions.tab(target.tabId)?.let { nav.showTab(it, fragment = target.fragment) }
                                is CitationTarget.Book -> container.sessions.tab(target.tabId)?.let { nav.showTab(it, target.at) }
                                CitationTarget.Gone -> Unit
                            }
                        }
                    },
                    onSearch = { keywords -> nav.navigate(SearchRoute(keywords)) },
                    onGetModels = { nav.navigate(SourceRoute(CatalogScope.MODELS)) },
                    onReadiness = { nav.navigate(ReadinessRoute) },
                    onOpenChat = { chatId -> openResearch(ResearchRoute(chatId)) },
                    onNewChat = { openResearch(ResearchRoute()) },
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
                            source, container.catalog, container.downloads, container.library, container.bookshelf, container.volumes,
                            container.downloadPrefs, container.deviceTier, container.appScope,
                        )
                    },
                    title = source.title,
                    onBack = { nav.popBackStack() },
                )
            }
            composable<ArchiveRoute> {
                ArchiveScreen(
                    viewModel = viewModel {
                        ArchiveViewModel(
                            container.archiveClient, container.downloads, container.bookshelf, container.archivePrefs,
                            container.volumes, container.downloadPrefs, { container.archiveSidecarDir }, container.appScope,
                        )
                    },
                    detailsUrl = container.archiveClient::detailsUrl,
                    onBack = { nav.popBackStack() },
                )
            }
            composable<SettingsRoute> {
                SettingsScreen(
                    viewModel = viewModel {
                        SettingsViewModel(container.downloads, container.volumes, container.downloadPrefs, container.archivePrefs, container.appScope)
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
