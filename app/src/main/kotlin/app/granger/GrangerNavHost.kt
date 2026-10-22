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
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.granger.archive.ArchiveScreen
import app.granger.core.player.NowPlaying
import app.granger.core.speech.ReadSourceKey
import app.granger.core.player.PlayerMode
import app.granger.core.player.PlayerSettingsKeys
import app.granger.core.player.PlayerUi
import app.granger.player.MiniPlayer
import app.granger.player.VoiceSheet
import app.granger.player.MiniPlayerPlacement
import app.granger.player.PlayerScreen
import app.granger.player.PlayerViewModel
import app.granger.player.miniPlayerPlacement
import app.granger.archive.ArchiveViewModel
import app.granger.librivox.LibriVoxScreen
import app.granger.librivox.LibriVoxViewModel
import app.granger.core.archive.ArchiveKind
import app.granger.core.library.Albums
import app.granger.core.library.BookIds
import app.granger.core.library.MediaResults
import app.granger.core.media.MediaManifest
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
import app.granger.shell.RailEntryActions
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
import app.granger.research.citationTab
import app.granger.research.ResearchScreen
import app.granger.research.ResearchViewModel

/** [notice] is shown once as a snackbar, e.g. why the app didn't resume reading. */
@Serializable data class LibraryRoute(val notice: String? = null)
@Serializable data class CollectionRoute(val collectionId: String)

/** Music's tabs for one Music collection; null is "All music" (not scoped). */
@Serializable data class MusicRoute(val collectionId: String? = null)
@Serializable data class AlbumRoute(val albumKey: String)
@Serializable data class ArtistRoute(val name: String)
@Serializable data class GenreRoute(val name: String)
@Serializable data class PlaylistRoute(val playlistId: Long)

/** [folder] is a track's stored folder, as `Track.folder` holds it. */
@Serializable data class FolderRoute(val folder: String)

private fun NavDestination.isMusic() = hasRoute<MusicRoute>() || hasRoute<AlbumRoute>() || hasRoute<ArtistRoute>() ||
    hasRoute<GenreRoute>() || hasRoute<PlaylistRoute>() || hasRoute<FolderRoute>()

/** The Audiobooks shelf for one Audiobooks collection; null is "All audiobooks" (not scoped). */
@Serializable data class AudiobookShelfRoute(val collectionId: String? = null)
@Serializable data class AudiobookRoute(val audiobookId: String)

private fun NavDestination.isAudiobooks() = hasRoute<AudiobookShelfRoute>() || hasRoute<AudiobookRoute>()
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
/** [kind] picks the view: texts (Books), spoken word (Audiobooks) or music. */
@Serializable data class ArchiveRoute(val kind: ArchiveKind = ArchiveKind.TEXTS)
/** The LibriVox source: Recently added, six genres and search (Plan M2). */
@Serializable object LibriVoxRoute

fun NavController.openLibriVox() = navigate(LibriVoxRoute) { launchSingleTop = true }

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
@Serializable data class SettingsRoute(val media: Boolean = false)
@Serializable object ReadinessRoute
/** Settings → Voice (Plan V1). */
@Serializable object VoiceRoute

/** The rail tool a destination belongs to: archive sources are part of the Library, as is everything they lead to. */
internal fun railToolOf(destination: NavDestination): RailTool? = when {
    destination.hasRoute<LibraryRoute>() || destination.hasRoute<CollectionRoute>() || destination.hasRoute<ArchiveRoute>() ||
        destination.hasRoute<LibriVoxRoute>() || destination.isMusic() || destination.isAudiobooks() -> RailTool.LIBRARY
    destination.hasRoute<SearchRoute>() -> RailTool.SEARCH
    destination.hasRoute<ResearchRoute>() -> RailTool.RESEARCH
    destination.hasRoute<SettingsRoute>() || destination.hasRoute<ReadinessRoute>() ||
        destination.hasRoute<SourceRoute>() -> RailTool.SETTINGS
    destination.hasRoute<PlayerRoute>() -> RailTool.PLAYER
    else -> null
}

/** The full-screen player, music or audiobook, whichever is loaded (spec §8). */
@Serializable object PlayerRoute

/**
 * The Player tool: opens the full-screen player, or returns from it (to the mini-player) when it's on top. With the
 * player further down the back stack it is brought back up rather than stacked a second time.
 */
fun NavController.togglePlayer() {
    when {
        currentBackStackEntry?.destination?.hasRoute<PlayerRoute>() == true -> popBackStack()
        popBackStack<PlayerRoute>(inclusive = false) -> Unit
        else -> navigate(PlayerRoute) { launchSingleTop = true }
    }
}

/** Shows the full-screen player: nothing when it's on top, brought back up when further down, else opened. */
fun NavController.showPlayer() {
    if (currentBackStackEntry?.destination?.hasRoute<PlayerRoute>() == true) return
    if (!popBackStack<PlayerRoute>(inclusive = false)) navigate(PlayerRoute) { launchSingleTop = true }
}

/**
 * The player's shelf link: Music ([MusicRoute] for All music) or Audiobooks ([AudiobookShelfRoute] for all of them).
 * The player is closed first, so Back from the shelf returns to the screen the player was opened from, as closing the
 * player would; the mini-player keeps playing. When that screen already is the shelf, it is simply shown.
 */
fun NavController.openShelfFromPlayer(mode: PlayerMode) {
    popBackStack<PlayerRoute>(inclusive = true)
    val top = currentBackStackEntry
    val showing = when (mode) {
        PlayerMode.MUSIC -> top?.destination?.hasRoute<MusicRoute>() == true && top.toRoute<MusicRoute>().collectionId == null
        PlayerMode.AUDIOBOOK -> top?.destination?.hasRoute<AudiobookShelfRoute>() == true && top.toRoute<AudiobookShelfRoute>().collectionId == null
    }
    if (!showing) navigate(if (mode == PlayerMode.MUSIC) MusicRoute(null) else AudiobookShelfRoute(null))
}

/** How [route] is opened: [replacesChatOnTop] drops a Research chat on top first (its view model would otherwise stay). */
internal data class ResearchOpening(val route: ResearchRoute, val replacesChatOnTop: Boolean)

internal fun researchOpening(route: ResearchRoute, researchOnTop: Boolean) = ResearchOpening(route, researchOnTop)

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
fun GrangerNavHost(container: AppContainer, start: StartScreen, openPlayerRequests: Int = 0) {
    val nav = rememberNavController()
    val shell = rememberShellState()
    // True only for the first resume navigation; read when that navigation's transitions are chosen.
    var skipTransition by remember { mutableStateOf(false) }
    var showChromeRequest by rememberSaveable { mutableStateOf(0) }
    val currentTab = viewModel { CurrentTab() }
    val railViewModel = viewModel {
        RailViewModel(
            container.sessions, container.library, container.bookshelf, container.researchChats, currentTab.id,
            container.railPins, container.railHidden,
            plays = if (Features.PLAYER) container.playback.observeRecentPlays(RailViewModel.SCAN) else flowOf(emptyList()),
            recentPlay = container.playback::recentPlay,
            resumer = if (Features.PLAYER) container.playResumer else null,
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
            )
        }
    } else {
        null
    }
    val playerUi by (player?.ui ?: remember { MutableStateFlow(PlayerUi.IDLE) }).collectAsStateWithLifecycle()

    val entry by nav.currentBackStackEntryAsState()
    val destination = entry?.destination
    val readerTabId = entry?.readingTabId()
    LaunchedEffect(readerTabId) { currentTab.id.value = readerTabId }
    // Non-reader screens always show the rail; a reader shows it with its chrome (up on entry, a middle tap hides it).
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

    // The home-screen widget's body: each request (a new count) shows the Player, over whatever the launch opened.
    LaunchedEffect(openPlayerRequests) { if (openPlayerRequests > 0 && Features.PLAYER) nav.showPlayer() }

    // Every screen that opens a tab hands over its id; the tab decides which reader shows it.
    val scope = rememberCoroutineScope()
    // A song tapped in Search plays its album from there.
    val songs = container.songs
    fun showTab(tabId: Long) {
        scope.launch { container.sessions.tab(tabId)?.let { nav.showTab(it) } }
    }

    /** Opens the page the voice is reading (an article in its tab, a book in its reader); null when the key names neither. */
    suspend fun openVoiceSource(voice: NowPlaying.Voice): Unit? {
        val tabId = when (val key = ReadSourceKey.parse(voice.sourceKey)) {
            is ReadSourceKey.Article -> container.sessions.openTab(key.packId, key.path, voice.title)
            is ReadSourceKey.Book -> container.sessions.openTab(key.bookId, "", voice.title)
            is ReadSourceKey.Text, null -> return null
        }
        container.sessions.tab(tabId)?.let { nav.showTab(it) }
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
    /** Opens [word]'s Wiktionary entry over the book, so Back returns to the page; false when there is none. */
    suspend fun lookUpInWiktionary(packId: String, word: String): Boolean {
        val hit = withContext(Dispatchers.IO) { container.library.archive(packId)?.suggest(word, 5) }
            ?.let { Dictionary.pick(it, word) } ?: return false
        val tab = container.sessions.openTab(packId, hit.path, hit.title).let { container.sessions.tab(it) } ?: return false
        nav.showTab(tab, keepCurrent = true)
        return true
    }

    val selected = destination?.let(::railToolOf)

    val miniPlacement = miniPlayerPlacement(
        loaded = playerUi.now != NowPlaying.Idle,
        onPlayer = destination?.hasRoute<PlayerRoute>() == true,
        reading = readerTabId != null,
        railVisible = shell.railVisible,
    )

    // Research runs only on phones of the ASK tier or better; below that, the rail and the selection menus leave it out.
    val showResearch = container.deviceTier >= DeviceTier.ASK
    SideEffect {
        SelectionBridge.researchAvailable = showResearch
        SelectionBridge.readAloudAvailable = Features.READ_ALOUD && Features.PLAYER
    }
    /** Opens [route], replacing a Research chat on top (whose view model would otherwise stay); else pushes it. */
    fun openResearch(route: ResearchRoute) {
        val researchOnTop = nav.currentBackStackEntry?.destination?.hasRoute<ResearchRoute>() == true
        val open = researchOpening(route, researchOnTop)
        nav.navigate(open.route) { if (open.replacesChatOnTop) popUpTo<ResearchRoute> { inclusive = true } }
    }

    // The voice's full view (Plan V2): a sheet over whatever is on screen, opened from the mini-player; it closes by
    // itself when the voice is no longer loaded.
    var voiceSheet by rememberSaveable { mutableStateOf(false) }
    val voiceNow = playerUi.now as? NowPlaying.Voice
    if (voiceSheet && player != null && voiceNow != null) {
        val progress by player.voiceProgress.collectAsStateWithLifecycle()
        val voices by container.installedVoices.voices.collectAsStateWithLifecycle()
        VoiceSheet(
            ui = playerUi, progress = progress, voices = voices,
            mixLevel = remember { container.playerPrefs.getFloat(PlayerSettingsKeys.MIX_LEVEL, PlayerSettingsKeys.DEFAULT_MIX) },
            onPlayPause = player::playPause, onPreviousParagraph = player::previousParagraph, onNextParagraph = player::nextParagraph,
            onBackSentence = player::previousSentence, onSpeed = player::setSpeed, onVoice = player::setVoice,
            onMix = player::setMixLevel, onPlayPauseMusic = player::playPauseMusic, onNextMusic = player::nextMusic,
            onSleep = player::startSleepTimer, onCancelSleep = player::cancelSleepTimer,
            onStop = { player.stopVoice(); voiceSheet = false },
            onOpen = { voiceSheet = false; scope.launch { openVoiceSource(voiceNow) ?: nav.togglePlayer() } },
            onDismiss = { voiceSheet = false },
        )
    } else if (voiceSheet && voiceNow == null) {
        voiceSheet = false
    }
    GrangerShell(
        rail = rail,
        expanded = shell.expanded,
        railVisible = shell.railVisible,
        selected = selected,
        onToggleExpanded = { shell.expanded = !shell.expanded },
        onEdgeSwipe = {
            // Full-screen reader: the swipe brings the chrome (and with it the rail) up; elsewhere it opens the rail.
            if (readerTabId != null && !shell.railVisible) showChromeRequest++ else shell.expanded = !shell.expanded
        },
        onTool = { tool ->
            when (tool) {
                RailTool.SEARCH -> nav.navigate(SearchRoute()) { launchSingleTop = true }
                RailTool.LIBRARY -> if (!nav.popBackStack<LibraryRoute>(inclusive = false)) nav.navigate(LibraryRoute())
                RailTool.SETTINGS -> nav.navigate(SettingsRoute()) { launchSingleTop = true }
                RailTool.RESEARCH -> openResearch(ResearchRoute()) // A new chat, even while a kept one is open.
                RailTool.PLAYER -> nav.togglePlayer()
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
        onPlay = { item ->
            scope.launch {
                // Gone or unplayable: the Library, with a notice saying so (it replaces the Library on the stack).
                val notice = railViewModel.resume(item) ?: return@launch
                nav.navigate(LibraryRoute(notice)) { popUpTo<LibraryRoute> { inclusive = true } }
            }
        },
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
                onRemove = railViewModel::remove,
            )
        },
        side = railSide,
        showResearch = showResearch,
        reading = readerTabId != null,
        miniPlayer = if (player == null || miniPlacement == MiniPlayerPlacement.HIDDEN) {
            null
        } else {
            {
                MiniPlayer(
                    playerUi,
                    onPlayPause = player::playPause,
                    onSecondary = player::secondary,
                    // The voice has no Player tab: its full view is a sheet (Plan V2), whose Open goes to the page being read.
                    onOpen = { if (player.voiceNow() == null) nav.togglePlayer() else voiceSheet = true },
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
                            ),
                            container.appScope,
                        )
                    },
                    packsFolder = container.packDirs().firstOrNull()?.path.orEmpty(),
                    notice = backStackEntry.toRoute<LibraryRoute>().notice,
                    onOpenCollection = { id -> nav.navigate(CollectionRoute(id)) },
                    onOpenMusic = { id -> nav.navigate(MusicRoute(id)) },
                    onOpenAudiobooks = { id -> nav.navigate(AudiobookShelfRoute(id)) },
                    onOpenSource = nav::openSource,
                    onSearch = { nav.navigate(SearchRoute()) },
                    onHistory = { nav.navigate(HistoryRoute) },
                    onReadiness = { nav.navigate(ReadinessRoute) },
                    onMediaSettings = if (Features.PLAYER) ({ nav.navigate(SettingsRoute(media = true)) }) else null,
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
                onMediaSettings = { nav.navigate(SettingsRoute(media = true)) },
            )
            val removeDownload: (String) -> Unit = { group -> container.appScope.launch { container.mediaDownloader.remove(group) } }
            // The detail pages aren't scoped to a collection: their routes carry none.
            fun musicViewModel(collectionId: String?) =
                MusicViewModel(
                    collectionId, container.media, container.shelves, container.playlists, container.playerHub,
                    downloads = container.downloads.records, remover = removeDownload,
                )
            composable<MusicRoute> { backStackEntry ->
                val id = backStackEntry.toRoute<MusicRoute>().collectionId
                MusicScreen(viewModel { musicViewModel(id) }, musicNav)
            }
            composable<AlbumRoute> { backStackEntry ->
                AlbumScreen(viewModel { musicViewModel(null) }, backStackEntry.toRoute<AlbumRoute>().albumKey, musicNav)
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
                downloads = container.downloads.records, remover = removeDownload,
            )
            composable<AudiobookShelfRoute> { backStackEntry ->
                val id = backStackEntry.toRoute<AudiobookShelfRoute>().collectionId
                AudiobookShelfScreen(
                    viewModel { audiobooksViewModel(id) },
                    onBook = { nav.navigate(AudiobookRoute(it)) },
                    onBack = { nav.popBackStack() },
                    onOpenSource = nav::openSource,
                    onMediaSettings = { nav.navigate(SettingsRoute(media = true)) },
                )
            }
            composable<AudiobookRoute> { backStackEntry ->
                AudiobookScreen(
                    viewModel { audiobooksViewModel(null) },
                    backStackEntry.toRoute<AudiobookRoute>().audiobookId,
                    onBack = { nav.popBackStack() },
                )
            }
            composable<ReaderRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<ReaderRoute>()
                ReaderScreen(
                    viewModel = viewModel {
                        ReaderViewModel(
                            route.tabId, container.library, container.sessions, container.appScope, fragment = route.fragment,
                            player = if (Features.PLAYER && Features.READ_ALOUD) container.playerHub else null,
                        )
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
                            player = if (Features.PLAYER && Features.READ_ALOUD) container.playerHub else null,
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
                        )
                    },
                    focusInput = route.quote != null,
                    onOpenCitation = { target ->
                        // Pushed over the chat (Research isn't a reader), so Back returns to it.
                        scope.launch {
                            val tabId = when (target) {
                                is CitationTarget.Article -> target.tabId
                                is CitationTarget.Book -> target.tabId
                                is CitationTarget.Album -> return@launch nav.navigate(AlbumRoute(target.albumKey))
                                is CitationTarget.Audiobook -> return@launch nav.navigate(AudiobookRoute(target.audiobookId))
                                CitationTarget.Gone -> return@launch
                            }
                            val tab = citationTab(container.sessions, tabId) ?: return@launch
                            when (target) {
                                is CitationTarget.Article -> nav.showTab(tab, fragment = target.fragment)
                                is CitationTarget.Book -> nav.showTab(tab, target.at)
                                is CitationTarget.Album, is CitationTarget.Audiobook, CitationTarget.Gone -> Unit
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
            composable<SettingsRoute> { backStackEntry ->
                SettingsScreen(
                    viewModel = viewModel {
                        SettingsViewModel(
                            container.downloads, container.volumes, container.downloadPrefs, container.archivePrefs, container.appearancePrefs,
                            container.appScope,
                            mediaSettings = if (Features.PLAYER) { vmScope -> mediaSettings(container, appContext, vmScope) } else null,
                        )
                    },
                    onModels = { nav.navigate(SourceRoute(CatalogScope.MODELS)) },
                    onReadiness = { nav.navigate(ReadinessRoute) },
                    onBack = { nav.popBackStack() },
                    onVoice = if (Features.PLAYER && Features.READ_ALOUD) ({ nav.navigate(VoiceRoute) }) else null,
                    voiceSummary = container.installedVoices.voices.collectAsStateWithLifecycle().value
                        .let { voices -> container.voicePrefs.voiceId.collectAsStateWithLifecycle().value?.let { id -> voices.firstOrNull { v -> v.id == id } } ?: voices.firstOrNull() }
                        ?.title ?: "No offline voice on this phone",
                    scrollToMedia = backStackEntry.toRoute<SettingsRoute>().media,
                    onPermissionChecked = { if (Features.PLAYER) container.phoneMediaScans.recheckPermission() },
                )
            }
            if (player != null) {
                composable<PlayerRoute> {
                    PlayerScreen(player, onClose = { nav.popBackStack() }, onShelf = { nav.openShelfFromPlayer(it) })
                }
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
)
