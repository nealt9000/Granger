package app.granger.home

import app.granger.UiText
import app.granger.core.library.FeedKind
import app.granger.core.library.FilingKeys
import app.granger.core.library.HomeDraw
import app.granger.core.library.HomeFeed
import app.granger.core.library.HomeFeeds
import app.granger.core.library.PackStatus
import app.granger.screens.CivilDate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A feed's row: [title] once drawn ([FeedDrawer.title]); [drawn] is null while its cards are being drawn. */
data class FeedUi(val feed: HomeFeed, val scopeName: UiText, val title: UiText, val drawn: Drawn?)

/** A scope the Add feed dialog offers. */
data class ScopeChoice(val scope: FeedScope, val label: UiText)

/** Home's feeds as a screen shows them: the rows with something to show, and what Add feed offers. */
data class HomeFeedsUi(val feeds: List<FeedUi> = emptyList(), val scopeChoices: List<ScopeChoice> = emptyList())

/** What a feed kind is called on Home (Add feed's choices, a row still drawing); Android's `label` resolves it. */
val FeedKind.labelKey: String
    get() = when (this) {
        FeedKind.RANDOM -> "home_kind_random"
        FeedKind.ON_THIS_DAY -> "home_kind_on_this_day"
        FeedKind.SEARCH -> "home_kind_search"
    }

/**
 * Home's feeds (Plan H2; shared since Plan I4b Task 8): each feed whose scope names something on the phone, drawn once
 * a day and cached in `home_draws` (Decision 5); cached rows show at once and missing ones fill in as they are drawn on
 * [io]. Add feed's choices, adding, removing and the first-run feeds. Lives in [scope] (Android: Home's view model).
 *
 * [snapshot] is what's on the phone (packs, books, shelves, albums, audiobooks, the catalog); [today] is read now and
 * on each [refresh].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeFeedsModel(
    private val scope: CoroutineScope,
    private val homeFeeds: HomeFeeds,
    private val drawer: FeedDrawer,
    private val snapshot: Flow<LibrarySnapshot>,
    private val io: CoroutineDispatcher = app.granger.screens.ioDispatcher,
    private val today: () -> CivilDate = CivilDate::today,
) {
    private val day = MutableStateFlow(today())

    private class Planned(val feed: HomeFeed, val scope: Resolved)

    /**
     * Every feed that resolves, in Home order, with its cards: cached ones first, then the rest as they are drawn. A row
     * whose draw came back empty has `drawn.cards` empty (a screen hides it, as [ui] does).
     */
    val rows: Flow<List<FeedUi>> = combine(homeFeeds.feeds, snapshot, day) { feeds, snap, d ->
        Triple(feeds.mapNotNull { feed -> FeedScope.parse(feed.scope)?.resolve(snap)?.let { Planned(feed, it) } }, snap, d)
    }.transformLatest { (planned, snap, d) ->
        val rows = planned.map { cached(it, snap, d).let { drawn -> FeedUi(it.feed, it.scope.name, FeedDrawer.title(it.feed, it.scope, d, drawn?.mentions == true), drawn) } }.toMutableList()
        emit(rows.toList())
        for ((i, plan) in planned.withIndex()) {
            if (rows[i].drawn != null) continue
            val drawn = withContext(io) { runCatching { drawer.draw(plan.feed, plan.scope, d) }.getOrNull() }
            if (drawn != null) homeFeeds.saveDraw(HomeDraw(plan.feed.id, iso(d), Cards.encode(drawn)))
            rows[i] = rows[i].copy(
                title = FeedDrawer.title(plan.feed, plan.scope, d, drawn?.mentions == true),
                drawn = drawn ?: Drawn(emptyList()),
            )
            emit(rows.toList())
        }
    }

    /** The feeds with something to show (or still drawing) and Add feed's choices; started on first look. */
    val ui: StateFlow<HomeFeedsUi> by lazy {
        combine(rows, snapshot) { r, snap -> HomeFeedsUi(shown(r), scopeChoices(snap)) }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), HomeFeedsUi())
    }

    /** The day's cached cards, pruned of targets that are gone; null when there is no usable draw for today. */
    private suspend fun cached(plan: Planned, snap: LibrarySnapshot, d: CivilDate): Drawn? {
        val draw = homeFeeds.draw(plan.feed.id) ?: return null
        if (draw.day != iso(d)) return null
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

    /** Adds a feed at the end of the feeds (Add feed: a scope, a kind and, for Search, the words). */
    fun add(scope: FeedScope, kind: FeedKind, query: String = "") {
        this.scope.launch { homeFeeds.add(scope.text, kind, query) }
    }

    fun remove(id: Long) {
        scope.launch { homeFeeds.remove(id) }
    }

    /**
     * The first-run feeds (Decision 4), once: when [seeded] says not yet and there are no feeds, [defaults] for the
     * [languages] (the library's, then the app's); then [markSeeded], even when the user later removes them all. A
     * Quotes feed sits dormant until a Wikiquote pack is installed.
     */
    suspend fun seedDefaults(seeded: () -> Boolean, markSeeded: () -> Unit, languages: suspend () -> List<String>) {
        if (seeded()) return
        if (homeFeeds.feeds().isEmpty()) {
            for ((scope, kind) in defaults(languages().distinct())) homeFeeds.add(scope.text, kind)
        }
        markSeeded()
    }

    companion object {
        val DEFAULTS: List<Pair<FeedScope, FeedKind>> get() = defaults(listOf(app.granger.core.catalog.ENGLISH))

        /**
         * The first-run feeds for the library's [languages], main first (Plan L3): a Random feed for each language's
         * Granger collection (Granger Classics, Clásicos Granger), in that order. A collection not on the phone stays
         * dormant, like Quotes.
         */
        fun defaults(languages: List<String>): List<Pair<FeedScope, FeedKind>> = buildList {
            add(FeedScope.Family("wikipedia") to FeedKind.ON_THIS_DAY)
            add(FeedScope.Family("wikipedia") to FeedKind.RANDOM)
            languages.mapNotNull { FilingKeys.GRANGER_COLLECTIONS[it] }.distinct()
                .forEach { add(FeedScope.Collection("c:$it") to FeedKind.RANDOM) }
            add(FeedScope.Family("wikiquote") to FeedKind.RANDOM)
        }

        /** The rows Home shows: drawing, or drawn with cards; a draw that found nothing hides its row. */
        fun shown(rows: List<FeedUi>): List<FeedUi> = rows.filter { it.drawn?.cards?.isEmpty() != true }

        /** What the Add feed dialog offers: the library, each whole pack, each collection, music and audiobooks when there are any. */
        fun scopeChoices(snap: LibrarySnapshot): List<ScopeChoice> {
            val choices = mutableListOf(ScopeChoice(FeedScope.Library, UiText.Res("home_scope_whole_library")))
            snap.packs.filter { it.status == PackStatus.OK && it.name.isNotBlank() }.sortedBy { it.title.lowercase() }
                .mapTo(choices) { ScopeChoice(FeedScope.Pack(it.name), UiText.Raw(it.title)) }
            snap.shelves.flatMap { it.allCollections }.filterNot { it.isFolder || it.isEmpty }
                .mapTo(choices) { ScopeChoice(FeedScope.Collection(it.collection.id), snap.collectionName(it.collection)) }
            if (snap.albums.any { it.status == PackStatus.OK }) choices += ScopeChoice(FeedScope.Music, UiText.Res("home_scope_music"))
            if (snap.audiobooks.any { it.status == PackStatus.OK }) choices += ScopeChoice(FeedScope.Audiobooks, UiText.Res("home_scope_audiobooks"))
            return choices
        }

        /**
         * [layout] with feed [id] one place up or down among everything Home shows (Plan HY: feeds and sections share one
         * order, kept with Home's layout; the feeds' own order in the store stays as it was).
         */
        fun moved(layout: HomeLayout, id: Long, up: Boolean, feeds: List<FeedUi>): HomeLayout =
            layout.move("feed:$id", up, feeds.map { it.feed.id })

        /** "2026-10-22": the day a draw is stored under (as `LocalDate.toString` wrote it). */
        fun iso(d: CivilDate): String =
            "${d.year.toString().padStart(4, '0')}-${d.month.toString().padStart(2, '0')}-${d.day.toString().padStart(2, '0')}"
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
