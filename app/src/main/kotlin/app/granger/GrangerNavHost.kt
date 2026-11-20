package app.granger

import app.granger.feedback.FeedbackFacts
import app.granger.feedback.FeedbackMail
import app.granger.feedback.FeedbackSheet
import app.granger.home.CrashNotice
import android.content.ActivityNotFoundException
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
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
import app.granger.core.library.isBookPack
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
import app.granger.explore.ExploreScreen
import app.granger.explore.ExploreViewModel
import app.granger.explore.SubjectScreen
import app.granger.explore.SubjectViewModel
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
import app.granger.search.WordEntry
import android.content.Context
import app.granger.media.PlayResumer
import app.granger.settings.ComponentOtherAppsSharing
import app.granger.settings.DaoMediaFolders
import app.granger.settings.MediaSettings
import app.granger.settings.MediaSettingsScreen
import app.granger.settings.ResolverFolderGrants
import app.granger.settings.ScanSummary
import app.granger.settings.SettingsScreen
import app.granger.settings.LanguageSettingsScreen
import app.granger.settings.VoiceSettingsScreen
import app.granger.settings.languageLabel
import app.granger.settings.VoiceSettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
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
import app.granger.home.GameKind
import app.granger.home.GameModel
import app.granger.home.GameStore
import app.granger.home.GamesScreen
import app.granger.home.GamesViewModel
import app.granger.home.gameActions
import app.granger.home.TimelineModel
import app.granger.home.TimelineScreen
import app.granger.home.TimelineViewModel
import app.granger.home.timelineActions
import app.granger.home.WanderModel
import app.granger.core.library.TimeIndex
import app.granger.core.library.TimeIndexCache
import app.granger.core.library.TimeIndexDirectory
import app.granger.core.library.TimeIndexes
import app.granger.onboarding.OnboardingScreen
import app.granger.onboarding.OnboardingViewModel
import app.granger.shell.RailEntry
import app.granger.shell.RailEntryActions
import app.granger.shell.RailTool
import app.granger.shell.RailViewModel
import app.granger.shell.rememberShellState
import app.granger.sources.CatalogScope
import app.granger.sources.GetMoreFor
import app.granger.sources.GetMoreLink
import app.granger.sources.GetMoreScreen
import app.granger.sources.SourceScreen
import app.granger.sources.SourceViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import app.granger.sources.bookSourceShelves
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import app.granger.core.books.TextQuote
import app.granger.core.ai.DeviceTier
import app.granger.core.ai.ResearchReadiness
import app.granger.core.research.Passage
import app.granger.core.research.SourceKind
import app.granger.research.CitationTarget
import app.granger.search.BestMatchSearch
import app.granger.research.citationTab
import app.granger.research.ResearchScreen
import app.granger.research.spokenOutcome
import app.granger.research.ResearchViewModel

/** [notice] is shown once as a snackbar, e.g. why the app didn't resume reading; [noticeTitle] is the item it names. */
@Serializable data class LibraryRoute(val notice: app.granger.launch.LibraryNotice? = null, val noticeTitle: String? = null)

/** The Library route's notice in words. */
internal fun LibraryRoute.noticeText(): UiText? = notice?.let { UiText.Res(it.text, *listOfNotNull(noticeTitle).toTypedArray()) }

/** Home (Plan H2): the readiness strip, Continue and the feeds. A rail tool, pushed over the Library. */
@Serializable object HomeRoute
/** The full-screen Timeline, opened from Home's glance on the article its lens was on (Plan H5b). */
@Serializable data class TimelineRoute(val packId: String? = null, val path: String? = null)
/** The Games screen (Plan H5c): both games on the time index, opened from Home's Play. */
/** How long after the app opens Explore's counts are read in the background: after the first screen has drawn. */
private const val EXPLORE_WARM_AFTER = 1_500L

@Serializable data object GamesRoute

/** Onboarding (Plan H3): five screens over Home on a first run, or from Settings. */
/** First run; [outfit] opens straight at Outfit for offline, from Settings (Plan OB call 4). */
@Serializable data class OnboardingRoute(val outfit: Boolean = false)
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
/**
 * [domain] filters the Kiwix list to a readiness domain (Plan H1); [family] is one folded family's A–Z list (Plan KB
 * Decision 10); null for both is the whole catalog.
 */
@Serializable data class SourceRoute(val scope: CatalogScope, val domain: String? = null, val family: String? = null, val set: String? = null)
/** [kind] picks the view: texts (Books), spoken word (Audiobooks) or music. */
@Serializable data class ArchiveRoute(val kind: ArchiveKind = ArchiveKind.TEXTS)
/** The LibriVox source: Recently added, six genres and search (Plan M2). */
@Serializable object LibriVoxRoute

fun NavController.openLibriVox() = navigate(LibriVoxRoute) { launchSingleTop = true }

/**
 * Where sources live: Kiwix, books, audiobooks and music, models (Plan U3b Decision 2). [only] is one tool's sources:
 * the Library's books, Listen's audio (Plan KB walkthrough 2); null is all of them.
 */
@Serializable data class GetMoreRoute(val only: GetMoreFor? = null)

/** The Library entry's saved-state key: Get books' Import your own asked for the file picker. */
internal const val IMPORT_ASKED = "importAsked"

/** Get books' Import your own: back to the Library, which opens the picker (its import shows its notice there). */
fun NavController.importIntoLibrary() {
    val library = runCatching { getBackStackEntry<LibraryRoute>() }.getOrNull() ?: return
    library.savedStateHandle[IMPORT_ASKED] = true
    popBackStack<LibraryRoute>(inclusive = false)
}

/** Where each Get more row goes: today's source screens. */
internal fun routeOf(link: GetMoreLink): Any = when (link) {
    GetMoreLink.KIWIX -> SourceRoute(CatalogScope.KIWIX)
    GetMoreLink.BOOKS -> GetMoreRoute(GetMoreFor.BOOKS)
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
/** Settings → Appearance → Language (Plan L2 Task 3). */
@Serializable object LanguageRoute

/**
 * The rail tool a destination belongs to. Get more and its sources are part of the Library, as is everything they lead
 * to, except Models ([sourceScope] is a [SourceRoute]'s scope), which stays with Settings (Plan U3b Decision 2), and
 * Kiwix, whose packs are Browse's. Browse also lights while a pack is read; a book being read lights the Library (Plan
 * U3c Decision 1). Music, audiobooks, the player and the audio sources (LibriVox, and Archive.org's spoken word and
 * music: [archiveKind] is an [ArchiveRoute]'s kind) are Listen's (Plan U2 Decision 4). Downloads and Readiness cover
 * everything, and stay the Library's.
 */
internal fun railToolOf(
    destination: NavDestination, sourceScope: CatalogScope? = null, archiveKind: ArchiveKind? = null, getMoreFor: GetMoreFor? = null,
): RailTool? = when {
    destination.hasRoute<HomeRoute>() || destination.hasRoute<OnboardingRoute>() || destination.hasRoute<TimelineRoute>() || destination.hasRoute<GamesRoute>() -> RailTool.HOME
    destination.hasRoute<SourceRoute>() -> when (sourceScope) {
        CatalogScope.MODELS -> RailTool.SETTINGS
        CatalogScope.KIWIX -> RailTool.BROWSE
        else -> RailTool.LIBRARY
    }
    destination.hasRoute<BrowseRoute>() || destination.hasRoute<ReaderRoute>() || destination.hasRoute<PackFilesRoute>() ||
        destination.hasRoute<ExploreRoute>() || destination.hasRoute<SubjectRoute>() || destination.hasRoute<SubjectGamesRoute>() ||
            destination.hasRoute<TopicsRoute>() -> RailTool.BROWSE
    destination.hasRoute<BookRoute>() -> RailTool.LIBRARY
    destination.hasRoute<GetMoreRoute>() && getMoreFor == GetMoreFor.LISTEN -> RailTool.LISTEN
    destination.hasRoute<ArchiveRoute>() -> if (archiveKind == null || archiveKind == ArchiveKind.TEXTS) RailTool.LIBRARY else RailTool.LISTEN
    destination.hasRoute<ListenRoute>() || destination.hasRoute<ListenPlayerRoute>() || destination.hasRoute<LibriVoxRoute>() ||
        destination.isMusic() || destination.isAudiobooks() -> RailTool.LISTEN
    destination.hasRoute<LibraryRoute>() || destination.hasRoute<CollectionRoute>() ||
        destination.hasRoute<DownloadsRoute>() || destination.hasRoute<GetMoreRoute>() || destination.hasRoute<ReadinessRoute>() -> RailTool.LIBRARY
    destination.hasRoute<SearchRoute>() -> RailTool.SEARCH
    destination.hasRoute<ResearchRoute>() -> RailTool.RESEARCH
    destination.hasRoute<SettingsRoute>() || destination.hasRoute<MediaSettingsRoute>() || destination.hasRoute<LanguageRoute>() -> RailTool.SETTINGS
    else -> null
}

/** Browse's home (Plan U3c Decision 1): the packs and the maps, each a row opening its main page. The tool's landing. */
@Serializable object BrowseRoute
/** Files in this pack (Plan KB2 Task 4.6): a website pack's PDFs and EPUBs. */
@Serializable data class PackFilesRoute(val packId: String)

/** Plan BK: a book pack's collection read online, before (or without) the pack; [packId] is its catalog id. */
@Serializable data class RemoteCollectionRoute(val packId: String)

/** Explore (Plan EX, with [Features.EXPLORE]): the rail's Browse tool lands here; Your sources leads on to [BrowseRoute]. */
@Serializable object ExploreRoute

/** One subject opened from Explore (Plan EX): [subject] is its subjects.json id. */
@Serializable data class SubjectRoute(val subject: String)

/** A subject's quiz (Plan EX Task 3): the Games screen, dealing only that subject's dated articles. */
@Serializable data class SubjectGamesRoute(val subject: String)

/** A subject's articles as a mosaic (Plan EX editorial pass): one sub-category's ([facet]), or the whole subject's (null). */
@Serializable data class TopicsRoute(val subject: String, val facet: String? = null)

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
fun GrangerNavHost(container: AppContainer, start: StartScreen, openPlayerRequests: Int = 0, openDownloadsRequests: Int = 0, openOnlineTitle: String? = null) {
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
            showChats = container.askOn,
            voice = if (Features.PLAYER && Features.READ_ALOUD) container.playerHub.ui.map { it.now as? NowPlaying.Voice } else flowOf(null),
            stopVoice = { if (Features.PLAYER) container.playerHub.stopVoice() },
            knownPacks = container.knownPacks,
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
                chooseVoiceSpeed = container.voicePrefs::setSpeed,
                focus = container.playerFocus,
                playlists = container.playlists,
            )
        }
    } else {
        null
    }
    // The player's one-off lines (a bookmark kept, a queue that couldn't play), wherever the user is.
    val noticeContext = LocalContext.current.applicationContext
    LaunchedEffect(player) {
        player?.notices?.collect { Toast.makeText(noticeContext, it.resolve(noticeContext), Toast.LENGTH_LONG).show() }
    }
    val playerUi by (player?.ui ?: remember { MutableStateFlow(PlayerUi.IDLE) }).collectAsStateWithLifecycle()
    // An article opened by its topic that can't open now: what it would take, instead (Plan O3 Task 6).
    var wouldTake by remember { mutableStateOf<app.granger.online.WouldTake?>(null) }
    suspend fun openTopic(packId: String, path: String, title: String): Long? {
        if (!container.opensNow(packId, path)) {
            wouldTake = container.wouldTake(packId, path, title)
            return null
        }
        return container.openArticle(packId, path, title)
    }
    wouldTake?.let { take ->
        app.granger.online.WouldTakeSheet(
            take, onKeepLater = { container.keepLater(take.packId, take.path) }, onGetPack = { container.getPack(it) }, onDismiss = { wouldTake = null },
        )
    }
    val miniUi by (player?.mini ?: remember { MutableStateFlow(PlayerUi.IDLE) }).collectAsStateWithLifecycle()
    // The internet can't be reached (Local only or no connection): the banner shows under the mini player (Plan O3).
    val offline = container.reachControl?.state?.collectAsStateWithLifecycle()?.value?.online == false

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
                nav.navigate(OnboardingRoute())
            }
        }
    }

    // The home-screen widget's body: each request (a new count) shows the Player, over whatever the launch opened.
    // The widget's tap: the player on what's live, as Listen's Now playing card opens it.
    LaunchedEffect(openPlayerRequests) { if (openPlayerRequests > 0 && Features.PLAYER) { player?.resetTab(); nav.showPlayer() } }
    // A download notification's tap: Downloads, over whatever the launch opened.
    LaunchedEffect(openDownloadsRequests) { if (openDownloadsRequests > 0) nav.navigate(DownloadsRoute) { launchSingleTop = true } }
    // Debug only (Plan O1): open a live Wikipedia article (`--es online_title Moon`).
    LaunchedEffect(openOnlineTitle) {
        val title = openOnlineTitle ?: return@LaunchedEffect
        val pack = app.granger.core.online.OnlineWikipediaArchive.packName("en")
        container.sessions.tab(container.sessions.openTab(pack, title.replace(' ', '_'), title))?.let { nav.showTab(it, keepCurrent = true) }
    }
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
                // A storage error leaves the voice playing rather than crashing the app.
                val chatId = runCatching { container.researchChats.turn(turnId) }.getOrNull()?.chatId ?: return null
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

    /** The timeline's model, for Home's glance and the full screen (Plan H5b); [startAt] is an article to open on. */
    /** The phone's time indexes, shared with the topic page (the container keeps them open). */
    suspend fun timeIndexes(): TimeIndexes = container.timeIndexes()

    /** The time indexes the volunteered screens deal from: offline, without Wikipedia online's (Plan O3 Task 5). */
    suspend fun volunteeredTimeIndexes(): TimeIndexes = container.timeIndexes().without(container.offlineSources())

    /**
     * An article's picture for a screen Granger fills by itself (Timeline, games, a subject's quiz): none for an item the
     * adult-content filter flags. Blocking.
     */
    fun volunteeredThumbnail(thumbs: ArticleThumbnails, packId: String, path: String): String? {
        if (container.contentFilter.noPicture(packId, path)) return null
        val archive = container.library.archive(packId) ?: return null
        // Online, the summary's picture: one small request, not the whole article (Plan O1 Pixel pass).
        if (archive.remote) return container.articleCards.card(archive, path)?.thumbnail
        return archive.read(path)?.let { thumbs.thumbnail(archive, path, String(it.bytes, Charsets.UTF_8)) }
    }

    fun timelineModel(scope: kotlinx.coroutines.CoroutineScope, thumbs: ArticleThumbnails, startAt: Pair<String, String>? = null) = TimelineModel(
        scope = scope,
        indexes = { volunteeredTimeIndexes() },
        picture = { packId, path -> volunteeredThumbnail(thumbs, packId, path) },
        startAt = startAt,
    )

    // Explore (Plan EX): the QID indexes' subjects, the time indexes' eras and the packs, as Browse lists them.
    val exploreSources = remember {
        val thumbs = ArticleThumbnails(File(appContext.cacheDir, "home-thumbs"))
        app.granger.explore.ExploreSources(
            qids = { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { container.referenceLinks.qidIndexes() } },
            times = { timeIndexes() },
            picture = { packId, path ->
                if (container.contentFilter.noPicture(packId, path)) null
                else container.library.archive(packId)?.let { archive -> container.articleCards.card(archive, path)?.thumbnail }
            },
            packs = kotlinx.coroutines.flow.combine(
                container.library.packs, container.catalog.state, container.downloads.records,
            ) { packs, state, records ->
                packs.filterNot { it.isBookPack }.map { pack ->
                    // A map joins no door (the Atlas is LL6); it stays under Your sources.
                    val map = app.granger.core.library.Filing.defaultKey(pack) == "maps"
                    pack to if (map) null else app.granger.browse.installedEntry(pack, state?.catalog, records)?.domain
                }
            },
            isWikipedia = { it.name.startsWith("wikipedia_") },
            load = { container.catalog.load() },
            roomDescription = app.granger.explore.RoomDescriptions(appContext.assets)::of,
            figure = { packId, image -> container.library.archive(packId)?.let { thumbs.picture(it, image, 800) } },
            seen = container.seen,
            onlineIndex = if (!Features.ONLINE) kotlinx.coroutines.flow.flowOf(app.granger.online.IndexWait.NONE) else kotlinx.coroutines.flow.combine(
                container.downloads.records, container.unmetered,
            ) { records, unmetered -> app.granger.online.OnlineIndexes.waitOf(records, unmetered) },
            // Book collections join subjects by the catalog's own `subjects` (KB2): book packs, Classics and book sets.
            collections = kotlinx.coroutines.flow.combine(
                container.library.packs, container.bookshelf.books, container.catalog.state, container.downloads.records,
            ) { packs, books, state, records ->
                val catalog = state?.catalog
                val bookSubjects = HashMap<String, List<String>>()
                catalog?.books?.forEach { bookSubjects[it.id] = it.subjects }
                catalog?.bookSets?.forEach { set -> set.books.forEach { bookSubjects[it.id] = it.subjects.ifEmpty { set.subjects } } }
                val members = packs.filter { it.isBookPack }.mapNotNull { pack ->
                    app.granger.browse.installedEntry(pack, catalog, records)?.subjects?.takeIf { it.isNotEmpty() }?.let {
                        val key = app.granger.core.library.Filing.defaultKey(pack)
                        app.granger.explore.CollectionMember("c:$key", app.granger.core.library.Filing.defaultName(key, pack), it, pack = true)
                    }
                } + books.filter { it.origin == app.granger.core.library.BookOrigin.GRANGER }.mapNotNull { book ->
                    bookSubjects[book.sourceRef]?.takeIf { it.isNotEmpty() }?.let {
                        val key = app.granger.core.library.Filing.defaultKey(book)
                        app.granger.explore.CollectionMember("c:$key", app.granger.core.library.Filing.defaultName(key, book, packs), it)
                    }
                }
                app.granger.explore.collectionsBySubject(members)
            }.flowOn(kotlinx.coroutines.Dispatchers.Default),
            look = { packId, path ->
                container.library.archive(packId)?.let { archive ->
                    app.granger.Perf.time("look $path") { container.articleCards.card(archive, path, targetPx = 800) }
                        ?.let {
                            val thumbnail = it.thumbnail.takeUnless { _ -> container.contentFilter.noPicture(packId, path) }
                            app.granger.explore.ArticleLook(thumbnail, it.summary.takeIf { s -> s.isNotBlank() })
                        }
                }
            },
        )
    }
    // What Explore's landing counts is read a moment after the app opens, so the landing doesn't wait for it (the performance pass).
    androidx.compose.runtime.LaunchedEffect(exploreSources) {
        kotlinx.coroutines.delay(EXPLORE_WARM_AFTER)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            app.granger.Perf.time("explore.warm") { runCatching { app.granger.explore.ExploreViewModel.warm(exploreSources) } }
        }
    }
    val exploreActions = app.granger.explore.ExploreActions(
        onGetIndexNow = { scope.launch { container.onlineIndexes.startNow(container.downloads.records.first()) } },
        onOpenSubject = { nav.navigate(SubjectRoute(it.id)) },
        onOpenArticle = { packId, path, title ->
            scope.launch { openTopic(packId, path, title)?.let { container.sessions.tab(it) }?.let { nav.showTab(it, keepCurrent = true) } }
        },
        onOpenTimeline = { packId, path -> nav.navigate(TimelineRoute(packId, path)) },
        onOpenSources = { nav.navigate(BrowseRoute) { launchSingleTop = true } },
        onOpenPack = { packId -> openPack(packId, newTab = true) },
        onGetMore = { nav.navigate(SourceRoute(CatalogScope.KIWIX)) },
        onBack = { nav.popBackStack() },
        onWanderAt = { packId, path ->
            container.wanderFrom.value = packId to path
            if (!nav.popBackStack<HomeRoute>(inclusive = false)) nav.navigate(HomeRoute) { launchSingleTop = true }
        },
        onOpenGames = { nav.navigate(GamesRoute) { launchSingleTop = true } },
    )

    // Play and the Games screen (Plan H5c) share one count of rounds played and Which came first?'s level.
    val gameStore = remember { GameStore.Prefs(appContext.getSharedPreferences("games", android.content.Context.MODE_PRIVATE)) }
    // Home's round, for the Games screen's Continue to pick up (Plan HY); set when Home's view model makes it.
    val homeGames = remember { arrayOfNulls<GameModel>(1) }
    fun gameModel(scope: kotlinx.coroutines.CoroutineScope, thumbs: ArticleThumbnails, mixed: Boolean, start: GameKind? = null) = GameModel(
        scope = scope,
        indexes = { volunteeredTimeIndexes() },
        picture = { packId, path -> volunteeredThumbnail(thumbs, packId, path) },
        store = gameStore,
        mixed = mixed,
        start = start,
    )

    /**
     * A subject's quiz (Plan EX Task 3): the same games, dealt only from the dated articles whose main subject it is; its own count and
     * best runs, kept apart from the main games'.
     */
    fun subjectGameModel(scope: kotlinx.coroutines.CoroutineScope, thumbs: ArticleThumbnails, subject: String): GameModel {
        var set: app.granger.core.library.SubjectSet? = null
        return GameModel(
            scope = scope,
            indexes = {
                val s = set ?: container.referenceLinks.qidIndexes().subjectSet(subject).also { set = it }
                // Articles whose main subject it is: countries carry History second, and a History quiz isn't El Salvador vs Russia.
                timeIndexes().only { s.isMain(it) }
            },
            picture = { packId, path -> volunteeredThumbnail(thumbs, packId, path) },
            store = GameStore.Prefs(appContext.getSharedPreferences("games_$subject", android.content.Context.MODE_PRIVATE)),
            mixed = false,
        )
    }

    // Send feedback and the crash log (Plan F1 part 2): one sheet, opened from Settings → About or Home's crash line.
    val activityContext = LocalContext.current
    val crashLog = remember { (appContext as? GrangerApp)?.crashLog }
    val crashNotice = crashLog?.notice?.let { it.collectAsStateWithLifecycle().value }
    var feedbackOpen by rememberSaveable { mutableStateOf(false) }
    var crashesCleared by remember { mutableStateOf(0) }
    val crashReports = remember(feedbackOpen, crashNotice, crashesCleared) { crashLog?.list()?.size ?: 0 }
    if (feedbackOpen && crashLog != null) {
        val facts by produceState<FeedbackFacts?>(null, crashesCleared) { value = FeedbackMail.facts(container, crashLog) }
        FeedbackSheet(
            facts,
            onSend = { withCrashes ->
                facts?.let { f ->
                    val email = FeedbackMail.intent(activityContext, f, withCrashes)
                    try {
                        // No mail app: the same email to anything that can send it.
                        activityContext.startActivity(
                            FeedbackMail.toMailApps(email, FeedbackMail.mailApps(activityContext)) ?: FeedbackMail.shareInstead(email),
                        )
                        crashLog.acknowledge()
                        feedbackOpen = false
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(activityContext, activityContext.getString(R.string.feedback_no_mail_app, BuildConfig.FEEDBACK_EMAIL), Toast.LENGTH_LONG).show()
                    }
                }
            },
            onClearCrashes = {
                crashLog.clear()
                crashesCleared++
            },
            onDismiss = { feedbackOpen = false },
        )
    }
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
        // A book pack's file opens as its listed row (Plan GB, Plan KB Decision 4), copied out by the reader on its first
        // open, whatever its type.
        container.packBookLinks.bookFor(packId, path)?.let { book ->
            val tab = container.sessions.openTab(book.id, "", book.title).let { container.sessions.tab(it) } ?: return
            nav.showTab(tab, keepCurrent = true)
            return
        }
        if (path.endsWith(".epub", ignoreCase = true)) {
            val book = container.gutenbergBooks.bookFor(packId, path)
            if (book == null) {
                android.widget.Toast.makeText(appContext, R.string.app_book_not_in_pack, android.widget.Toast.LENGTH_LONG).show()
                return
            }
            val tab = container.sessions.openTab(book.id, "", book.title).let { container.sessions.tab(it) } ?: return
            nav.showTab(tab, keepCurrent = true)
            return
        }
        when (val opened = container.packDocuments.open(packId, path, linkText)) {
            is PackDocuments.Opened.Ready -> {
                val tab = container.sessions.openTab(opened.book.id, "", opened.book.title).let { container.sessions.tab(it) } ?: return
                nav.showTab(tab, keepCurrent = true)
            }
            is PackDocuments.Opened.Failed -> android.widget.Toast.makeText(appContext, opened.message, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Opens an installed pack: a book pack's Library collection of its books (Plan GB Decision 4, Plan KB Decision 6),
     * any other pack in the reader.
     */
    /** Plan BK: whether catalog pack [id] is a book pack whose collection can be read online (it has a list). */
    fun canBrowseOnline(id: String): Boolean =
        Features.ONLINE && container.catalog.state.value.catalog?.packs?.firstOrNull { it.id == id }
            ?.sidecars?.any { it.kind == app.granger.core.catalog.SidecarEntry.GBOOKS } == true

    fun openPackOrCollection(packId: String) {
        scope.launch {
            val pack = container.library.packs.first().firstOrNull { it.id == packId }
            if (pack == null || !pack.isBookPack) {
                openPack(packId)
                return@launch
            }
            nav.navigate(CollectionRoute("c:${app.granger.core.library.Filing.defaultKey(pack)}"))
        }
    }

    /**
     * Opens [word]'s Wiktionary entry over the book, so Back returns to the page, at the section for the book's language
     * (Plan L3: the Wiktionary [Dictionary.choose] picks for a book in [bookLanguage]; [packId] when it picks none).
     */
    suspend fun lookUpInWiktionary(packId: String, word: String, bookLanguage: String?): app.granger.ebook.LookUp {
        val definer = Dictionary.choose(container.library.packs.first(), bookLanguage, container.knowledgeNow.value)
        val id = definer?.packId ?: packId
        val hit = withContext(Dispatchers.IO) { container.library.archive(id)?.suggest(word, 5) }
            ?.let { Dictionary.pick(it, word) } ?: return app.granger.ebook.LookUp.NotFound
        val tab = container.sessions.openTab(id, hit.path, hit.title).let { container.sessions.tab(it) } ?: return app.granger.ebook.LookUp.NotFound
        nav.showTab(tab, keepCurrent = true, fragment = definer?.anchor)
        return app.granger.ebook.LookUp.Found(
            definer?.takeIf { it.fallback }?.let {
                UiText.Res(R.string.ebook_defined_in, app.granger.sources.KiwixList.languageName(it.wiktionaryLanguage))
            },
        )
    }

    val selected = entry?.let { e ->
        railToolOf(
            e.destination,
            sourceScope = if (e.destination.hasRoute<SourceRoute>()) e.toRoute<SourceRoute>().scope else null,
            archiveKind = if (e.destination.hasRoute<ArchiveRoute>()) e.toRoute<ArchiveRoute>().kind else null,
            getMoreFor = if (e.destination.hasRoute<GetMoreRoute>()) e.toRoute<GetMoreRoute>().only else null,
        )
    }

    // A spoken Ask reply belongs to its chat (Ask has its own speaker button): no mini-player, no rail row, and it
    // stops once Ask is left.
    val askReplySpoken = (playerUi.now as? NowPlaying.Voice)?.let { ResearchViewModel.answerTurnId(it.sourceKey) } != null
    val onAsk = destination?.hasRoute<ResearchRoute>() == true
    LaunchedEffect(askReplySpoken, onAsk) {
        if (askReplySpoken && !onAsk && destination != null) player?.stopVoice()
    }

    val miniPlacement = miniPlayerPlacement(
        loaded = playerUi.now != NowPlaying.Idle && !askReplySpoken,
        // Listen's home has the Now playing card instead.
        onPlayer = destination?.hasRoute<ListenPlayerRoute>() == true || destination?.hasRoute<ListenRoute>() == true,
        reading = readerTabId != null,
    )

    // Research runs only where Ask is on (the feature, and a phone of the ASK tier or better); elsewhere the rail and the
    // selection menus leave it out. So does a phone whose model failed its self-check; Readiness says why and can try again.
    val askReadiness by container.modelManager.readiness.collectAsStateWithLifecycle()
    val showResearch = container.askOn && askReadiness != ResearchReadiness.FAILED_CHECK
    SideEffect {
        SelectionBridge.researchAvailable = showResearch
        SelectionBridge.readAloudAvailable = Features.READ_ALOUD && Features.PLAYER
    }
    /** Opens where a cited passage (Ask) or a Best match (Search, Plan SQ) leads, pushed over the screen it came from. */
    fun openCitation(target: CitationTarget) {
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
                    if (target.moved) Toast.makeText(appContext, R.string.app_page_moved, Toast.LENGTH_LONG).show()
                }
                is CitationTarget.Book -> nav.showTab(tab, target.at)
                is CitationTarget.Album, is CitationTarget.Audiobook, CitationTarget.Gone -> Unit
            }
        }
    }
    val matchOpener = remember { app.granger.research.CitationOpener(container.sessions, container.library, container.bookshelf, container.mediaIndex) }

    /** Opens a book the Linked Library found (an article's footer, a link's card) at its saved place, over this screen. */
    fun openLinkedBook(bookId: String) {
        scope.launch { openCitation(matchOpener.open(app.granger.core.library.PassageAddress.Book(bookId, "", 0.0))) }
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
            nav.navigate(LibraryRoute(notice, item.title)) { popUpTo<LibraryRoute> { inclusive = true } }
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
                RailTool.BROWSE -> if (Features.EXPLORE) {
                    if (!nav.popBackStack<ExploreRoute>(inclusive = false)) nav.navigate(ExploreRoute) { launchSingleTop = true }
                } else if (!nav.popBackStack<BrowseRoute>(inclusive = false)) nav.navigate(BrowseRoute) { launchSingleTop = true }
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
                        Toast.makeText(appContext, notice.resolve(appContext), Toast.LENGTH_LONG).show()
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
        // The mini player's place at the top, and under it the Local only / no connection banner (Plan O3 Task 3).
        miniPlayer = run {
            val showPlayer = player != null && miniPlacement != MiniPlayerPlacement.HIDDEN
            if (!showPlayer && !offline) null else {
                {
                    androidx.compose.foundation.layout.Column {
                        if (showPlayer) MiniPlayer(
                            miniUi,
                            onPlayPause = player!!::miniPlayPause,
                            onSecondary = player::secondary,
                            onOpen = { nav.togglePlayer(player) },
                            skipBackS = player.skipBackS(),
                        )
                        app.granger.online.ReachBanner(onOutfitting = { nav.navigate(ReadinessRoute) { launchSingleTop = true } })
                    }
                }
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
                val importAsked by backStackEntry.savedStateHandle.getStateFlow(IMPORT_ASKED, false).collectAsStateWithLifecycle()
                LibraryScreen(
                    viewModel = viewModel {
                        val context = appContext
                        LibraryViewModel(
                            container.shelves, container.library,
                            ImportRunner(
                                container.bookshelf,
                                open = { uri -> context.contentResolver.openInputStream(uri) },
                                nameOf = { uri -> displayNameOf(context, uri) },
                                onImported = { container.appScope.launch { container.prepareBooks() } },
                            ),
                            container.appScope,
                            catalog = container.catalog,
                            bookshelf = container.bookshelf,
                        )
                    },
                    notice = backStackEntry.toRoute<LibraryRoute>().noticeText(),
                    onOpenCollection = { id -> nav.navigate(CollectionRoute(id)) },
                    onSearch = { nav.navigate(SearchRoute()) },
                    onReadiness = { nav.navigate(ReadinessRoute) },
                    downloadsBadge = downloadsBadge,
                    onDownloads = { nav.navigate(DownloadsRoute) { launchSingleTop = true } },
                    onGetMore = { nav.navigate(GetMoreRoute(GetMoreFor.BOOKS)) { launchSingleTop = true } },
                    onOpenSource = nav::openGetMore,
                    importAsked = importAsked,
                    onImportAsked = { backStackEntry.savedStateHandle[IMPORT_ASKED] = false },
                    // The reading shelf opens a book where it was left (Plan O4 Task 5).
                    onOpenBook = { book -> scope.launch { showTab(container.sessions.openTab(book.id, "", book.title)) } },
                    everyCollection = if (!Features.ONLINE || !app.granger.online.isOnlineNow()) null else {
                        val books = viewModel(key = "every-collection") {
                            SourceViewModel(
                                CatalogScope.BOOKS, container.catalog, container.downloads, container.library, container.bookshelf, container.volumes,
                                container.downloadPrefs, container.deviceTier, container.appScope, askOn = container.askOn, meaningSearch = container.meaningSearch,
                                knowledge = container.knowledgeLanguages.languages,
                            )
                        }
                        val booksUi by books.ui.collectAsStateWithLifecycle()
                        val actions = app.granger.sources.rememberBookSourceActions(
                            books,
                            onOpenScope = { s -> nav.navigate(SourceRoute(s)) },
                            onOpenPack = ::openPackOrCollection,
                            onOpenBook = { bookId, title -> scope.launch { showTab(container.sessions.openTab(bookId, "", title)) } },
                            onOpenSet = { id -> nav.navigate(SourceRoute(CatalogScope.BOOK_SET, set = id)) },
                            onReadNow = { id, title -> scope.launch { container.readWithoutKeeping(id)?.let { bookId -> showTab(container.sessions.openTab(bookId, "", title)) } } },
                            canReadNow = { id -> container.catalog.state.value.catalog?.books?.any { it.id == id } == true },
                            onBrowse = { id -> nav.navigate(RemoteCollectionRoute(id)) },
                            canBrowse = ::canBrowseOnline,
                        );
                        { bookSourceShelves(booksUi, actions, keyPrefix = "every-") }
                    },
                )
            }
            composable<RemoteCollectionRoute> { backStackEntry ->
                val packId = backStackEntry.toRoute<RemoteCollectionRoute>().packId
                app.granger.library.RemoteCollectionScreen(
                    viewModel = viewModel(key = "remote:$packId") {
                        app.granger.library.RemoteCollectionViewModel(
                            load = { container.remoteCollection(packId) },
                            books = container.bookshelf.books,
                            read = { key, progress -> container.readPackBookOnline(packId, key, progress) },
                            keepBook = { id -> container.keepPackBook(id) },
                            keepPack = { container.keepPack(packId) },
                            packQueued = container.downloads.records.map { list ->
                                list.any { it.itemId == packId && it.state != app.granger.core.downloads.DownloadState.FAILED }
                            },
                            capBytes = { container.onlinePrefs.savedCapMb.value * 1024L * 1024 },
                            online = { container.canFetchOnline() },
                        )
                    },
                    onBack = { nav.popBackStack() },
                    onOpenBook = { bookId, title -> scope.launch { showTab(container.sessions.openTab(bookId, "", title)) } },
                )
            }
            composable<ExploreRoute> {
                // Outfit for offline's downloads, until they're in or the line is dismissed (Plan OB call 3).
                var outfitGroup by remember { mutableStateOf(container.onboardingPrefs.outfitGroup) }
                val records by container.downloads.records.collectAsStateWithLifecycle(emptyList())
                val outfitting = outfitGroup?.let { group ->
                    val mine = records.filter { it.groupId == group }
                    val dismiss = { container.onboardingPrefs.outfitGroup = null; outfitGroup = null }
                    if (mine.isEmpty() || mine.all { it.state == app.granger.core.downloads.DownloadState.INSTALLED }) {
                        if (mine.isNotEmpty()) LaunchedEffect(group) { dismiss() }
                        null
                    } else app.granger.explore.OutfitProgress(
                        totalBytes = mine.sumOf { it.sizeBytes },
                        doneBytes = mine.sumOf { if (it.state == app.granger.core.downloads.DownloadState.INSTALLED) it.sizeBytes else it.bytesDone },
                        onSee = { nav.navigate(ReadinessRoute) },
                        onDismiss = dismiss,
                    )
                }
                ExploreScreen(viewModel { ExploreViewModel(exploreSources) }, exploreActions, outfitting)
            }
            composable<SubjectRoute> { backStackEntry ->
                val id = backStackEntry.toRoute<SubjectRoute>().subject
                val subject = app.granger.explore.Subject.of(id) ?: app.granger.explore.Subject.HISTORY
                SubjectScreen(
                    viewModel(key = "subject:$id") {
                        SubjectViewModel(subject, exploreSources, books = {
                            container.referenceLinks.booksWithWorks().map { (b, work) -> app.granger.explore.ExploreBook(b.bookId, b.title, b.cover, work) }
                        })
                    },
                    exploreActions,
                    app.granger.explore.SubjectActions(
                        onWanderAt = { packId, path ->
                            container.wanderFrom.value = packId to path
                            if (!nav.popBackStack<HomeRoute>(inclusive = false)) nav.navigate(HomeRoute) { launchSingleTop = true }
                        },
                        onQuiz = { nav.navigate(SubjectGamesRoute(subject.id)) { launchSingleTop = true } },
                        onOpenFacet = { facet -> nav.navigate(TopicsRoute(subject.id, facet)) },
                        onOpenExplore = { if (!nav.popBackStack<ExploreRoute>(inclusive = false)) nav.navigate(ExploreRoute) { launchSingleTop = true } },
                        // Through a door the next subject takes this one's place, so Back leaves the subjects.
                        onOpenDoor = { next -> nav.navigate(SubjectRoute(next)) { popUpTo(SubjectRoute(subject.id)) { inclusive = true } } },
                        onOpenBook = ::openLinkedBook,
                        onOpenCollection = { id -> nav.navigate(CollectionRoute(id)) },
                    ),
                )
            }
            composable<TopicsRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<TopicsRoute>()
                val subject = app.granger.explore.Subject.of(route.subject) ?: app.granger.explore.Subject.HISTORY
                app.granger.explore.TopicsScreen(
                    viewModel(key = "topics:${route.subject}:${route.facet}") {
                        SubjectViewModel(subject, exploreSources, facetId = route.facet, listing = true)
                    },
                    exploreActions,
                    app.granger.explore.SubjectActions(
                        onWanderAt = { packId, path ->
                            container.wanderFrom.value = packId to path
                            if (!nav.popBackStack<HomeRoute>(inclusive = false)) nav.navigate(HomeRoute) { launchSingleTop = true }
                        },
                        // The trail leads back the way the reader came when that way is still behind them.
                        onOpenExplore = { if (!nav.popBackStack<ExploreRoute>(inclusive = false)) nav.navigate(ExploreRoute) { launchSingleTop = true } },
                        onOpenSubject = { if (!nav.popBackStack(SubjectRoute(subject.id), inclusive = false)) nav.navigate(SubjectRoute(subject.id)) { launchSingleTop = true } },
                        // Through a door the next room takes this one's place, so Back leaves the rooms; a link opens on top.
                        onOpenRoom = { s, f, through -> nav.navigate(TopicsRoute(s, f)) { if (through) popUpTo(route) { inclusive = true } } },
                    ),
                )
            }
            composable<SubjectGamesRoute> { backStackEntry ->
                val id = backStackEntry.toRoute<SubjectGamesRoute>().subject
                val subject = app.granger.explore.Subject.of(id) ?: app.granger.explore.Subject.HISTORY
                val thumbs = remember { ArticleThumbnails(File(appContext.cacheDir, "home-thumbs")) }
                val model = viewModel(key = "subject-games:$id") {
                    GamesViewModel { scope -> subjectGameModel(scope, thumbs, id) }
                }.model
                val ui by model.ui.collectAsStateWithLifecycle()
                GamesScreen(
                    ui, remember(model) { gameActions(model) },
                    title = stringResource(R.string.explore_subject_quiz_title, stringResource(subject.label)),
                    onOpen = { mark ->
                        scope.launch {
                            val tabId = openTopic(mark.packId, mark.path, mark.title) ?: return@launch
                            container.sessions.tab(tabId)?.let { nav.showTab(it) }
                        }
                    },
                    onBack = { nav.popBackStack() },
                )
            }
            composable<BrowseRoute> {
                BrowseScreen(
                    viewModel { BrowseViewModel(container.shelves, container.library, container.catalog, container.downloads.records, countryCode = container.phoneCountry, knowledge = container.knowledgeLanguages.languages, askOffered = container.askOn, books = container.bookshelf.books) },
                    packsFolder = container.packDirs().firstOrNull()?.path.orEmpty(),
                    downloadsBadge = downloadsBadge,
                    actions = BrowseActions(
                        // Always a new tab at the pack's main page, so a wiki can be open in two places.
                        onOpenPack = { packId -> openPack(packId, newTab = true) },
                        onGetPacks = { domain -> nav.navigate(SourceRoute(CatalogScope.KIWIX, domain)) },
                        onDownloads = { nav.navigate(DownloadsRoute) { launchSingleTop = true } },
                        onReadiness = { nav.navigate(ReadinessRoute) },
                        onFiles = { packId -> nav.navigate(PackFilesRoute(packId)) },
                        onBooks = { if (!nav.popBackStack<LibraryRoute>(inclusive = false)) nav.navigate(LibraryRoute()) },
                        // Opened from Explore's Your sources: Back returns there (Plan EX Task 4).
                        onBack = if (Features.EXPLORE) ({ nav.popBackStack() }) else null,
                    ),
                    importPack = { uri -> container.packImports.add(uri).text() },
                )
            }
            composable<PackFilesRoute> { backStackEntry ->
                val packId = backStackEntry.toRoute<PackFilesRoute>().packId
                app.granger.browse.PackFilesScreen(
                    viewModel { app.granger.browse.PackFilesViewModel(packId, container.library) },
                    onBack = { nav.popBackStack() },
                    // Opened as a pack document (Plan P2's path), as a link to it in the pack would be.
                    onOpen = { path, title -> scope.launch { openPackDocument(packId, path, title) } },
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
                            gutenberg = container.gutenbergShelf,
                            levelName = { appContext.getString(R.string.sources_level, it) },
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
                            footerSource = if (Features.LINKED_CLASSICS) container.referenceLinks else null,
                            topics = if (Features.TOPIC_PAGE) container.topics else null,
                        )
                    },
                    resolver = container.resolver,
                    onChromeVisibleChange = { visible -> shell.railVisible = visible },
                    showChromeRequest = showChromeRequest,
                    onSearchText = { text -> nav.navigate(SearchRoute(text)) },
                    onResearch = { text, packId, path, title -> nav.navigate(ResearchRoute.about(text, packId, title, path)) },
                    onOpenDocument = { packId, path, linkText -> scope.launch { openPackDocument(packId, path, linkText) } },
                    isDated = { packId, path -> kotlinx.coroutines.withContext(Dispatchers.IO) { timeIndexes().find(packId, path) != null } },
                    onShowInTime = { packId, path -> nav.navigate(TimelineRoute(packId, path)) },
                    onOpenBook = ::openLinkedBook,
                    onOpenPassage = { bookId, m ->
                        scope.launch {
                            val quote = m.quote
                            val before = app.granger.ebook.LinkDecorations.contextOf(m.before).orEmpty()
                            openCitation(
                                matchOpener.open(
                                    app.granger.core.library.PassageAddress.Book(bookId, m.href, 0.0, quote),
                                    app.granger.core.research.CitedQuote.Quote(before, quote, ""),
                                ),
                            )
                        }
                    },
                    // From a topic page (Plan LL2): a new tab, which opens at its own topic page; Back returns here.
                    onOpenArticle = { packId, path, title ->
                        scope.launch {
                            openTopic(packId, path, title)?.let { container.sessions.tab(it) }?.let { nav.showTab(it, keepCurrent = true) }
                        }
                    },
                    onWander = { packId, path ->
                        container.wanderFrom.value = packId to path
                        if (!nav.popBackStack<HomeRoute>(inclusive = false)) nav.navigate(HomeRoute) { launchSingleTop = true }
                    },
                    onOpenPlace = { subject, room ->
                        when {
                            subject == null -> nav.navigate(ExploreRoute) { launchSingleTop = true }
                            room == null -> nav.navigate(SubjectRoute(subject)) { launchSingleTop = true }
                            else -> nav.navigate(TopicsRoute(subject, room)) { launchSingleTop = true }
                        }
                    },
                    onOpenBookAt = { bookId, chapter ->
                        scope.launch { openCitation(matchOpener.open(app.granger.core.library.PassageAddress.Book(bookId, chapter, 0.0))) }
                    },
                    onBack = { nav.popBackStack() },
                )
            }
            composable<BookRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<BookRoute>()
                val target = route.target()
                // A book opened without keeping it (Plan O2 Task 8): off the shelf, from the catalog; ⋯ offers Keep.
                var keepRef by remember(route.tabId) { mutableStateOf<String?>(null) }
                // A pack book read online (Plan BK): ⋯ Keep keeps it on its own, without the pack.
                var keepLooseId by remember(route.tabId) { mutableStateOf<String?>(null) }
                LaunchedEffect(route.tabId) {
                    if (!Features.ONLINE) return@LaunchedEffect
                    val bookId = container.sessions.tab(route.tabId)?.packId ?: return@LaunchedEffect
                    val book = container.bookshelf.book(bookId)
                    keepRef = book?.takeIf { it.hidden && it.origin == app.granger.core.library.BookOrigin.GRANGER }?.sourceRef
                    keepLooseId = book?.takeIf { it.loose == app.granger.core.library.Loose.SAVED }?.id
                }
                EbookReaderScreen(
                    viewModel = viewModel {
                        EbookReaderViewModel(
                            route.tabId, target, container.bookshelf, container.sessions, container.bookOpener,
                            container.readingPrefs, container.appScope,
                            savedState = createSavedStateHandle(),
                            player = if (Features.PLAYER && Features.READ_ALOUD) container.playerHub else null,
                            restore = container::restoreBook, savedPlace = container.voicePlace,
                            pdfColour = PdfColour::isColour,
                            links = if (Features.LINKED_CLASSICS) container.referenceLinks else null,
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
                    onOpenBook = ::openLinkedBook,
                    // A reference link's article opens over the book, as Define's entry does; Back comes back to the page.
                    onOpenArticle = { packId, path, title ->
                        scope.launch {
                            openTopic(packId, path, title)?.let { container.sessions.tab(it) }?.let { nav.showTab(it, keepCurrent = true) }
                        }
                    },
                    // Made an ebook (Plan IA): this reader closes and the tab opens again, as the ebook. Only if it is
                    // still on screen: the reader may have been left while the conversion ran.
                    onReopen = {
                        if (nav.currentBackStackEntry?.readingTabId() == route.tabId) {
                            nav.popBackStack()
                            showTab(route.tabId)
                        }
                    },
                    onKeep = keepRef?.let { ref -> { scope.launch { if (container.keepBook(ref)) keepRef = null } } }
                        ?: keepLooseId?.let { id -> { scope.launch { if (container.keepPackBook(id)) keepLooseId = null } } },
                )
            }
            composable<SearchRoute> { backStackEntry ->
                val query = backStackEntry.toRoute<SearchRoute>().query
                SearchScreen(
                    viewModel = viewModel {
                        SearchViewModel(
                            container.search, container.sessions, query,
                            media = if (Features.PLAYER) MediaSearch { container.mediaIndex.searchForGroups(it) } else MediaSearch { MediaResults() },
                            voice = container.voiceInput,
                            spokenNames = container.spokenTitles.names,
                            best = object : BestMatchSearch {
                                override fun stream(query: String) = container.bestStream(query)
                                override suspend fun warm() = container.bestMatches.warm()
                            },
                            define = WordEntry { word ->
                                container.passageFinder.entry(word, app.granger.core.research.ResearchLanguage.of(word))
                            },
                            online = if (Features.ONLINE) app.granger.search.OnlineSearch(container::searchOnline) else null,
                            reachOnline = container.reach.state.map { it.online },
                        )
                    },
                    onOpenMatch = { match ->
                        scope.launch {
                            val p = match.passage
                            openCitation(matchOpener.open(app.granger.core.library.PassageAddress.of(p, match.quote()), title = p.sourceTitle))
                        }
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
                    onGetMore = { nav.navigate(GetMoreRoute()) { launchSingleTop = true } },
                    voicePrefs = container.voiceInput.prefs,
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
                            speakAnswers = container.voicePrefs.speakAnswers,
                            setSpeakAnswers = container.voicePrefs::setSpeakAnswers,
                            voice = container.voiceInput,
                            // Conversation mode is English-only (its mic is), so the owl's decline is the one heard.
                            outcomeLine = { ui -> spokenOutcome(appContext.resources, ui, owl = java.util.Locale.getDefault().language == "en") },
                        )
                    },
                    voicePrefs = container.voiceInput.prefs,
                    focusInput = route.quote != null,
                    // Pushed over the chat (Research isn't a reader), so Back returns to it.
                    onOpenCitation = ::openCitation,
                    onSearch = { keywords -> nav.navigate(SearchRoute(keywords)) },
                    onGetModels = { nav.navigate(SourceRoute(CatalogScope.MODELS)) },
                    librarian = container.librarian.ui.collectAsStateWithLifecycle(null).value,
                    onGetLibrarian = {
                        container.appScope.launch {
                            container.librarian.get()?.let { problem ->
                                withContext(Dispatchers.Main) { Toast.makeText(appContext, problem.resolve(appContext), Toast.LENGTH_LONG).show() }
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
                            knownPacks = container.knownPacks,
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
                            container.downloadPrefs, container.deviceTier, container.appScope, askOn = container.askOn, meaningSearch = container.meaningSearch, domain = route.domain,
                            family = route.family, knowledge = container.knowledgeLanguages.languages,
                            voiceTitles = app.granger.core.speech.VoiceTitles.of(appContext), bookSet = route.set,
                        )
                    },
                    title = stringResource(source.title),
                    onBack = { nav.popBackStack() },
                    // An installed book pack opens as its books, in the Library (Plan GB, Plan KB Decision 6), from any list.
                    onOpenPack = ::openPackOrCollection,
                    onOpenFamily = { family -> nav.navigate(SourceRoute(source, family = family)) },
                    // An installed book opens in the reader (Plan KB2 Task 4.3).
                    onOpenBook = { bookId, title -> scope.launch { showTab(container.sessions.openTab(bookId, "", title)) } },
                )
            }
            composable<ArchiveRoute> { backStackEntry ->
                val kind = backStackEntry.toRoute<ArchiveRoute>().kind
                ArchiveScreen(
                    viewModel = viewModel {
                        ArchiveViewModel(
                            container.archiveClient, container.downloads, container.bookshelf, container.archivePrefs,
                            container.volumes, container.downloadPrefs, { container.archiveSidecarDir }, container.appScope,
                            kind = kind, media = container.mediaDownloader, languages = { container.knowledgeNow.value },
                        )
                    },
                    detailsUrl = container.archiveClient::detailsUrl,
                    onBack = { nav.popBackStack() },
                    onOpenLibriVox = { nav.openLibriVox() },
                    onOpenInLibrary = { identifier -> nav.navigate(inLibraryRoute(kind, identifier)) },
                    // An installed text opens as a book, a set as its collection (Plan KB2 Task 4.3).
                    onOpenText = { target ->
                        when (target) {
                            is app.granger.archive.ArchiveOpen.Book ->
                                scope.launch { showTab(container.sessions.openTab(target.bookId, "", target.title)) }
                            is app.granger.archive.ArchiveOpen.Collection -> nav.navigate(CollectionRoute(target.collectionId))
                        }
                    },
                )
            }
            composable<LibriVoxRoute> {
                LibriVoxScreen(
                    viewModel = viewModel {
                        LibriVoxViewModel(
                            container.librivoxClient, container.archiveClient, container.mediaDownloader, container.downloads,
                            container.appScope, languages = { container.knowledgeNow.value },
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
                    seen = container.seen,
                    content = container.contentPrefs,
                    online = if (Features.ONLINE) app.granger.online.OnlineSettingsSource(container.onlinePrefs, container.savedStore, container.reachControl) else null,
                    onModels = { nav.navigate(SourceRoute(CatalogScope.MODELS)) },
                    onSetUpAgain = { nav.navigate(OnboardingRoute(outfit = true)) { launchSingleTop = true } },
                    onShowTips = if (Features.ONLINE) container.hints::reset else null,
                    onBack = { nav.popBackStack() },
                    onVoice = if (Features.PLAYER && Features.READ_ALOUD) ({ nav.navigate(VoiceRoute) }) else null,
                    voiceSummary = container.installedVoices.voices.collectAsStateWithLifecycle().value
                        .let { voices -> container.voicePrefs.voiceId.collectAsStateWithLifecycle().value?.let { id -> voices.firstOrNull { v -> v.id == id } } ?: voices.firstOrNull() }
                        ?.title ?: stringResource(R.string.settings_voice_none),
                    onMediaSettings = if (Features.PLAYER) ({ nav.navigate(MediaSettingsRoute) { launchSingleTop = true } }) else null,
                    audiobooks = remember { container.shelves.audioShelves }.collectAsStateWithLifecycle(AudioShelves.NONE).value.audiobooks,
                    onLanguage = { nav.navigate(LanguageRoute) { launchSingleTop = true } },
                    languageSummary = languageLabel(
                        container.appLanguages.language.collectAsStateWithLifecycle().value, container.appLanguages.phoneLocale(),
                    ),
                    onFeedback = if (crashLog != null) ({ feedbackOpen = true }) else null,
                    crashReports = crashReports,
                    voiceInput = container.voiceInput,
                )
            }
            composable<LanguageRoute> {
                LanguageSettingsScreen(container.appLanguages, container.knowledgeLanguages, onBack = { nav.popBackStack() })
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
                        onGetMore = { nav.navigate(GetMoreRoute(GetMoreFor.LISTEN)) { launchSingleTop = true } },
                    ),
                )
            }
            composable<VoiceRoute> {
                VoiceSettingsScreen(
                    viewModel = viewModel {
                        VoiceSettingsViewModel(
                            container.voicePrefs, container.installedVoices, container.catalog, container.downloads, container.volumes,
                            container.downloadPrefs, container.appScope,
                            lowMemory = container.deviceTier < DeviceTier.ASK,
                        )
                    },
                    onBack = { nav.popBackStack() },
                )
            }
            composable<HomeRoute> {
                val privacySeen by container.networkPolicy.privacySeen.collectAsStateWithLifecycle()
                // Ready offline (Plan O3 Task 8): worked out when Home shows, and again when the internet comes or goes.
                val reachNow = container.reachControl?.state?.collectAsStateWithLifecycle()?.value
                val readyOffline by androidx.compose.runtime.produceState<app.granger.online.ReadyOfflineUi?>(null, reachNow) {
                    value = if (Features.ONLINE) runCatching { container.readyOffline() }.getOrNull() else null
                }
                val homeThumbs = remember {
                    ArticleThumbnails(File(appContext.cacheDir, "home-thumbs")).also { thumbs ->
                        // Once per Home: the cache trimmed to its cap, in the background.
                        container.appScope.launch(Dispatchers.IO) { thumbs.prune() }
                    }
                }
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
                                thumbnail = homeThumbs::thumbnail,
                                languages = { container.knowledgeNow.value },
                                hides = container.contentFilter::hides,
                                noPicture = container.contentFilter::noPicture,
                            ),
                            container.shelves, container.media, container.homePrefs, tidyDismissals = container.tidyDismissals,
                            knowledge = container.knowledgeLanguages.languages, appLanguage = container.appLanguages.code,
                            wanderOf = { scope ->
                                WanderModel(
                                    scope = scope,
                                    encyclopedias = {
                                        // Offline, Wander walks only what opens (Plan O3 Task 5).
                                        val off = container.offlineSources()
                                        WanderModel.encyclopediasOf(container.knownPacks.first(), container.knowledgeNow.value).filterNot { it.id in off }
                                    },
                                    archiveOf = { container.library.archive(it.id) },
                                    thumbnail = homeThumbs::thumbnail,
                                    titles = { container.search.titles(it).hits },
                                    hides = container.contentFilter::hides,
                                    noPicture = container.contentFilter::noPicture,
                                )
                            },
                            timelineOf = { scope -> timelineModel(scope, homeThumbs) },
                            gamesOf = { scope -> gameModel(scope, homeThumbs, mixed = true).also { homeGames[0] = it } },
                            tabPicture = { packId, path ->
                                container.library.archive(packId)?.let { archive ->
                                    if (archive.remote) container.articleCards.card(archive, path)?.thumbnail
                                    else archive.read(path)?.let { homeThumbs.thumbnail(archive, path, String(it.bytes, Charsets.UTF_8)) }
                                }
                            },
                            wanderFrom = container.wanderFrom,
                            opens = container::opensNow,
                            reachChanges = container.reach.state,
                        )
                    },
                    onReadiness = { nav.navigate(ReadinessRoute) { launchSingleTop = true } },
                    onOpenDownloads = { nav.navigate(DownloadsRoute) { launchSingleTop = true } },
                    crashNotice = if (crashNotice != null) CrashNotice(onSend = { feedbackOpen = true }, onDismiss = { crashLog?.acknowledge() }) else null,
                    onOpenTab = ::showTab,
                    onPlay = ::resumePlay,
                    onOpenTimeline = { mark -> nav.navigate(TimelineRoute(mark?.packId, mark?.path)) { launchSingleTop = true } },
                    onOpenGames = { nav.navigate(GamesRoute) { launchSingleTop = true } },
                    readyOffline = readyOffline,
                    privacyNotice = if (Features.ONLINE && !privacySeen) app.granger.home.PrivacyNotice(
                        onOk = container.networkPolicy::setPrivacySeen, onSettings = { nav.navigate(SettingsRoute) { launchSingleTop = true } },
                    ) else null,
                    readyActions = app.granger.home.ReadyActions(
                        onKeepTheRead = container::keepTheRead, onGetPack = { container.getPack(it) }, onSeeAll = { nav.navigate(ReadinessRoute) },
                    ),
                    onOpenCard = { card ->
                        when (val target = card.target) {
                            is CardTarget.Article -> scope.launch {
                                val tabId = openTopic(target.packId, target.path, card.title) ?: return@launch
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
            composable<TimelineRoute> { entry ->
                val route = entry.toRoute<TimelineRoute>()
                val thumbs = remember { ArticleThumbnails(File(appContext.cacheDir, "home-thumbs")) }
                val model = viewModel { TimelineViewModel { scope -> timelineModel(scope, thumbs, route.packId?.let { p -> route.path?.let { p to it } }) } }.model
                val ui by model.ui.collectAsStateWithLifecycle()
                TimelineScreen(
                    ui, remember(model) { timelineActions(model) },
                    onOpen = { mark ->
                        scope.launch {
                            val tabId = openTopic(mark.packId, mark.path, mark.title) ?: return@launch
                            container.sessions.tab(tabId)?.let { nav.showTab(it) }
                        }
                    },
                    onBack = { nav.popBackStack() },
                )
            }
            composable<GamesRoute> {
                val thumbs = remember { ArticleThumbnails(File(appContext.cacheDir, "home-thumbs")) }
                val model = viewModel { GamesViewModel { scope -> gameModel(scope, thumbs, mixed = false) } }.model
                val ui by model.ui.collectAsStateWithLifecycle()
                val home = homeGames[0]
                val carry = home?.ui?.collectAsStateWithLifecycle()?.value
                GamesScreen(
                    ui, remember(model) { gameActions(model) },
                    carry = carry,
                    onContinue = {
                        val r = carry?.round
                        if (r != null) {
                            model.startRun(r.kind, r, carry.pictures)
                            // Home deals a fresh round of another game for when they're back.
                            home.replace()
                        }
                    },
                    onOpen = { mark ->
                        scope.launch {
                            val tabId = openTopic(mark.packId, mark.path, mark.title) ?: return@launch
                            container.sessions.tab(tabId)?.let { nav.showTab(it) }
                        }
                    },
                    onBack = { nav.popBackStack() },
                )
            }
            composable<OnboardingRoute> { entry ->
                val outfitOnly = entry.toRoute<OnboardingRoute>().outfit
                OnboardingScreen(
                    viewModel = viewModel {
                        OnboardingViewModel(
                            container.catalog, container.library, container.downloads, container.volumes, container.downloadPrefs,
                            container.deviceTier, container.totalRamBytes, container.onboardingPrefs, askOn = container.askOn,
                            knowledge = container.knowledgeLanguages.languages,
                            setKnowledge = container.knowledgeLanguages::set,
                            phoneLocales = container.appLanguages::phoneLocales,
                            appLanguage = container.appLanguages::currentCode,
                            countryCode = container.phoneCountry,
                            chooseOnline = Features.ONLINE,
                            onPrivacySeen = container.networkPolicy::setPrivacySeen,
                            startAt = if (outfitOnly) app.granger.onboarding.Step.OUTFIT else app.granger.onboarding.Step.WELCOME,
                        )
                    },
                    onDone = { nav.popBackStack() },
                    // Start reading now (Plan O5): onboarding ends on Explore.
                    onReadNow = { nav.popBackStack(); nav.navigate(ExploreRoute) { launchSingleTop = true } },
                    onSeeDomain = { domain -> nav.navigate(SourceRoute(CatalogScope.KIWIX, domain)) },
                )
            }
            composable<DownloadsRoute> {
                DownloadsScreen(
                    viewModel = viewModel {
                        DownloadsViewModel(
                            container.downloads, container.library, container.bookshelf, container.sessions, container.appScope,
                            container.catalog, container.volumes, tidyDismissals = container.tidyDismissals,
                        )
                    },
                    onBack = { nav.popBackStack() },
                    onOpened = ::showTab,
                    // A book pack opens as its books, as Get more's Open does (Plan KB2 Task 4.3).
                    onOpenPack = ::openPackOrCollection,
                    onGetMore = { nav.navigate(GetMoreRoute()) { launchSingleTop = true } },
                )
            }
            composable<GetMoreRoute> { backStackEntry ->
                val only = backStackEntry.toRoute<GetMoreRoute>().only
                // The Library's + is Books by kind itself (Plan KB2 Task 4.1).
                if (only == GetMoreFor.BOOKS) {
                    app.granger.sources.BooksScreen(
                        viewModel = viewModel {
                            SourceViewModel(
                                CatalogScope.BOOKS, container.catalog, container.downloads, container.library, container.bookshelf, container.volumes,
                                container.downloadPrefs, container.deviceTier, container.appScope, askOn = container.askOn, meaningSearch = container.meaningSearch,
                                knowledge = container.knowledgeLanguages.languages,
                            )
                        },
                        onBack = { nav.popBackStack() },
                        onOpenScope = { s -> nav.navigate(SourceRoute(s)) },
                        onOpenPack = ::openPackOrCollection,
                        onOpenBook = { bookId, title -> scope.launch { showTab(container.sessions.openTab(bookId, "", title)) } },
                        onArchive = { nav.navigate(ArchiveRoute(ArchiveKind.TEXTS)) },
                        onReadNow = if (!Features.ONLINE) null else { id, title ->
                            scope.launch { container.readWithoutKeeping(id)?.let { bookId -> showTab(container.sessions.openTab(bookId, "", title)) } }
                        },
                        canReadNow = { id -> container.catalog.state.value.catalog?.books?.any { it.id == id } == true },
                        onImport = if (Features.IMPORT) nav::importIntoLibrary else null,
                        onOpenSet = { id -> nav.navigate(SourceRoute(CatalogScope.BOOK_SET, set = id)) },
                        onBrowse = if (!Features.ONLINE) null else { id -> nav.navigate(RemoteCollectionRoute(id)) },
                        canBrowse = ::canBrowseOnline,
                    )
                    return@composable
                }
                GetMoreScreen(
                    onOpen = nav::openGetMore,
                    onBack = { nav.popBackStack() },
                    only = only,
                    onImport = if (only == GetMoreFor.BOOKS && Features.IMPORT) nav::importIntoLibrary else null,
                    onImportPack = { uri -> container.packImports.add(uri).text() },
                )
            }
            composable<ReadinessRoute> {
                // Outfitting (Plan O4): Readiness's screen with its own sections, worked out each time it shows.
                var outfitVersion by remember { mutableStateOf(0) }
                val kept by container.keptStore.articles.collectAsStateWithLifecycle(emptyList())
                val ready by androidx.compose.runtime.produceState<app.granger.online.ReadyOfflineUi?>(null, outfitVersion, kept.size) {
                    value = if (Features.ONLINE) runCatching { container.readyOffline(subjects = 17) }.getOrNull() else null
                }
                val savedBytes by androidx.compose.runtime.produceState(0L, outfitVersion, kept.size) {
                    value = kotlinx.coroutines.withContext(Dispatchers.IO) { container.savedStore.size() }
                }
                // Outfitting for everyone (builder's call 1); its online sections only while Wikipedia online is on.
                // Updates for kept articles: checked here, once a day while online (Plan O5 Task 4).
                val updates by container.keptUpdates.updates.collectAsStateWithLifecycle()
                LaunchedEffect(kept.size) {
                    if (Features.ONLINE) kotlinx.coroutines.withContext(Dispatchers.IO) { runCatching { container.keptUpdates.check(kept, container.reach.now.online) } }
                }
                val outfitting = if (!Features.ONLINE) app.granger.readiness.OutfittingSlots({}, {}) else app.granger.online.outfittingSlots(
                    app.granger.online.OutfittingState(ready, kept, savedBytes, container.onlinePrefs.savedCapMb.value * 1024L * 1024, updates),
                    app.granger.online.OutfittingActions(
                        onKeepTheRead = container::keepTheRead,
                        onGetPack = { container.getPack(it) },
                        onOpenKept = { a -> scope.launch { openTopic(app.granger.core.online.OnlineWikipediaArchive.packName(a.lang), a.title, app.granger.core.online.WikimediaClient.titleOfKey(a.title))?.let { container.sessions.tab(it) }?.let { nav.showTab(it) } } },
                        onRemoveKept = { a -> scope.launch { container.keeper.remove(a.lang, a.title); outfitVersion++ } },
                        onClearSaved = { scope.launch(Dispatchers.IO) { container.savedStore.clear(); outfitVersion++ } },
                        onUpdate = { list -> scope.launch { container.updateKept(list) } },
                    ),
                )
                ReadinessScreen(
                    viewModel = viewModel {
                        ReadinessViewModel(
                            container.downloads, container.library, container.bookshelf, container.catalog, container.volumes,
                            container.deviceTier, container.totalRamBytes, container.appScope, askOn = container.askOn,
                            knowledge = container.knowledgeLanguages.languages, appLanguage = container.appLanguages.code,
                        )
                    },
                    onBack = { nav.popBackStack() },
                    onOpenKiwix = { domain -> nav.navigate(SourceRoute(CatalogScope.KIWIX, domain)) },
                    askCheck = container.modelManager.checkFailure.collectAsStateWithLifecycle().value.takeIf { container.askOn },
                    onRetryAsk = { container.appScope.launch { container.modelManager.retryCheck() } },
                    outfitting = outfitting,
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
