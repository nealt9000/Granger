package app.granger.home

import androidx.lifecycle.ViewModel
import app.granger.library.shownName
import androidx.lifecycle.viewModelScope
import app.granger.core.catalog.CatalogSource
import app.granger.core.downloads.DownloadQueueSummary
import app.granger.core.downloads.Downloads
import app.granger.core.downloads.QueueSummary
import app.granger.core.downloads.isFromCatalog
import app.granger.core.downloads.isGone
import app.granger.core.library.onShelf
import app.granger.core.library.Albums
import app.granger.core.library.Bookshelf
import app.granger.core.library.FeedKind
import app.granger.core.library.HomeFeeds
import app.granger.core.library.IndexPages
import app.granger.core.library.Library
import app.granger.core.library.MediaLibrary
import app.granger.core.library.PackStatus
import app.granger.core.library.Shelves
import app.granger.readiness.ReadinessModel
import app.granger.readiness.ReadinessReport
import app.granger.shell.RailEntry
import app.granger.shell.RailUi
import app.granger.screens.CivilDate
import java.time.LocalDate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Home (spec §5): the readiness strip, where you left off, then the feeds. */
data class HomeUi(
    val strip: ReadinessReport = ReadinessReport.EMPTY,
    /** The rail's tabs and plays, pins first, then newest, at most [HomeViewModel.CONTINUE]. */
    val continueCards: List<RailEntry> = emptyList(),
    /** Feeds whose scope names something on the phone, in Home order; the others are hidden (Decision 3). */
    val feeds: List<FeedUi> = emptyList(),
    val scopeChoices: List<ScopeChoice> = emptyList(),
    /** The live downloads card (Plan DL Decision 7); null when there's nothing to show. */
    val downloads: DownloadsCard? = null,
    /** A bigger slice is in: the offers to remove the smaller (Plan WF Decision 5). */
    val tidy: List<app.granger.sources.TidyOffer> = emptyList(),
    /** Home's order and what's hidden (Plan HY). */
    val layout: HomeLayout = HomeLayout(),
)

/** Home's one card for downloads: the round while anything waits or runs, else what stopped. */
sealed interface DownloadsCard {
    /** [title] is the file downloading now; null between files and while paused. */
    data class Active(
        val title: String?,
        val paused: Boolean,
        val filesDone: Int,
        val files: Int,
        val bytesDone: Long,
        val bytes: Long,
        val waiting: Int,
        val timeLeftMillis: Long? = null,
    ) : DownloadsCard {
        val fraction: Float get() = if (bytes <= 0L) 0f else (bytesDone.toFloat() / bytes).coerceIn(0f, 1f)
    }

    /** The queue is empty but [count] files stopped; [retriable] can be fetched again (not a withdrawn one). */
    data class Stopped(val count: Int, val retriable: List<String>, val keys: Set<String>) : DownloadsCard
}

/** The one-time marks Home keeps (SharedPreferences `"home"`). */
interface HomePrefs {
    /** The first-run feeds were inserted (Decision 4); set even when the user removes them all. */
    var seeded: Boolean

    /** The stopped files whose card was closed (`itemId@hash`); a new failure brings the card back (Plan DL Decision 7). */
    var dismissedFailures: Set<String>

    /** Home's order and hidden sections, as the user arranged them (Plan HY). */
    var layout: HomeLayout
}

/**
 * Home's data: the readiness report over the same four flows the Readiness screen uses, Continue from the rail's list
 * (no second query, no second cover loader), and the feeds ([HomeFeedsModel], shared with the iPhone since Plan I4b
 * Task 8), each drawn once a day and cached in `home_draws` (Decision 5).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    catalog: CatalogSource,
    private val library: Library,
    bookshelf: Bookshelf,
    private val downloads: Downloads,
    rail: StateFlow<RailUi>,
    homeFeeds: HomeFeeds,
    drawer: FeedDrawer,
    shelves: Shelves,
    media: MediaLibrary,
    private val prefs: HomePrefs,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val today: () -> LocalDate = LocalDate::now,
    private val clock: () -> Long = System::currentTimeMillis,
    private val tidyDismissals: app.granger.sources.TidyDismissals = app.granger.sources.TidyDismissals(),
    /** The library's languages, main first, and the app's (Plan L3): the strip measures and titles domains by them. */
    knowledge: Flow<List<String>> = kotlinx.coroutines.flow.flowOf(listOf(app.granger.core.catalog.ENGLISH)),
    appLanguage: Flow<String> = kotlinx.coroutines.flow.flowOf(app.granger.core.catalog.ENGLISH),
    /** Makes Home's Wander in the view model's scope (Plan H5a); none in tests that don't need it. */
    wanderOf: ((kotlinx.coroutines.CoroutineScope) -> WanderModel)? = null,
    /** Makes Home's timeline in the view model's scope (Plan H5b); none in tests that don't need it. */
    timelineOf: ((kotlinx.coroutines.CoroutineScope) -> TimelineModel)? = null,
    /** Makes Home's game round in the view model's scope (Plan H5c); none in tests that don't need it. */
    gamesOf: ((kotlinx.coroutines.CoroutineScope) -> GameModel)? = null,
    /** An article tab's picture (its lead image, cached), or null; blocking, run on [io]. */
    private val tabPicture: (packId: String, path: String) -> String? = { _, _ -> null },
    /** A topic page's Wander from here (Plan LL2): an article to start Wander at, taken once. */
    private val wanderFrom: kotlinx.coroutines.flow.MutableStateFlow<Pair<String, String>?>? = null,
    /** Whether an article tab opens now (Plan O3 Task 5): offline, Continue lists only what does. Blocking. */
    private val opens: (packId: String, path: String) -> Boolean = { _, _ -> true },
    /** Moves when the internet comes or goes, so Continue is worked out again. */
    private val reachChanges: Flow<Any?> = kotlinx.coroutines.flow.flowOf(Unit),
) : ViewModel() {
    /** Wander (Plan H5a): its own state, started when Home first shows it. */
    val wander: WanderModel? = wanderOf?.invoke(viewModelScope)

    /** Bumped when Wander was started from a topic page, for Home to bring Wander into view. */
    private val _wanderJumps = MutableStateFlow(0)
    val wanderJumps: StateFlow<Int> = _wanderJumps

    init {
        val requests = wanderFrom
        val model = wander
        if (requests != null && model != null) {
            viewModelScope.launch {
                requests.collect { at ->
                    if (at == null) return@collect
                    requests.value = null
                    model.startFrom(at.first, at.second)
                    _wanderJumps.value++
                }
            }
        }
    }

    /** The timeline (Plan H5b): its own state, started when Home first shows it. */
    val timeline: TimelineModel? = timelineOf?.invoke(viewModelScope)

    /** Play (Plan H5c): one round at a time, the two games in turn, started when Home first shows it. */
    val games: GameModel? = gamesOf?.invoke(viewModelScope)

    private val day = MutableStateFlow(today())

    private val languages = combine(knowledge, appLanguage) { k, a -> k to a }
    private val knowledgeFlow = knowledge
    private val appLanguageFlow = appLanguage

    private val strip = combine(catalog.state, library.packs, bookshelf.books, downloads.records, languages) { c, packs, books, records, (langs, app) ->
        ReadinessModel.report(c.catalog, packs, books, records, day.value.toEpochDay(), langs, app)
    }.flowOn(io)

    private val mediaItems = combine(media.tracks, media.audiobooks) { tracks, audiobooks -> Albums.summaries(tracks) to audiobooks }
    private val snapshot: Flow<LibrarySnapshot> =
        combine(library.packs, bookshelf.books, shelves.shelves, mediaItems, catalog.state) { packs, books, shelfList, (albums, audiobooks), c ->
            // Pack documents (Plan P2) belong to their packs: no feed draws them as books.
            LibrarySnapshot(packs, books.filter { it.onShelf }, shelfList, albums, audiobooks, c.catalog) { it.shownName() }
        }

    /** The feeds (shared with the iPhone): drawn on [io], for the same day as the strip. */
    private val feeds = HomeFeedsModel(viewModelScope, homeFeeds, drawer, snapshot, io) { today().toCivilDate() }

    /** Every feed that resolves, with its cards: cached ones first, then the rest as they are drawn. */
    private val feedRows: Flow<List<FeedUi>> = feeds.rows

    private val dismissed = MutableStateFlow(prefs.dismissedFailures)

    private val catalogIds = catalog.state.map { s -> s.catalog?.let { c -> c.items.mapTo(HashSet()) { it.id } } }

    private val downloadsCard = combine(downloads.records, downloads.live, dismissed, catalogIds) { records, live, closed, ids ->
        downloadsCard(DownloadQueueSummary.of(records, live, clock()), closed, ids)
    }.flowOn(io)

    /** Each OK pack's main entry, for Continue to leave out (opening an archive reads its file, so on [io]). */
    private val mainPaths: Flow<Map<String, String>> = library.packs.map { packs ->
        packs.filter { it.status == PackStatus.OK }.mapNotNull { pack -> library.archive(pack.id)?.mainPath?.let { pack.id to it } }.toMap()
    }.flowOn(io)

    /** Article pictures looked up once each ("packId/path" → file or none): the rail emits often. */
    private val tabPictures = java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<String>>()

    /**
     * Continue, with each article tab's lead image as its cover, so a card shows a picture rather than its title twice
     * (Neal, 2026-11-08). Books keep their own covers.
     */
    private val continuing = combine(rail, mainPaths, reachChanges) { railUi, mains, _ ->
        continueCards(railUi, mains).filter { e -> e !is RailEntry.Tab || e.tab.path.isEmpty() || opens(e.tab.packId, e.tab.path) }.map { entry ->
            if (entry !is RailEntry.Tab || entry.tab.coverPath != null || entry.tab.path.isEmpty()) return@map entry
            val key = "${entry.tab.packId}/${entry.tab.path}"
            val picture = tabPictures.getOrPut(key) {
                java.util.Optional.ofNullable(runCatching { tabPicture(entry.tab.packId, entry.tab.path) }.getOrNull())
            }
            if (picture.isPresent) entry.copy(tab = entry.tab.copy(coverPath = picture.get())) else entry
        }
    }.flowOn(io)

    private val tidyOffers = combine(catalog.state, downloads.records, library.packs, tidyDismissals.keys) { c, records, packs, keys ->
        app.granger.sources.SetTidy.offers(c.catalog, records, packs, keys)
    }.flowOn(io)

    private val layout = MutableStateFlow(prefs.layout)

    val ui: StateFlow<HomeUi> = combine(strip, continuing, feedRows, snapshot, combine(downloadsCard, tidyOffers, layout, ::Triple)) { report, cards, rows, snap, (card, tidy, order) ->
        HomeUi(
            strip = report, continueCards = cards, feeds = HomeFeedsModel.shown(rows),
            scopeChoices = scopeChoices(snap), downloads = card, tidy = tidy, layout = order,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUi())

    init {
        // The cached catalog, no network; MainActivity loads it too, and the repository loads once.
        viewModelScope.launch { catalog.load() }
        viewModelScope.launch { seedDefaults() }
    }

    fun pauseDownloads() {
        viewModelScope.launch { downloads.pause() }
    }

    fun resumeDownloads() {
        viewModelScope.launch { downloads.resume() }
    }

    /** Queues the stopped files again (a withdrawn one stays for Downloads' Remove). */
    fun retryStopped(card: DownloadsCard.Stopped) {
        viewModelScope.launch { card.retriable.forEach { downloads.retry(it) } }
    }

    /** Yes to a tidy offer: the smaller slice goes, its places and bookmarks moving to the bigger (Plan WF Decision 5). */
    fun removeSmaller(offer: app.granger.sources.TidyOffer) {
        viewModelScope.launch {
            if (!app.granger.sources.SetTidy.remove(offer, library, downloads)) tidyDismissals.dismiss(offer.key)
        }
    }

    /** Keep both: the offer doesn't come back. */
    fun keepBoth(offer: app.granger.sources.TidyOffer) = tidyDismissals.dismiss(offer.key)

    /** Closes the stopped card until something else fails. */
    fun dismissStopped(card: DownloadsCard.Stopped) {
        prefs.dismissedFailures = card.keys
        dismissed.value = card.keys
    }

    /** A new look at Home (resume): the date may have turned. */
    fun refresh() {
        day.value = today()
        feeds.refresh()
    }

    fun addFeed(scope: FeedScope, kind: FeedKind, query: String = "") = feeds.add(scope, kind, query)

    fun removeFeed(id: Long) = feeds.remove(id)

    /** Moves a feed one place up or down among everything Home shows (Plan HY: feeds and sections share one order). */
    fun moveFeed(id: Long, up: Boolean) {
        arrange(HomeFeedsModel.moved(layout.value, id, up, ui.value.feeds))
    }

    /** Saves Home's order and hidden sections from Arrange. */
    fun arrange(arranged: HomeLayout) {
        prefs.layout = arranged
        layout.value = arranged
    }

    /**
     * The first-run feeds (Decision 4), once. The library's languages, then the app's: a Spanish phone seeded before the
     * catalog loads still gets Clásicos.
     */
    private suspend fun seedDefaults() =
        feeds.seedDefaults({ prefs.seeded }, { prefs.seeded = true }) { knowledgeFlow.first() + appLanguageFlow.first() }

    companion object {
        const val CONTINUE = 8

        val DEFAULTS: List<Pair<FeedScope, FeedKind>> get() = HomeFeedsModel.DEFAULTS

        /** The first-run feeds for the library's [languages], main first (Plan L3); see [HomeFeedsModel.defaults]. */
        fun defaults(languages: List<String>): List<Pair<FeedScope, FeedKind>> = HomeFeedsModel.defaults(languages)

        /**
         * The round while anything waits or runs, for every download, not just setup. With nothing to come, the stopped
         * files, unless that card was closed on exactly these ([closed]); null when there's nothing to show. A withdrawn
         * file, or one the catalog ([catalogIds], null while there is none) no longer lists, isn't offered Retry.
         */
        fun downloadsCard(summary: QueueSummary, closed: Set<String>, catalogIds: Set<String>? = null): DownloadsCard? {
            if (summary.isActive) {
                return DownloadsCard.Active(
                    title = summary.current?.title, paused = summary.paused, filesDone = summary.filesDone, files = summary.files,
                    bytesDone = summary.bytesDone, bytes = summary.bytes, waiting = summary.waiting.size,
                    timeLeftMillis = summary.timeLeftMillis,
                )
            }
            val keys = summary.stopped.mapTo(HashSet()) { "${it.itemId}@${it.hash}" }
            if (keys.isEmpty() || closed.containsAll(keys)) return null
            val retriable = summary.stopped
                .filterNot { it.isGone || (catalogIds != null && it.isFromCatalog && it.itemId !in catalogIds) }
                .map { it.itemId }.distinct()
            return DownloadsCard.Stopped(summary.stopped.map { it.itemId }.distinct().size, retriable, keys)
        }

        const val SETUP_GROUP = "onboarding:"

        /**
         * The rail's tabs and plays, pins first, then newest; searches, chats and the voice's live row are not "continue".
         * From [RailUi.recent], so Continue isn't cut to the rail's four. A tab on its pack's main page ([mainPaths], by
         * pack id) or an index page ([IndexPages]) wasn't read, so it isn't continued (Plan U3b Decision 12).
         */
        fun continueCards(rail: RailUi, mainPaths: Map<String, String> = emptyMap()): List<RailEntry> =
            rail.recent.filter { entry ->
                when (entry) {
                    is RailEntry.Tab -> entry.tab.path.isEmpty() ||
                        (entry.tab.path != mainPaths[entry.tab.packId] && !IndexPages.isIndex(entry.tab.path))
                    is RailEntry.Play -> true
                    else -> false
                }
            }.take(CONTINUE)

        /** What the Add feed dialog offers; see [HomeFeedsModel.scopeChoices]. */
        fun scopeChoices(snap: LibrarySnapshot): List<ScopeChoice> = HomeFeedsModel.scopeChoices(snap)
    }
}

/** A java.time date as the shared models take it. */
fun LocalDate.toCivilDate(): CivilDate = CivilDate(year, monthValue, dayOfMonth)

class SharedHomePrefs(private val prefs: android.content.SharedPreferences) : HomePrefs {
    constructor(context: android.content.Context) : this(context.getSharedPreferences("home", android.content.Context.MODE_PRIVATE))

    override var seeded: Boolean
        get() = prefs.getBoolean(KEY_SEEDED, false)
        set(value) = prefs.edit().putBoolean(KEY_SEEDED, value).apply()

    override var dismissedFailures: Set<String>
        get() = prefs.getStringSet(KEY_DISMISSED, null).orEmpty().toSet()
        set(value) = prefs.edit().putStringSet(KEY_DISMISSED, value).apply()

    override var layout: HomeLayout
        get() = HomeLayout.decode(prefs.getString(KEY_ORDER, null), prefs.getStringSet(KEY_HIDDEN, null))
        set(value) {
            val (order, hidden) = HomeLayout.encode(value)
            prefs.edit().putString(KEY_ORDER, order).putStringSet(KEY_HIDDEN, hidden).apply()
        }

    private companion object {
        const val KEY_SEEDED = "seeded"
        const val KEY_DISMISSED = "dismissedFailures"
        const val KEY_ORDER = "layoutOrder"
        const val KEY_HIDDEN = "layoutHidden"
    }
}
