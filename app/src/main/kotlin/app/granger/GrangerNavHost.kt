package app.granger

import android.util.Log
import android.widget.Toast
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
import androidx.navigation.NavGraph
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.granger.archive.ArchiveScreen
import app.granger.core.library.PdfColour
import app.granger.core.player.NowPlaying
import app.granger.core.speech.ReadSourceKey
import app.granger.core.player.PlayerMode
import app.granger.core.player.PlayerSettingsKeys
import app.granger.core.player.PlayerUi
import app.granger.player.MiniPlayer
import app.granger.player.MiniPlayerPlacement
import app.granger.player.ListenPlayerScreen
import app.granger.player.PlayerViewModel
import app.granger.player.miniPlayerPlacement
import app.granger.archive.ArchiveViewModel
import app.granger.librivox.LibriVoxScreen
import app.granger.librivox.LibriVoxViewModel
import app.granger.core.archive.ArchiveKind
import app.granger.core.library.Albums
import app.granger.core.library.AudioShelves
import app.granger.core.library.BookIds
import app.granger.core.library.MediaResults
import app.granger.core.media.MediaManifest
import app.granger.core.library.ReadingTab
import app.granger.downloads.DownloadsModel
import app.granger.downloads.DownloadsScreen
import app.granger.downloads.DownloadsViewModel
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
import app.granger.listen.ListenActions
import app.granger.browse.BrowseActions
import app.granger.browse.BrowseScreen
import app.granger.browse.BrowseViewModel
import app.granger.listen.ListenScreen
import app.granger.listen.ListenViewModel
import app.granger.widget.playFromWidget
import app.granger.audiobooks.AudiobookScreen
import app.granger.audiobooks.AudiobookShelfScreen
import app.granger.audiobooks.AudiobooksViewModel
import app.granger.audiobooks.DaoListening
import app.granger.music.AlbumScreen
import app.granger.music.ArtistScreen
import app.granger.music.FolderScreen
import app.granger.music.GenreScreen
import app.granger.music.MusicNav
import app.granger.music.MusicScreen
import app.granger.music.MusicViewModel
import app.granger.music.PlaylistScreen
import app.granger.reader.PackDocuments
import app.granger.reader.ReaderScreen
import app.granger.reader.ReaderViewModel
import app.granger.readiness.ReadinessScreen
import app.granger.readiness.ReadinessViewModel
import app.granger.search.SearchScreen
import app.granger.search.MediaSearch
import app.granger.search.searchForGroups
import app.granger.search.SearchViewModel
import android.content.Context
import app.granger.media.PlayResumer
import app.granger.settings.ComponentOtherAppsSharing
import app.granger.settings.DaoMediaFolders
import app.granger.settings.MediaSettings
import app.granger.settings.MediaSettingsScreen
import app.granger.settings.ResolverFolderGrants
import app.granger.settings.ScanSummary
import app.granger.settings.SettingsScreen
import app.granger.settings.VoiceSettingsScreen
import app.granger.settings.VoiceSettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flowOf
import app.granger.settings.SettingsViewModel
import app.granger.shell.GrangerShell
import app.granger.shell.HistoryScreen
import app.granger.shell.HistoryViewModel
import app.granger.home.ArticleThumbnails
import java.io.File
import app.granger.home.CardTarget
import app.granger.home.FeedDrawer
import app.granger.home.HomeScreen
import app.granger.home.HomeViewModel
import app.granger.onboarding.OnboardingScreen
import app.granger.onboarding.OnboardingViewModel
import app.granger.shell.RailEntry
import app.granger.shell.RailEntryActions
import app.granger.shell.RailTool
import app.granger.shell.RailViewModel
import app.granger.shell.rememberShellState
import app.granger.sources.CatalogScope
import app.granger.sources.GetMoreLink
import app.granger.sources.GetMoreScreen
import app.granger.sources.SourceScreen
import app.granger.sources.SourceViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import app.granger.core.books.TextQuote
import app.granger.core.ai.DeviceTier
import app.granger.core.research.Passage
import app.granger.core.research.SourceKind
import app.granger.research.CitationTarget
import app.granger.research.citationTab
import app.granger.research.ResearchScreen
import app.granger.research.ResearchViewModel

/** [notice] is shown once as a snackbar, e.g. why the app didn't resume reading. */
@Serializable data class LibraryRoute(val notice: String? = null)

/** Home (Plan H2): the readiness strip, Continue and the feeds. A rail tool, pushed over the Library. */
@Serializable object HomeRoute

/** Onboarding (Plan H3): five screens over Home on a first run, or from Settings. */
@Serializable object OnboardingRoute
@Serializable data class CollectionRoute(val collectionId: String)

/** Music's tabs for one Music collection; null is "All music" (not scoped). */
@Serializable data class MusicRoute(val collectionId: String? = null)
/** [trackId]: a cited song, scrolled to and marked. */
@Serializable data class AlbumRoute(val albumKey: String, val trackId: String? = null)
@Serializable data class ArtistRoute(val name: String)
@Serializable data class GenreRoute(val name: String)
@Serializable data class PlaylistRoute(val playlistId: Long)

/** [folder] is a track's stored folder, as `Track.folder` holds it. */
@Serializable data class FolderRoute(val folder: String)

private fun NavDestination.isMusic() = hasRoute<MusicRoute>() || hasRoute<AlbumRoute>() || hasRoute<ArtistRoute>() ||
    hasRoute<GenreRoute>() || hasRoute<PlaylistRoute>() || hasRoute<FolderRoute>()

/** The Audiobooks shelf for one Audiobooks collection; null is "All audiobooks" (not scoped). */
@Serializable data class AudiobookShelfRoute(val collectionId: String? = null)
/** [chapter]: a cited chapter's ordinal, scrolled to and marked. */
@Serializable data class AudiobookRoute(val audiobookId: String, val chapter: Int? = null)

private fun NavDestination.isAudiobooks() = hasRoute<AudiobookShelfRoute>() || hasRoute<AudiobookRoute>()
/** Said when a cited article's page is gone from its pack, so its main page opened instead (research spec §7). */
const val PAGE_MOVED = "That page is no longer in this pack."

/** An article; [fragment] scrolls its first page to that section (a cited passage's heading), and [quote] is marked there. */
@Serializable data class ReaderRoute(val tabId: Long, val fragment: String? = null, val quote: String? = null)

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
 * [at] (a book) or [fragment] (an article's section, with [quote] to mark in it) asks for a place in it.
 */
fun NavController.showTab(
    tab: ReadingTab, at: BookTarget? = null, keepCurrent: Boolean = false, fragment: String? = null, quote: String? = null,
) {
    // Read the back stack now, not the composition's copy: opening a tab suspends, and a second tap
    // meanwhile must replace the reader the first one pushed.
    val top = currentBackStackEntry
    val showing = top?.readingTabId()
    if (tab.id == showing && at == null && fragment == null && quote == null) return
    val route: Any =
        if (BookIds.isBook(tab.packId)) {
            BookRoute(tab.id, at?.href, at?.progression, at?.quote?.highlight, at?.quote?.before, at?.quote?.after)
        } else {
            ReaderRoute(tab.id, fragment, quote)
        }
    navigate(route) {
        // Switching tabs replaces the reader instead of stacking one per tab.
        // keepCurrent stacks it on the reader instead (a dictionary entry over the book), so Back returns to the page.
        if (showing != null && top != null && !keepCurrent) popUpTo(top.destination.id) { inclusive = true }
    }
}

/**
 * Opens [tab] from the shell's way in (the rail, Home, Search and the other screens that list tabs), keeping a reader on
 * top underneath, so Back returns to the page you were on (Plan U3c Decision 7). The rail's tools still clear to their
 * own landing; only an item opened from them stacks.
 */
fun NavController.openTab(tab: ReadingTab) = showTab(tab, keepCurrent = true)

/**
 * After [tabId] is removed from the rail: if its reader is on screen, the reader closes and Home shows, popping back to
 * the Home on the stack or replacing the reader with one (Plan U3c walkthrough: the open item couldn't be removed).
 * A tab not on screen just leaves the rail.
 */
fun NavController.leaveRemovedTab(tabId: Long) {
    val top = currentBackStackEntry ?: return
    if (top.readingTabId() != tabId) return
    if (!popBackStack<HomeRoute>(inclusive = false)) navigate(HomeRoute) { popUpTo(top.destination.id) { inclusive = true } }
}

/**
 * After Clear history: takes every reader whose tab isn't in [keptTabIds] off the back stack, so Back never returns to a
 * page that was cleared, nor writes it into History again (Plan U3c walkthrough). The screens above the lowest such
 * reader are popped and opened again in order, with their arguments, leaving the others out; the kept reader right
 * under History and the pinned tabs' readers stay.
 */
fun NavController.dropClosedReaders(keptTabIds: Set<Long>) {
    val entries = currentBackStack.value.filter { it.destination !is NavGraph }
    val first = entries.indexOfFirst { entry -> entry.readingTabId()?.let { it !in keptTabIds } == true }
    if (first < 0) return
    val reopen = entries.drop(first + 1).filter { entry -> entry.readingTabId()?.let { it in keptTabIds } != false }
    repeat(entries.size - first) { popBackStack() }
    reopen.forEach { navigate(it.destination.id, it.arguments) }
}

@Serializable data class SearchRoute(val query: String = "")
@Serializable object HistoryRoute
/** [domain] filters the Kiwix list to a readiness domain (Plan H1); null is the whole catalog. */
@Serializable data class SourceRoute(val scope: CatalogScope, val domain: String? = null)
/** [kind] picks the view: texts (Books), spoken word (Audiobooks) or music. */
@Serializable data class ArchiveRoute(val kind: ArchiveKind = ArchiveKind.TEXTS)
/** The LibriVox source: Recently added, six genres and search (Plan M2). */
@Serializable object LibriVoxRoute

fun NavController.openLibriVox() = navigate(LibriVoxRoute) { launchSingleTop = true }

/** Where sources live: Kiwix, books, audiobooks and music, models (Plan U3b Decision 2). */
@Serializable object GetMoreRoute

/** Where each Get more row goes: today's source screens. */
internal fun routeOf(link: GetMoreLink): Any = when (link) {
    GetMoreLink.KIWIX -> SourceRoute(CatalogScope.KIWIX)
    GetMoreLink.GRANGER_CLASSICS -> SourceRoute(CatalogScope.GRANGER)
    GetMoreLink.ARCHIVE_ORG_BOOKS -> ArchiveRoute(ArchiveKind.TEXTS)
    GetMoreLink.LIBRIVOX -> LibriVoxRoute
    GetMoreLink.ARCHIVE_ORG_AUDIOBOOKS -> ArchiveRoute(ArchiveKind.SPOKEN_WORD)
    GetMoreLink.ARCHIVE_ORG_MUSIC -> ArchiveRoute(ArchiveKind.MUSIC)
    GetMoreLink.MODELS -> SourceRoute(CatalogScope.MODELS)
}

fun NavController.openGetMore(link: GetMoreLink) {
    if (link == GetMoreLink.LIBRIVOX) openLibriVox() else navigate(routeOf(link))
}

/** Where each Library source link goes (spec §3.4): the archive.org links open its texts, spoken-word or music view. */
internal fun routeOf(link: SourceLink): Any = when (link) {
    SourceLink.GRANGER -> SourceRoute(CatalogScope.GRANGER)
    SourceLink.KIWIX -> SourceRoute(CatalogScope.KIWIX)
    SourceLink.ARCHIVE_ORG -> ArchiveRoute(ArchiveKind.TEXTS)
    SourceLink.ARCHIVE_ORG_SPOKEN -> ArchiveRoute(ArchiveKind.SPOKEN_WORD)
    SourceLink.ARCHIVE_ORG_MUSIC -> ArchiveRoute(ArchiveKind.MUSIC)
    SourceLink.LIBRIVOX -> LibriVoxRoute
}

fun NavController.openSource(link: SourceLink) {
    if (link == SourceLink.LIBRIVOX) openLibriVox() else navigate(routeOf(link))
}

/**
 * An archive.org item seen in the [kind] view but got from the other one, where it lives: an album (got from Music)
 * or a book (got from Audiobooks), by its origin ids.
 */
internal fun inLibraryRoute(kind: ArchiveKind, identifier: String): Any =
    if (kind == ArchiveKind.MUSIC) AudiobookRoute("audiobook:${MediaManifest.ARCHIVE_ORG}:$identifier")
    else AlbumRoute(Albums.itemKey(MediaManifest.ARCHIVE_ORG, identifier))
/** [media] opens it at the Music & audiobooks section. */
@Serializable object SettingsRoute
/** Settings → Music & audiobooks, its own screen (Plan U3b Decision 9). */
@Serializable object MediaSettingsRoute
@Serializable object ReadinessRoute
/** What's downloading, what stopped and what landed today (Plan U3b Decision 1). */
@Serializable object DownloadsRoute
/** Settings → Voice (Plan V1). */
@Serializable object VoiceRoute

/**
 * The rail tool a destination belongs to. Get more and its sources are part of the Library, as is everything they lead
 * to, except Models ([sourceScope] is a [SourceRoute]'s scope), which stays with Settings (Plan U3b Decision 2), and
 * Kiwix, whose packs are Browse's. Browse also lights while a pack is read; a book being read lights the Library (Plan
 * U3c Decision 1). Music, audiobooks, the player and the audio sources (LibriVox, and Archive.org's spoken word and
 * music: [archiveKind] is an [ArchiveRoute]'s kind) are Listen's (Plan U2 Decision 4). Downloads and Readiness cover
 * everything, and stay the Library's.
 */
internal fun railToolOf(destination: NavDestination, sourceScope: CatalogScope? = null, archiveKind: ArchiveKind? = null): RailTool? = when {
    destination.hasRoute<HomeRoute>() || destination.hasRoute<OnboardingRoute>() -> RailTool.HOME
    destination.hasRoute<SourceRoute>() -> when (sourceScope) {
        CatalogScope.MODELS -> RailTool.SETTINGS
        CatalogScope.KIWIX -> RailTool.BROWSE
        else -> RailTool.LIBRARY
    }
    destination.hasRoute<BrowseRoute>() || destination.hasRoute<ReaderRoute>() -> RailTool.BROWSE
    destination.hasRoute<BookRoute>() -> RailTool.LIBRARY
    destination.hasRoute<ArchiveRoute>() -> if (archiveKind == null || archiveKind == ArchiveKind.TEXTS) RailTool.LIBRARY else RailTool.LISTEN
    destination.hasRoute<ListenRoute>() || destination.hasRoute<ListenPlayerRoute>() || destination.hasRoute<LibriVoxRoute>() ||
        destination.isMusic() || destination.isAudiobooks() -> RailTool.LISTEN
    destination.hasRoute<LibraryRoute>() || destination.hasRoute<CollectionRoute>() ||
        destination.hasRoute<DownloadsRoute>() || destination.hasRoute<GetMoreRoute>() || destination.hasRoute<ReadinessRoute>() -> RailTool.LIBRARY
    destination.hasRoute<SearchRoute>() -> RailTool.SEARCH
    destination.hasRoute<ResearchRoute>() -> RailTool.RESEARCH
    destination.hasRoute<SettingsRoute>() || destination.hasRoute<MediaSettingsRoute>() -> RailTool.SETTINGS
    else -> null
}

/** Browse's home (Plan U3c Decision 1): the packs and the maps, each a row opening its main page. The tool's landing. */
@Serializable object BrowseRoute

/** Listen's home (Plan U2 Decision 4): the Now playing card and the Music and Audiobooks shelves. The tool's landing. */
@Serializable object ListenRoute

/** The full player (Plan U2 Decision 4): its Reader tab, and Music or Audiobook, whichever was played last (spec §8). */
@Serializable object ListenPlayerRoute

/**
 * The mini-player's tap: opens the full-screen player, or returns from it (to the mini-player) when it's on top. With
 * the player further down the back stack it is brought back up rather than stacked a second time.
 */
fun NavController.togglePlayer() {
    when {
        currentBackStackEntry?.destination?.hasRoute<ListenPlayerRoute>() == true -> popBackStack()
        popBackStack<ListenPlayerRoute>(inclusive = false) -> Unit
        else -> navigate(ListenPlayerRoute) { launchSingleTop = true }
    }
}

/** The mini-player's tap ([togglePlayer]), with the player on the live tab: Reader for the voice, else the media tab. */
fun NavController.togglePlayer(player: PlayerViewModel) {
    player.resetTab()
    togglePlayer()
}

/** Shows the full-screen player: nothing when it's on top, brought back up when further down, else opened. */
fun NavController.showPlayer() {
    if (currentBackStackEntry?.destination?.hasRoute<ListenPlayerRoute>() == true) return
    if (!popBackStack<ListenPlayerRoute>(inclusive = false)) navigate(ListenPlayerRoute) { launchSingleTop = true }
}

/**
 * The player's shelf link: Music ([MusicRoute] for All music) or Audiobooks ([AudiobookShelfRoute] for all of them).
 * The player is closed first, so Back from the shelf returns to the screen the player was opened from, as closing the
 * player would; the mini-player keeps playing. When that screen already is the shelf, it is simply shown.
 */
fun NavController.openShelfFromPlayer(mode: PlayerMode) {
    popBackStack<ListenPlayerRoute>(inclusive = true)
    val top = currentBackStackEntry
    val showing = when (mode) {
        PlayerMode.MUSIC -> top?.destination?.hasRoute<MusicRoute>() == true && top.toRoute<MusicRoute>().collectionId == null
        PlayerMode.AUDIOBOOK -> top?.destination?.hasRoute<AudiobookShelfRoute>() == true && top.toRoute<AudiobookShelfRoute>().collectionId == null
    }
    if (!showing) navigate(if (mode == PlayerMode.MUSIC) MusicRoute(null) else AudiobookShelfRoute(null))
}

/** How [route] is opened: [replacesChatOnTop] drops a Research chat on top first (its view model would otherwise stay). */
internal data class ResearchOpening(val route: ResearchRoute, val replacesChatOnTop: Boolean)

/**
 * Ask's one rule for + and Back (Plan U3a Decision 9, revised after Neal's walkthrough): a kept chat picked from the
 * Chats sheet ([fromChats]) is pushed over the chat it was picked in, so Back returns there; + and the rail's tool
 * replace a chat on top with a fresh, empty one. A reader's "Ask about this" is pushed over the reader by its own route.
 */
internal fun researchOpening(route: ResearchRoute, researchOnTop: Boolean, fromChats: Boolean = false) =
    ResearchOpening(route, replacesChatOnTop = researchOnTop && !fromChats)

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
fun GrangerNavHost(container: AppContainer, start: StartScreen, openPlayerRequests: Int = 0, openDownloadsRequests: Int = 0) {
    val nav = rememberNavController()
    val shell = rememberShellState()
    // True only for the first resume navigation; read when that navigation's transitions are chosen.
    var skipTransition by remember { mutableStateOf(false) }
    var showChromeRequest by rememberSaveable { mutableStateOf(0) }
    val currentTab = viewModel { CurrentTab() }
    val railViewModel = viewModel {
        RailViewModel(
            container.sessions, container.library, container.bookshelf, container.researchChats, currentTab.id,
            container.railPins, container.railHidden, container.railOrder,
            plays = if (Features.PLAYER) container.playback.observeRecentPlays(RailViewModel.SCAN) else flowOf(emptyList()),
            recentPlay = container.playback::recentPlay,
            resumer = if (Features.PLAYER) container.playResumer else null,
            voice = if (Features.PLAYER && Features.READ_ALOUD) container.playerHub.ui.map { it.now as? NowPlaying.Voice } else flowOf(null),
            stopVoice = { if (Features.PLAYER) container.playerHub.stopVoice() },
        )
    }
    val rail by railViewModel.ui.collectAsStateWithLifecycle()
    // Settings → Appearance; a change there moves the rail at once.
    val railSide by container.appearancePrefs.railSide.collectAsStateWithLifecycle()
    // One for the activity: the mini-player and the full-screen player share it.
    val player = if (Features.PLAYER) {
        viewModel {
            PlayerViewModel(
                container.playerHub,
                chapters = container.media::chapters,
                bookmarks = container.playback::observeBookmarks,
                deleteBookmark = container.playback::deleteBookmark,
                skipBackSeconds = { container.playerPrefs.getInt(PlayerSettingsKeys.SKIP_BACK_S, PlayerViewModel.DEFAULT_SKIP_S) },
                skipForwardSeconds = { container.playerPrefs.getInt(PlayerSettingsKeys.SKIP_FORWARD_S, PlayerViewModel.DEFAULT_SKIP_S) },
                audiobooks = container.shelves.audioShelves.map { it.audiobooks },
                chooseVoice = container.voicePrefs::setVoice,
                focus = container.playerFocus,
            )
        }
    } else {
        null
    }
    val playerUi by (player?.ui ?: remember { MutableStateFlow(PlayerUi.IDLE) }).collectAsStateWithLifecycle()
    val miniUi by (player?.mini ?: remember { MutableStateFlow(PlayerUi.IDLE) }).collectAsStateWithLifecycle()

    val entry by nav.currentBackStackEntryAsState()
    val destination = entry?.destination
    val readerTabId = entry?.readingTabId()
    LaunchedEffect(readerTabId) { currentTab.id.value = readerTabId }
    // Non-reader screens always show the rail; a reader shows it with its chrome (up on entry, a middle tap hides it).
    LaunchedEffect(destination) {
        if (destination?.isReading() != true) shell.railVisible = true
        if (destination?.isReading() == true || destination?.hasRoute<HomeRoute>() == true || destination?.hasRoute<OnboardingRoute>() == true) {
            skipTransition = false
        }
    }

    // Resuming puts the reader on top of the Library, so Back from it lands there. Once per launch, not per rotation.
    var resumeHandled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!resumeHandled) {
            resumeHandled = true
            if (start is StartScreen.Reader) {
                skipTransition = true
                nav.navigate(if (start.book) BookRoute(start.tabId) else ReaderRoute(start.tabId))
            } else if (start is StartScreen.Home) {
                // Home over the Library, the same way: Back from it lands on the Library (Plan H2, Decision 1).
                skipTransition = true
                nav.navigate(HomeRoute)
            } else if (start is StartScreen.Onboarding) {
                // Home, then the flow over it: Start and Skip pop back to Home (Plan H3, Decision 1).
                skipTransition = true
                nav.navigate(HomeRoute)
                nav.navigate(OnboardingRoute)
            }
        }
    }

    // The home-screen widget's body: each request (a new count) shows the Player, over whatever the launch opened.
    // The widget's tap: the player on what's live, as Listen's Now playing card opens it.
    LaunchedEffect(openPlayerRequests) { if (openPlayerRequests > 0 && Features.PLAYER) { player?.resetTab(); nav.showPlayer() } }
    // A download notification's tap: Downloads, over whatever the launch opened.
    LaunchedEffect(openDownloadsRequests) { if (openDownloadsRequests > 0) nav.navigate(DownloadsRoute) { launchSingleTop = true } }
    // The Library header's badge: what's coming or stopped.
    val downloadsBadge by remember { container.downloads.records.map(DownloadsModel::badge) }
        .collectAsStateWithLifecycle(0)

    // Every screen that opens a tab hands over its id; the tab decides which reader shows it.
    val scope = rememberCoroutineScope()
    // A song tapped in Search plays its album from there.
    val songs = container.songs
    fun showTab(tabId: Long) {
        scope.launch { container.sessions.tab(tabId)?.let { nav.openTab(it) } }
    }

    /**
     * The one way an installed pack opens: from Browse, a source's Open and Downloads (Plan U3b Decision 3). [newTab]:
     * Browse's way, the main page in a tab of its own even when the pack is open on the rail (Plan U3c walkthrough).
     */
    fun openPack(packId: String, newTab: Boolean = false) {
        scope.launch {
            val tabId = app.granger.library.openPack(container.library, container.sessions, packId, newTab) ?: return@launch
            container.sessions.tab(tabId)?.let { nav.showTab(it) }
        }
    }

    /**
     * Opens the page the voice is reading (an article in its tab, a book in its reader), over a reader on top so Back
     * returns to it; null when the key names neither.
     */
    suspend fun openVoiceSource(voice: NowPlaying.Voice): Unit? {
        val tabId = when (val key = ReadSourceKey.parse(voice.sourceKey)) {
            is ReadSourceKey.Article -> container.sessions.openTab(key.packId, key.path, voice.title)
            is ReadSourceKey.Book -> container.sessions.openTab(key.bookId, "", voice.title)
            is ReadSourceKey.Text -> {
                // A spoken Research answer (Plan V2) opens its chat.
                val turnId = ResearchViewModel.answerTurnId(voice.sourceKey) ?: return null
                val chatId = container.researchChats.turn(turnId)?.chatId ?: return null
                nav.navigate(ResearchRoute(chatId))
                return Unit
            }
            null -> return null
        }
        container.sessions.tab(tabId)?.let { nav.openTab(it) }
        return Unit
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
    /** A PDF in a pack, in the e-book reader over the article (Plan P2), so Back returns to the page; [linkText] may name it. */
    suspend fun openPackDocument(packId: String, path: String, linkText: String) {
        when (val opened = container.packDocuments.open(packId, path, linkText)) {
            is PackDocuments.Opened.Ready -> {
                val tab = container.sessions.openTab(opened.book.id, "", opened.book.title).let { container.sessions.tab(it) } ?: return
                nav.showTab(tab, keepCurrent = true)
            }
            is PackDocuments.Opened.Failed -> android.widget.Toast.makeText(appContext, opened.message, android.widget.Toast.LENGTH_LONG).show()
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

    val selected = entry?.let { e ->
        railToolOf(
            e.destination,
            sourceScope = if (e.destination.hasRoute<SourceRoute>()) e.toRoute<SourceRoute>().scope else null,
            archiveKind = if (e.destination.hasRoute<ArchiveRoute>()) e.toRoute<ArchiveRoute>().kind else null,
        )
    }

    val miniPlacement = miniPlayerPlacement(
        loaded = playerUi.now != NowPlaying.Idle,
        // Listen's home has the Now playing card instead.
        onPlayer = destination?.hasRoute<ListenPlayerRoute>() == true || destination?.hasRoute<ListenRoute>() == true,
        reading = readerTabId != null,
    )

    // Research runs only on phones of the ASK tier or better; below that, the rail and the selection menus leave it out.
    val showResearch = container.deviceTier >= DeviceTier.ASK
    SideEffect {
        SelectionBridge.researchAvailable = showResearch
        SelectionBridge.readAloudAvailable = Features.READ_ALOUD && Features.PLAYER
    }
    /** Opens [route], replacing a Research chat on top (whose view model would otherwise stay); else pushes it. */
    fun openResearch(route: ResearchRoute, fromChats: Boolean = false) {
        val researchOnTop = nav.currentBackStackEntry?.destination?.hasRoute<ResearchRoute>() == true
        val open = researchOpening(route, researchOnTop, fromChats)
        nav.navigate(open.route) { if (open.replacesChatOnTop) popUpTo<ResearchRoute> { inclusive = true } }
    }

    val voiceNow = playerUi.now as? NowPlaying.Voice

    /** Opens the full player on what's live (or the tab last used), from Listen's Now playing card. */
    fun openPlayer() {
        player?.resetTab()
        nav.showPlayer()
    }

    /** Reader's Open: the player closes, so Back from the page returns to where the player was opened from. */
    fun openReadingFromPlayer(voice: NowPlaying.Voice) {
        nav.popBackStack<ListenPlayerRoute>(inclusive = true)
        scope.launch { openVoiceSource(voice) }
    }

    /** Plays a rail item again. Gone or unplayable: the Library, with a notice saying so (it replaces the Library on the stack). */
    fun resumePlay(item: RailEntry.Play) {
        scope.launch {
            val notice = railViewModel.resume(item) ?: return@launch
            nav.navigate(LibraryRoute(notice)) { popUpTo<LibraryRoute> { inclusive = true } }
        }
    }

    GrangerShell(
        rail = rail,
        expanded = shell.expanded,
        // No rail during onboarding: a first run can't wander into empty tools (Plan U3a Decision 12).
        railVisible = shell.railVisible && destination?.hasRoute<OnboardingRoute>() != true,
        selected = selected,
        onToggleExpanded = { shell.expanded = !shell.expanded },
        onEdgeSwipe = {
            // Full-screen reader: the swipe brings the chrome (and with it the rail, unless it stayed) up; elsewhere it opens the rail.
            if (readerTabId != null && !shell.railVisible) showChromeRequest++ else shell.expanded = !shell.expanded
        },
        onTool = { tool ->
            when (tool) {
                RailTool.HOME -> if (!nav.popBackStack<HomeRoute>(inclusive = false)) nav.navigate(HomeRoute) { launchSingleTop = true }
                RailTool.SEARCH -> nav.navigate(SearchRoute()) { launchSingleTop = true }
                RailTool.BROWSE -> if (!nav.popBackStack<BrowseRoute>(inclusive = false)) nav.navigate(BrowseRoute) { launchSingleTop = true }
                RailTool.LIBRARY -> if (!nav.popBackStack<LibraryRoute>(inclusive = false)) nav.navigate(LibraryRoute())
                RailTool.SETTINGS -> nav.navigate(SettingsRoute) { launchSingleTop = true }
                RailTool.RESEARCH -> openResearch(ResearchRoute()) // A new chat, even while a kept one is open.
                RailTool.LISTEN -> if (!nav.popBackStack<ListenRoute>(inclusive = false)) nav.navigate(ListenRoute) { launchSingleTop = true }
            }
        },
        onTab = ::showTab,
        onHistory = { nav.navigate(HistoryRoute) { launchSingleTop = true } },
        onSearch = { query ->
            // Replace a Search that is on top; otherwise push, so Back returns to the article it was opened from.
            val searchOnTop = nav.currentBackStackEntry?.destination?.hasRoute<SearchRoute>() == true
            nav.navigate(SearchRoute(query)) { if (searchOnTop) popUpTo<SearchRoute> { inclusive = true } }
        },
        onChat = { chatId -> openResearch(ResearchRoute(chatId)) },
        // The live reading's row (Plan V2) opens the page being read, as Reader's Open does.
        onReading = { scope.launch { voiceNow?.let { openVoiceSource(it) } } },
        onPlay = ::resumePlay,
        actions = remember(railViewModel) {
            RailEntryActions(
                onPin = { entry ->
                    scope.launch {
                        // All six are taken: say to unpin one first.
                        val notice = railViewModel.pin(entry) ?: return@launch
                        Toast.makeText(appContext, notice, Toast.LENGTH_LONG).show()
                    }
                },
                onUnpin = railViewModel::unpin,
                onRemove = { entry ->
                    railViewModel.remove(entry)
                    // The tab on screen: it closes, and Home shows.
                    if (entry is RailEntry.Tab) nav.leaveRemovedTab(entry.tab.tabId)
                },
            )
        },
        side = railSide,
        showResearch = showResearch,
        reading = readerTabId != null,
        // The wiki keeps its rail while the chrome is away (Plan U3c walkthrough); e-books and PDFs read edge to edge.
        railStays = destination?.hasRoute<ReaderRoute>() == true,
        miniPlayer = if (player == null || miniPlacement == MiniPlayerPlacement.HIDDEN) {
            null
        } else {
            {
                MiniPlayer(
                    miniUi,
                    onPlayPause = player::miniPlayPause,
                    onSecondary = player::secondary,
                    onOpen = { nav.togglePlayer(player) },
                    skipBackS = player.skipBackS(),
                )
            }
        },
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
                                onImported = { container.appScope.launch { container.bookIndex.indexPending() } },
                            ),
                            container.appScope,
                        )
                    },
                    notice = backStackEntry.toRoute<LibraryRoute>().notice,
                    onOpenCollection = { id -> nav.navigate(CollectionRoute(id)) },
                    onSearch = { nav.navigate(SearchRoute()) },
                    onReadiness = { nav.navigate(ReadinessRoute) },
                    downloadsBadge = downloadsBadge,
                    onDownloads = { nav.navigate(DownloadsRoute) { launchSingleTop = true } },
                    onGetMore = { nav.navigate(GetMoreRoute) { launchSingleTop = true } },
                    onOpenSource = nav::openGetMore,
                )
            }
            composable<BrowseRoute> {
                BrowseScreen(
                    viewModel { BrowseViewModel(container.shelves, container.library, container.catalog, container.downloads.records) },
                    packsFolder = container.packDirs().firstOrNull()?.path.orEmpty(),
                    downloadsBadge = downloadsBadge,
                    actions = BrowseActions(
                        // Always a new tab at the pack's main page, so a wiki can be open in two places.
                        onOpenPack = { packId -> openPack(packId, newTab = true) },
                        onGetPacks = { domain -> nav.navigate(SourceRoute(CatalogScope.KIWIX, domain)) },
                        onDownloads = { nav.navigate(DownloadsRoute) { launchSingleTop = true } },
                        onReadiness = { nav.navigate(ReadinessRoute) },
                    ),
                )
            }
            composable<CollectionRoute> { backStackEntry ->
                CollectionScreen(
                    viewModel = viewModel {
                        CollectionViewModel(
                            backStackEntry.toRoute<CollectionRoute>().collectionId,
                            container.shelves, container.library, container.sessions,
                            container.bookshelf, container.downloads, container.appScope, container.archiveSidecarDir,
                            container.catalog, createSavedStateHandle(), container.libraryViews,
                        )
                    },
                    onOpened = ::showTab,
                    onOpenPack = ::openPack,
                    onGetBooks = { nav.navigate(SourceRoute(CatalogScope.GRANGER)) },
                    onOpenCollection = { id -> nav.navigate(CollectionRoute(id)) },
                    onBack = { nav.popBackStack() },
                )
            }
            val musicNav = MusicNav(
                onAlbum = { nav.navigate(AlbumRoute(it)) },
                onArtist = { nav.navigate(ArtistRoute(it)) },
                onGenre = { nav.navigate(GenreRoute(it)) },
                onPlaylist = { nav.navigate(PlaylistRoute(it)) },
                onFolder = { nav.navigate(FolderRoute(it)) },
                onBack = { nav.popBackStack() },
                onOpenSource = nav::openSource,
                onMediaSettings = { nav.navigate(MediaSettingsRoute) { launchSingleTop = true } },
            )
            val removeDownload: (String) -> Unit = { group -> container.appScope.launch { container.mediaDownloader.remove(group) } }
            // The detail pages aren't scoped to a collection: their routes carry none.
            fun musicViewModel(collectionId: String?) =
                MusicViewModel(
                    collectionId, container.media, container.shelves, container.playlists, container.playerHub,
                    downloads = container.downloads.records, remover = removeDownload, viewPrefs = container.libraryViews,
                )
            composable<MusicRoute> { backStackEntry ->
                val id = backStackEntry.toRoute<MusicRoute>().collectionId
                MusicScreen(viewModel { musicViewModel(id) }, musicNav)
            }
            composable<AlbumRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<AlbumRoute>()
                AlbumScreen(viewModel { musicViewModel(null) }, route.albumKey, musicNav, marked = route.trackId)
            }
            composable<ArtistRoute> { backStackEntry ->
                ArtistScreen(viewModel { musicViewModel(null) }, backStackEntry.toRoute<ArtistRoute>().name, musicNav)
            }
            composable<GenreRoute> { backStackEntry ->
                GenreScreen(viewModel { musicViewModel(null) }, backStackEntry.toRoute<GenreRoute>().name, musicNav)
            }
            composable<PlaylistRoute> { backStackEntry ->
                PlaylistScreen(viewModel { musicViewModel(null) }, backStackEntry.toRoute<PlaylistRoute>().playlistId, musicNav)
            }
            composable<FolderRoute> { backStackEntry ->
                FolderScreen(viewModel { musicViewModel(null) }, backStackEntry.toRoute<FolderRoute>().folder, musicNav)
            }
            // The book page isn't scoped to a collection: its route carries none.
            fun audiobooksViewModel(collectionId: String?) = AudiobooksViewModel(
                collectionId, container.media, container.shelves, DaoListening(container.playback), container.playerHub,
                downloads = container.downloads.records, remover = removeDownload, viewPrefs = container.libraryViews,
            )
            composable<AudiobookShelfRoute> { backStackEntry ->
                val id = backStackEntry.toRoute<AudiobookShelfRoute>().collectionId
                AudiobookShelfScreen(
                    viewModel { audiobooksViewModel(id) },
                    onBook = { nav.navigate(AudiobookRoute(it)) },
                    onBack = { nav.popBackStack() },
                    onOpenSource = nav::openSource,
                    onMediaSettings = { nav.navigate(MediaSettingsRoute) { launchSingleTop = true } },
                )
            }
            composable<AudiobookRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<AudiobookRoute>()
                AudiobookScreen(
                    viewModel { audiobooksViewModel(null) },
                    route.audiobookId,
                    onBack = { nav.popBackStack() },
                    markedChapter = route.chapter,
                )
            }
            composable<ReaderRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<ReaderRoute>()
                ReaderScreen(
                    viewModel = viewModel {
                        ReaderViewModel(
                            route.tabId, container.library, container.sessions, container.appScope, fragment = route.fragment,
                            player = if (Features.PLAYER && Features.READ_ALOUD) container.playerHub else null,
                            quote = route.quote, savedPlace = container.voicePlace, prefsStore = container.readingPrefs,
                        )
                    },
                    resolver = container.resolver,
                    onChromeVisibleChange = { visible -> shell.railVisible = visible },
                    showChromeRequest = showChromeRequest,
                    onSearchText = { text -> nav.navigate(SearchRoute(text)) },
                    onResearch = { text, packId, path, title -> nav.navigate(ResearchRoute.about(text, packId, title, path)) },
                    onOpenDocument = { packId, path, linkText -> scope.launch { openPackDocument(packId, path, linkText) } },
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
                            player = if (Features.PLAYER && Features.READ_ALOUD) container.playerHub else null,
                            restore = container.packDocuments::restore, savedPlace = container.voicePlace,
                            pdfColour = PdfColour::isColour,
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
                    onOpenTab = ::showTab,
                    // Made an ebook (Plan IA): this reader closes and the tab opens again, as the ebook.
                    onReopen = {
                        nav.popBackStack()
                        showTab(route.tabId)
                    },
                )
            }
            composable<SearchRoute> { backStackEntry ->
                val query = backStackEntry.toRoute<SearchRoute>().query
                SearchScreen(
                    viewModel = viewModel {
                        SearchViewModel(
                            container.search, container.sessions, query,
                            media = if (Features.PLAYER) MediaSearch { container.mediaIndex.searchForGroups(it) } else MediaSearch { MediaResults() },
                        )
                    },
                    onOpenTab = ::showTab,
                    onOpenBook = { tabId, at ->
                        scope.launch { container.sessions.tab(tabId)?.let { nav.showTab(it, at) } }
                    },
                    onOpenAlbum = { nav.navigate(AlbumRoute(it)) },
                    onOpenArtist = { nav.navigate(ArtistRoute(it)) },
                    onOpenAudiobook = { nav.navigate(AudiobookRoute(it)) },
                    onPlaySong = { track -> scope.launch { songs.play(track) } },
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
                            container.library, container.bookshelf, container.mediaIndex, container.appScope,
                            savedState = createSavedStateHandle(),
                            player = if (Features.PLAYER && Features.READ_ALOUD) container.playerHub else null,
                            speakAnswers = { container.voicePrefs.speakAnswers.value },
                        )
                    },
                    focusInput = route.quote != null,
                    onOpenCitation = { target ->
                        // Pushed over the chat (Research isn't a reader), so Back returns to it.
                        scope.launch {
                            val tabId = when (target) {
                                is CitationTarget.Article -> target.tabId
                                is CitationTarget.Book -> target.tabId
                                is CitationTarget.Album -> return@launch nav.navigate(AlbumRoute(target.albumKey, target.trackId))
                                is CitationTarget.Audiobook -> return@launch nav.navigate(AudiobookRoute(target.audiobookId, target.chapter))
                                CitationTarget.Gone -> return@launch
                            }
                            val tab = citationTab(container.sessions, tabId) ?: return@launch
                            when (target) {
                                is CitationTarget.Article -> {
                                    nav.showTab(tab, fragment = target.fragment, quote = target.quote)
                                    if (target.moved) Toast.makeText(appContext, PAGE_MOVED, Toast.LENGTH_LONG).show()
                                }
                                is CitationTarget.Book -> nav.showTab(tab, target.at)
                                is CitationTarget.Album, is CitationTarget.Audiobook, CitationTarget.Gone -> Unit
                            }
                        }
                    },
                    onSearch = { keywords -> nav.navigate(SearchRoute(keywords)) },
                    onGetModels = { nav.navigate(SourceRoute(CatalogScope.MODELS)) },
                    librarian = container.librarian.ui.collectAsStateWithLifecycle(null).value,
                    onGetLibrarian = {
                        container.appScope.launch {
                            container.librarian.get()?.let { problem ->
                                withContext(Dispatchers.Main) { Toast.makeText(appContext, problem, Toast.LENGTH_LONG).show() }
                            }
                        }
                    },
                    onReadiness = { nav.navigate(ReadinessRoute) },
                    onOpenChat = { chatId -> openResearch(ResearchRoute(chatId), fromChats = true) },
                    onNewChat = { openResearch(ResearchRoute()) },
                    onBack = { nav.popBackStack() },
                )
            }
            composable<HistoryRoute> {
                HistoryScreen(
                    viewModel = viewModel {
                        HistoryViewModel(
                            container.sessions, container.library, container.bookshelf, container.railPins, container.railHidden,
                            forgetPlays = container.playback::deleteRecentPlays,
                            readingAloud = { if (Features.PLAYER) (container.playerHub.ui.value.now as? NowPlaying.Voice)?.sourceKey else null },
                            stopVoice = { if (Features.PLAYER) container.playerHub.stopVoice() },
                        )
                    },
                    onOpenTab = ::showTab,
                    onBack = { nav.popBackStack() },
                    // The page you came from: Back returns to it, so Clear history keeps that tab alone (Plan U3b Decision 7).
                    keepTabIds = { HistoryViewModel.keptTab(nav.currentBackStack.value.map { it.readingTabId() }) },
                    // A buried reader whose tab was closed must not come back on Back (Plan U3c walkthrough).
                    onCleared = nav::dropClosedReaders,
                )
            }
            composable<SourceRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<SourceRoute>()
                val source = route.scope
                SourceScreen(
                    viewModel = viewModel {
                        SourceViewModel(
                            source, container.catalog, container.downloads, container.library, container.bookshelf, container.volumes,
                            container.downloadPrefs, container.deviceTier, container.appScope, domain = route.domain,
                        )
                    },
                    title = source.title,
                    onBack = { nav.popBackStack() },
                    onOpenPack = ::openPack,
                )
            }
            composable<ArchiveRoute> { backStackEntry ->
                val kind = backStackEntry.toRoute<ArchiveRoute>().kind
                ArchiveScreen(
                    viewModel = viewModel {
                        ArchiveViewModel(
                            container.archiveClient, container.downloads, container.bookshelf, container.archivePrefs,
                            container.volumes, container.downloadPrefs, { container.archiveSidecarDir }, container.appScope,
                            kind = kind, media = container.mediaDownloader,
                        )
                    },
                    detailsUrl = container.archiveClient::detailsUrl,
                    onBack = { nav.popBackStack() },
                    onOpenLibriVox = { nav.openLibriVox() },
                    onOpenInLibrary = { identifier -> nav.navigate(inLibraryRoute(kind, identifier)) },
                )
            }
            composable<LibriVoxRoute> {
                LibriVoxScreen(
                    viewModel = viewModel {
                        LibriVoxViewModel(
                            container.librivoxClient, container.archiveClient, container.mediaDownloader, container.downloads,
                            container.appScope,
                        )
                    },
                    onBack = { nav.popBackStack() },
                )
            }
            composable<SettingsRoute> {
                SettingsScreen(
                    viewModel = viewModel {
                        SettingsViewModel(
                            container.downloads, container.volumes, container.downloadPrefs, container.archivePrefs, container.appearancePrefs,
                            container.appScope,
                            refreshVoices = { if (Features.PLAYER && Features.READ_ALOUD) container.installedVoices.refresh() },
                        )
                    },
                    onModels = { nav.navigate(SourceRoute(CatalogScope.MODELS)) },
                    onSetUpAgain = { nav.navigate(OnboardingRoute) { launchSingleTop = true } },
                    onBack = { nav.popBackStack() },
                    onVoice = if (Features.PLAYER && Features.READ_ALOUD) ({ nav.navigate(VoiceRoute) }) else null,
                    voiceSummary = container.installedVoices.voices.collectAsStateWithLifecycle().value
                        .let { voices -> container.voicePrefs.voiceId.collectAsStateWithLifecycle().value?.let { id -> voices.firstOrNull { v -> v.id == id } } ?: voices.firstOrNull() }
                        ?.title ?: "No offline voice on this phone",
                    onMediaSettings = if (Features.PLAYER) ({ nav.navigate(MediaSettingsRoute) { launchSingleTop = true } }) else null,
                    audiobooks = remember { container.shelves.audioShelves }.collectAsStateWithLifecycle(AudioShelves.NONE).value.audiobooks,
                )
            }
            composable<MediaSettingsRoute> {
                MediaSettingsScreen(
                    viewModel = viewModel {
                        SettingsViewModel(
                            container.downloads, container.volumes, container.downloadPrefs, container.archivePrefs, container.appearancePrefs,
                            container.appScope,
                            mediaSettings = if (Features.PLAYER) { vmScope -> mediaSettings(container, appContext, vmScope) } else null,
                        )
                    },
                    onBack = { nav.popBackStack() },
                    onPermissionChecked = { if (Features.PLAYER) container.phoneMediaScans.recheckPermission() },
                )
            }
            if (player != null) {
                composable<ListenPlayerRoute> {
                    val voices by container.installedVoices.voices.collectAsStateWithLifecycle()
                    ListenPlayerScreen(
                        player, onClose = { nav.popBackStack() }, onShelf = { nav.openShelfFromPlayer(it) },
                        voices = voices,
                        mixLevel = remember { container.playerPrefs.getFloat(PlayerSettingsKeys.MIX_LEVEL, PlayerSettingsKeys.DEFAULT_MIX) },
                        onOpenSource = ::openReadingFromPlayer,
                        // As the rail's Play again: the saved place, read again.
                        onResume = { last -> scope.launch { container.playResumer.resume("voice:${last.sourceKey}", last.title) } },
                    )
                }
            }
            composable<ListenRoute> {
                ListenScreen(
                    viewModel {
                        if (Features.PLAYER) ListenViewModel(container.shelves, container.playerHub, container.playerFocus) else ListenViewModel(container.shelves)
                    },
                    ListenActions(
                        onOpenPlayer = ::openPlayer,
                        // As the widget's play: the live item, else what the card says would resume.
                        onPlayPause = {
                            if (Features.PLAYER) scope.launch {
                                playFromWidget(
                                    container.playerHub, container.playerFocus,
                                    resumeVoice = { key, title -> container.playResumer.resume(key, title) },
                                )
                            }
                        },
                        onOpenMusic = { id -> nav.navigate(MusicRoute(id)) },
                        onOpenAudiobooks = { id -> nav.navigate(AudiobookShelfRoute(id)) },
                        onOpenCollection = { id -> nav.navigate(CollectionRoute(id)) },
                        onOpenSource = nav::openGetMore,
                        onFindOnPhone = { nav.navigate(MediaSettingsRoute) { launchSingleTop = true } },
                        onGetMore = { nav.navigate(GetMoreRoute) { launchSingleTop = true } },
                    ),
                )
            }
            composable<VoiceRoute> {
                VoiceSettingsScreen(
                    viewModel = viewModel {
                        VoiceSettingsViewModel(
                            container.voicePrefs, container.installedVoices, container.catalog, container.downloads, container.volumes,
                            container.downloadPrefs, container.appScope,
                        )
                    },
                    onBack = { nav.popBackStack() },
                )
            }
            composable<HomeRoute> {
                HomeScreen(
                    viewModel = viewModel {
                        HomeViewModel(
                            container.catalog, container.library, container.bookshelf, container.downloads, railViewModel.ui,
                            container.homeFeeds,
                            FeedDrawer(
                                archiveOf = { container.library.archive(it.id) },
                                chunksOf = container.bookIndex::chunks,
                                bookSearch = { container.bookIndex.search(it) },
                                librarySearch = container.search,
                                thumbnail = ArticleThumbnails(File(appContext.cacheDir, "home-thumbs"))::thumbnail,
                            ),
                            container.shelves, container.media, container.homePrefs,
                        )
                    },
                    onReadiness = { nav.navigate(ReadinessRoute) { launchSingleTop = true } },
                    onOpenDownloads = { nav.navigate(DownloadsRoute) { launchSingleTop = true } },
                    onOpenTab = ::showTab,
                    onPlay = ::resumePlay,
                    onOpenCard = { card ->
                        when (val target = card.target) {
                            is CardTarget.Article -> scope.launch {
                                val tabId = container.sessions.openTab(target.packId, target.path, card.title)
                                container.sessions.tab(tabId)?.let { nav.showTab(it, fragment = target.fragment) }
                            }
                            is CardTarget.Book -> scope.launch {
                                val tabId = container.sessions.openTab(target.bookId, "", card.title)
                                val at = target.href?.let { BookTarget(it, target.progression) }
                                container.sessions.tab(tabId)?.let { nav.showTab(it, at) }
                            }
                            is CardTarget.Album -> nav.navigate(AlbumRoute(target.albumKey))
                            is CardTarget.Audiobook -> nav.navigate(AudiobookRoute(target.audiobookId))
                            is CardTarget.Search -> nav.navigate(SearchRoute(target.query))
                        }
                    },
                )
            }
            composable<OnboardingRoute> {
                OnboardingScreen(
                    viewModel = viewModel {
                        OnboardingViewModel(
                            container.catalog, container.library, container.downloads, container.volumes, container.downloadPrefs,
                            container.deviceTier, container.totalRamBytes, container.onboardingPrefs,
                        )
                    },
                    onDone = { nav.popBackStack() },
                    onSeeDomain = { domain -> nav.navigate(SourceRoute(CatalogScope.KIWIX, domain)) },
                )
            }
            composable<DownloadsRoute> {
                DownloadsScreen(
                    viewModel = viewModel {
                        DownloadsViewModel(
                            container.downloads, container.library, container.bookshelf, container.sessions, container.appScope,
                            container.catalog, container.volumes,
                        )
                    },
                    onBack = { nav.popBackStack() },
                    onOpened = ::showTab,
                    onOpenPack = ::openPack,
                    onGetMore = { nav.navigate(GetMoreRoute) { launchSingleTop = true } },
                )
            }
            composable<GetMoreRoute> {
                GetMoreScreen(onOpen = nav::openGetMore, onBack = { nav.popBackStack() })
            }
            composable<ReadinessRoute> {
                ReadinessScreen(
                    viewModel = viewModel {
                        ReadinessViewModel(
                            container.downloads, container.library, container.bookshelf, container.catalog, container.volumes,
                            container.deviceTier, container.totalRamBytes, container.appScope,
                        )
                    },
                    onBack = { nav.popBackStack() },
                    onOpenKiwix = { domain -> nav.navigate(SourceRoute(CatalogScope.KIWIX, domain)) },
                )
            }
        }
    }
}

/**
 * Settings → Music & audiobooks over the app's scanner, folders and player prefs. Scans and folder changes run in the
 * app scope, so leaving Settings doesn't stop them.
 */
private fun mediaSettings(container: AppContainer, context: Context, scope: CoroutineScope) = MediaSettings(
    folders = DaoMediaFolders(container.playback),
    grants = ResolverFolderGrants(context.contentResolver),
    prefs = container.playerPrefs,
    sharing = ComponentOtherAppsSharing(context),
    scanner = {
        container.appScope.async {
            container.phoneScanner.scan()
            // Granger's downloads too: "Rescan now" means everything.
            container.mediaDownloader.rescan()
        }.await()
        ScanSummary.of(container.media)
    },
    scope = scope,
    work = container.appScope,
    audiobooks = container.shelves.audioShelves.map { it.audiobooks },
)
