package app.granger.home

import androidx.lifecycle.ViewModel
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
import app.granger.core.library.HomeDraw
import app.granger.core.library.HomeFeed
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
import java.time.LocalDate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A feed's row: [drawn] is null while its cards are being drawn. */
data class FeedUi(val feed: HomeFeed, val scopeName: String, val drawn: Drawn?)

/** A scope the Add feed dialog offers. */
data class ScopeChoice(val scope: FeedScope, val label: String)

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
}

/**
 * Home's data: the readiness report over the same four flows the Readiness screen uses, Continue from the rail's list
 * (no second query, no second cover loader), and the feeds, each drawn once a day and cached in `home_draws`
 * (Decision 5). Drawing runs on [io]; cached rows show at once and missing ones fill in as they are drawn.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    catalog: CatalogSource,
    library: Library,
    bookshelf: Bookshelf,
    private val downloads: Downloads,
    rail: StateFlow<RailUi>,
    private val homeFeeds: HomeFeeds,
    private val drawer: FeedDrawer,
    shelves: Shelves,
    media: MediaLibrary,
    private val prefs: HomePrefs,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val today: () -> LocalDate = LocalDate::now,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val day = MutableStateFlow(today())

    private val strip = combine(catalog.state, library.packs, bookshelf.books, downloads.records) { c, packs, books, records ->
        ReadinessModel.report(c.catalog, packs, books, records, day.value)
    }.flowOn(io)

    private val mediaItems = combine(media.tracks, media.audiobooks) { tracks, audiobooks -> Albums.summaries(tracks) to audiobooks }
    private val snapshot: Flow<LibrarySnapshot> =
        combine(library.packs, bookshelf.books, shelves.shelves, mediaItems, catalog.state) { packs, books, shelfList, (albums, audiobooks), c ->
            // Pack documents (Plan P2) belong to their packs: no feed draws them as books.
            LibrarySnapshot(packs, books.filter { it.onShelf }, shelfList, albums, audiobooks, c.catalog)
        }

    private class Planned(val feed: HomeFeed, val scope: Resolved)

    /** Every feed that resolves, with its cards: cached ones first, then the rest as they are drawn. */
    private val feedRows: Flow<List<FeedUi>> = combine(homeFeeds.feeds, snapshot, day) { feeds, snap, d ->
        Triple(feeds.mapNotNull { feed -> FeedScope.parse(feed.scope)?.resolve(snap)?.let { Planned(feed, it) } }, snap, d)
    }.transformLatest { (planned, snap, d) ->
        val rows = planned.map { FeedUi(it.feed, it.scope.name, cached(it, snap, d)) }.toMutableList()
        emit(rows.toList())
        for ((i, plan) in planned.withIndex()) {
            if (rows[i].drawn != null) continue
            val drawn = withContext(io) { runCatching { drawer.draw(plan.feed, plan.scope, d) }.getOrNull() }
            if (drawn != null) homeFeeds.saveDraw(HomeDraw(plan.feed.id, d.toString(), Cards.encode(drawn)))
            rows[i] = rows[i].copy(drawn = drawn ?: Drawn(rows[i].scopeName, emptyList()))
            emit(rows.toList())
        }
    }

    private val dismissed = MutableStateFlow(prefs.dismissedFailures)

    private val catalogIds = catalog.state.map { s -> s.catalog?.let { c -> (c.packs + c.models + c.books + c.voices).mapTo(HashSet()) { it.id } } }

    private val downloadsCard = combine(downloads.records, downloads.live, dismissed, catalogIds) { records, live, closed, ids ->
        downloadsCard(DownloadQueueSummary.of(records, live, clock()), closed, ids)
    }.flowOn(io)

    /** Each OK pack's main entry, for Continue to leave out (opening an archive reads its file, so on [io]). */
    private val mainPaths: Flow<Map<String, String>> = library.packs.map { packs ->
        packs.filter { it.status == PackStatus.OK }.mapNotNull { pack -> library.archive(pack.id)?.mainPath?.let { pack.id to it } }.toMap()
    }.flowOn(io)

    private val continuing = combine(rail, mainPaths) { railUi, mains -> continueCards(railUi, mains) }

    val ui: StateFlow<HomeUi> = combine(strip, continuing, feedRows, snapshot, downloadsCard) { report, cards, rows, snap, card ->
        HomeUi(
            strip = report, continueCards = cards, feeds = rows.filter { it.drawn?.cards?.isEmpty() != true },
            scopeChoices = scopeChoices(snap), downloads = card,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUi())

    init {
        // The cached catalog, no network; MainActivity loads it too, and the repository loads once.
        viewModelScope.launch { catalog.load() }
        viewModelScope.launch { seedDefaults() }
    }

    /** The day's cached cards, pruned of targets that are gone; null when there is no usable draw for today. */
    private suspend fun cached(plan: Planned, snap: LibrarySnapshot, d: LocalDate): Drawn? {
        val draw = homeFeeds.draw(plan.feed.id) ?: return null
        if (draw.day != d.toString()) return null
        val drawn = Cards.decode(draw.cardsJson) ?: return null
        val kept = drawn.cards.filter { it.target.exists(snap) }
        if (kept.isEmpty()) {
            homeFeeds.clearDraw(plan.feed.id)
            return null
        }
        return drawn.copy(cards = kept)
    }

    /** A new look at Home (resume): the date may have turned. */
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

    /** Closes the stopped card until something else fails. */
    fun dismissStopped(card: DownloadsCard.Stopped) {
        prefs.dismissedFailures = card.keys
        dismissed.value = card.keys
    }

    fun refresh() {
        day.value = today()
    }

    fun addFeed(scope: FeedScope, kind: FeedKind, query: String = "") {
        viewModelScope.launch { homeFeeds.add(scope.text, kind, query) }
    }

    fun removeFeed(id: Long) {
        viewModelScope.launch { homeFeeds.remove(id) }
    }

    fun moveFeed(id: Long, up: Boolean) {
        viewModelScope.launch { homeFeeds.move(id, up) }
    }

    /** The first-run feeds (Decision 4), once; a Quotes feed sits dormant until a Wikiquote pack is installed. */
    private suspend fun seedDefaults() {
        if (prefs.seeded) return
        if (homeFeeds.feeds().isEmpty()) {
            for ((scope, kind) in DEFAULTS) homeFeeds.add(scope.text, kind)
        }
        prefs.seeded = true
    }

    companion object {
        const val CONTINUE = 8

        val DEFAULTS: List<Pair<FeedScope, FeedKind>> = listOf(
            FeedScope.Family("wikipedia") to FeedKind.ON_THIS_DAY,
            FeedScope.Family("wikipedia") to FeedKind.RANDOM,
            FeedScope.Collection("c:granger-classics") to FeedKind.RANDOM,
            FeedScope.Family("wikiquote") to FeedKind.RANDOM,
        )

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

        /** What the Add feed dialog offers: the library, each whole pack, each collection, music and audiobooks when there are any. */
        fun scopeChoices(snap: LibrarySnapshot): List<ScopeChoice> {
            val choices = mutableListOf(ScopeChoice(FeedScope.Library, "Whole library"))
            snap.packs.filter { it.status == PackStatus.OK && it.name.isNotBlank() }.sortedBy { it.title.lowercase() }
                .mapTo(choices) { ScopeChoice(FeedScope.Pack(it.name), it.title) }
            snap.shelves.flatMap { it.allCollections }.filterNot { it.isFolder || it.isEmpty }
                .mapTo(choices) { ScopeChoice(FeedScope.Collection(it.collection.id), it.collection.name) }
            if (snap.albums.any { it.status == PackStatus.OK }) choices += ScopeChoice(FeedScope.Music, "Music")
            if (snap.audiobooks.any { it.status == PackStatus.OK }) choices += ScopeChoice(FeedScope.Audiobooks, "Audiobooks")
            return choices
        }
    }
}

/** Whether what the card opens is still on the phone and whole. */
fun CardTarget.exists(snap: LibrarySnapshot): Boolean = when (this) {
    is CardTarget.Article -> snap.packs.any { it.id == packId && it.status == PackStatus.OK }
    is CardTarget.Book -> snap.books.any { it.id == bookId && it.status == PackStatus.OK }
    is CardTarget.Album -> snap.albums.any { it.key == albumKey && it.status == PackStatus.OK }
    is CardTarget.Audiobook -> snap.audiobooks.any { it.id == audiobookId && it.status == PackStatus.OK }
    is CardTarget.Search -> true
}

class SharedHomePrefs(private val prefs: android.content.SharedPreferences) : HomePrefs {
    constructor(context: android.content.Context) : this(context.getSharedPreferences("home", android.content.Context.MODE_PRIVATE))

    override var seeded: Boolean
        get() = prefs.getBoolean(KEY_SEEDED, false)
        set(value) = prefs.edit().putBoolean(KEY_SEEDED, value).apply()

    override var dismissedFailures: Set<String>
        get() = prefs.getStringSet(KEY_DISMISSED, null).orEmpty().toSet()
        set(value) = prefs.edit().putStringSet(KEY_DISMISSED, value).apply()

    private companion object {
        const val KEY_SEEDED = "seeded"
        const val KEY_DISMISSED = "dismissedFailures"
    }
}
