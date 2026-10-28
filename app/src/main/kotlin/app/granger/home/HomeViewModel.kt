package app.granger.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.granger.core.catalog.CatalogSource
import app.granger.core.downloads.DownloadRecord
import app.granger.core.downloads.DownloadState
import app.granger.core.downloads.Downloads
import app.granger.core.library.onShelf
import app.granger.core.library.Albums
import app.granger.core.library.Bookshelf
import app.granger.core.library.FeedKind
import app.granger.core.library.HomeDraw
import app.granger.core.library.HomeFeed
import app.granger.core.library.HomeFeeds
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
    /** Onboarding's downloads while they run (Plan H3); null once they have all landed or were cancelled. */
    val setup: SetupProgress? = null,
)

/** The latest onboarding group's progress: files landed of files asked for, and bytes. */
data class SetupProgress(val done: Int, val total: Int, val bytesDone: Long, val bytes: Long, val failed: Int) {
    val fraction: Float get() = if (bytes <= 0L) 0f else (bytesDone.toFloat() / bytes).coerceIn(0f, 1f)
}

/** The one-time marks Home keeps (SharedPreferences `"home"`). */
interface HomePrefs {
    /** The first-run feeds were inserted (Decision 4); set even when the user removes them all. */
    var seeded: Boolean
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
    downloads: Downloads,
    rail: StateFlow<RailUi>,
    private val homeFeeds: HomeFeeds,
    private val drawer: FeedDrawer,
    shelves: Shelves,
    media: MediaLibrary,
    private val prefs: HomePrefs,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val today: () -> LocalDate = LocalDate::now,
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

    private val setup = downloads.records.map { setupProgress(it) }

    val ui: StateFlow<HomeUi> = combine(strip, rail, feedRows, snapshot, setup) { report, railUi, rows, snap, progress ->
        HomeUi(
            strip = report, continueCards = continueCards(railUi), feeds = rows.filter { it.drawn?.cards?.isEmpty() != true },
            scopeChoices = scopeChoices(snap), setup = progress,
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

        /** The newest onboarding group (`onboarding:<epoch>`) that still has files to land, else null. */
        fun setupProgress(records: List<DownloadRecord>): SetupProgress? {
            val group = records.mapNotNull { it.groupId?.takeIf { g -> g.startsWith(SETUP_GROUP) } }.maxOrNull() ?: return null
            val rows = records.filter { it.groupId == group }
            val done = rows.count { it.state == DownloadState.INSTALLED }
            if (done == rows.size) return null
            return SetupProgress(
                done = done,
                total = rows.size,
                bytesDone = rows.sumOf { if (it.state == DownloadState.INSTALLED) it.sizeBytes else it.bytesDone },
                bytes = rows.sumOf { it.sizeBytes },
                failed = rows.count { it.state == DownloadState.FAILED },
            )
        }

        const val SETUP_GROUP = "onboarding:"

        /**
         * The rail's tabs and plays, pins first, then newest; searches, chats and the voice's live row are not "continue".
         * From [RailUi.recent], so Continue isn't cut to the rail's four.
         */
        fun continueCards(rail: RailUi): List<RailEntry> =
            rail.recent.filter { it is RailEntry.Tab || it is RailEntry.Play }.take(CONTINUE)

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

    private companion object { const val KEY_SEEDED = "seeded" }
}
