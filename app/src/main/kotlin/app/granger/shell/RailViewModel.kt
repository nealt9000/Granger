package app.granger.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.granger.core.library.Book
import app.granger.core.library.BookIds
import app.granger.core.library.BookPosition
import app.granger.core.library.Bookshelf
import app.granger.core.library.Library
import app.granger.core.library.MAX_RAIL_PINS
import app.granger.core.library.Pack
import app.granger.core.library.PackStatus
import app.granger.core.library.PinResult
import app.granger.core.library.RailPins
import app.granger.core.library.ReadingSessions
import app.granger.core.library.ReadingTab
import app.granger.core.library.RecentPlay
import app.granger.core.library.RecentSearch
import app.granger.core.library.ResearchChats
import app.granger.core.library.ChatSummary
import app.granger.media.PlayResumer
import app.granger.music.MusicSources
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import app.granger.core.player.NowPlaying
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** One article or book tab on the rail. [initial] fills the letter tile, since ZIM articles have no covers. */
data class RailTabUi(
    val tabId: Long,
    val title: String,
    /** The pack's title. */
    val subtitle: String,
    val initial: Char,
    /** False when the pack is missing, damaged or gone; the tile is greyed out. */
    val available: Boolean,
    /** The tab the reader is showing; outlined in the accent. */
    val current: Boolean,
    /** A book's cover image file, drawn instead of the letter tile when it can be decoded. */
    val coverPath: String? = null,
)

/**
 * One item on the rail's list: anything opened. [key] names it for pinning ("tab:12", "search:holst", "chat:3",
 * "play:album:…"); [at] is when it was last opened, searched, answered or played.
 */
sealed interface RailEntry {
    val key: String
    val at: Long
    val pinned: Boolean

    data class Tab(val tab: RailTabUi, override val at: Long, override val pinned: Boolean = false) : RailEntry {
        override val key get() = "$TAB${tab.tabId}"
    }

    data class Search(val query: String, override val at: Long, override val pinned: Boolean = false) : RailEntry {
        override val key get() = "$SEARCH$query"
    }

    data class Chat(val chatId: Long, val title: String, override val at: Long, override val pinned: Boolean = false) : RailEntry {
        override val key get() = "$CHAT$chatId"
    }

    /** Music or an audiobook; [playKey] is the `RecentPlay` key ("album:<albumKey>", "audiobook:<id>", "songs", …). */
    data class Play(val playKey: String, val title: String, override val at: Long, override val pinned: Boolean = false) : RailEntry {
        override val key get() = "$PLAY$playKey"
    }

    /** The voice reading [title] now (Plan V2): heads the list while it is loaded; tapping it opens the page. Not pinnable. */
    data class Reading(val sourceKey: String, val title: String) : RailEntry {
        override val key get() = "$READING$sourceKey"
        override val at: Long get() = Long.MAX_VALUE
        override val pinned: Boolean get() = false
    }

    companion object {
        const val TAB = "tab:"
        const val SEARCH = "search:"
        const val CHAT = "chat:"
        const val PLAY = "play:"
        const val READING = "reading:"
    }
}

/** The pins (newest pin first), then the newest [RailViewModel.UNPINNED] others. */
data class RailUi(val entries: List<RailEntry>)

/**
 * The rail's one list: tabs, searches, Research chats and plays merged newest first, pinned ones on top. A pin whose
 * item is gone (a closed tab, a forgotten search, a deleted chat, a play no longer recorded) is dropped as the list is
 * built. [currentTabId] is the tab on screen, null off the reader.
 */
class RailViewModel(
    private val sessions: ReadingSessions,
    library: Library,
    bookshelf: Bookshelf,
    private val chats: ResearchChats,
    currentTabId: StateFlow<Long?>,
    private val pins: RailPins,
    private val hidden: RailHidden,
    /** Newest first, as `PlaybackDao.observeRecentPlays` gives them; at least [SCAN] of them. */
    plays: Flow<List<RecentPlay>> = flowOf(emptyList()),
    /** A recorded play by its key, for a pinned play older than [plays] holds. */
    private val recentPlay: suspend (String) -> RecentPlay? = { null },
    private val resumer: PlayResumer? = null,
    /** The voice reading now (Plan V2); it heads the list while it is loaded. */
    voice: Flow<NowPlaying.Voice?> = flowOf(null),
) : ViewModel() {
    private class Shelf(val packs: Map<String, Pack>, val books: Map<String, Book>, val positions: Map<String, BookPosition>)

    private class Opened(
        val tabs: List<ReadingTab>,
        val searches: List<RecentSearch>,
        val chats: List<ChatSummary>,
        val plays: List<RecentPlay>,
    )

    /**
     * Plays [item] again. Null when it started; otherwise the notice the Library shows (the album, playlist or book is
     * gone, or nothing in it can play now).
     */
    suspend fun resume(item: RailEntry.Play): String? {
        val played = try {
            resumer?.resume(item.playKey, item.title) == true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        return if (played) null else "“${item.title}” can't be played: it's no longer in the library, or none of its files are on the phone."
    }

    private val shelf = combine(library.packs, bookshelf.books, bookshelf.positions) { packs, books, positions ->
        Shelf(packs.associateBy { it.id }, books.associateBy { it.id }, positions)
    }

    // Wider than the list, so pinned and removed items don't leave it short.
    private val opened = combine(sessions.railTabs(SCAN), sessions.recentSearchRows(SCAN), chats.recent(SCAN), plays) { tabs, searches, chats, plays ->
        Opened(tabs, searches, chats, plays)
    }

    private val list: Flow<RailUi> = combine(opened, shelf, currentTabId, pins.pins, hidden.hidden) { opened, shelf, current, pinList, hiddenKeys ->
        val all = (
            opened.tabs.map { tabEntry(it, shelf, current) } +
                opened.searches.map { RailEntry.Search(it.query, it.searchedAt) } +
                opened.chats.map { RailEntry.Chat(it.id, it.title, it.updatedAt) } +
                opened.plays.map { RailEntry.Play(it.key, MusicSources.title(it.key, it.title), it.playedAt) }
            ).associateBy { it.key }
        val gone = mutableListOf<String>()
        val pinned = pinList.mapNotNull { pin ->
            when (val found = all[pin.key]?.let(Found::Entry) ?: lookUp(pin.key, shelf, current)) {
                is Found.Entry -> found.entry.pinned()
                Found.Gone -> null.also { gone += pin.key }
                Found.Unknown -> null
            }
        }
        if (gone.isNotEmpty()) viewModelScope.launch { pins.dropMissing(gone) }
        val pinnedKeys = pinList.mapTo(HashSet()) { it.key }
        val others = all.values
            .filter { it.key !in pinnedKeys && !hiddenKeys.hides(it) }
            .sortedByDescending { it.at }
            .take(UNPINNED)
        RailUi(pinned + others)
    }

    val ui: StateFlow<RailUi> = combine(list, voice) { list, v ->
        if (v == null) list else RailUi(listOf(RailEntry.Reading(v.sourceKey, v.title)) + list.entries)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RailUi(emptyList()))

    private sealed interface Found {
        data class Entry(val entry: RailEntry) : Found
        data object Gone : Found
        /** It couldn't be read just now; the pin stays, unshown. */
        data object Unknown : Found
    }

    /** A pinned item older than the lists the rail watches. Searches are all watched (50 are kept), so one not seen is gone. */
    private suspend fun lookUp(key: String, shelf: Shelf, current: Long?): Found = try {
        when {
            key.startsWith(RailEntry.TAB) -> key.removePrefix(RailEntry.TAB).toLongOrNull()
                ?.let { sessions.tab(it) }?.takeIf { it.onRail }?.let { Found.Entry(tabEntry(it, shelf, current)) } ?: Found.Gone
            key.startsWith(RailEntry.CHAT) -> key.removePrefix(RailEntry.CHAT).toLongOrNull()
                ?.let { chats.chat(it) }?.let { Found.Entry(RailEntry.Chat(it.id, it.title, it.updatedAt)) } ?: Found.Gone
            key.startsWith(RailEntry.PLAY) -> recentPlay(key.removePrefix(RailEntry.PLAY))
                ?.let { Found.Entry(RailEntry.Play(it.key, MusicSources.title(it.key, it.title), it.playedAt)) } ?: Found.Gone
            else -> Found.Gone
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Found.Unknown
    }

    private fun RailEntry.pinned(): RailEntry = when (this) {
        is RailEntry.Tab -> copy(pinned = true)
        is RailEntry.Search -> copy(pinned = true)
        is RailEntry.Chat -> copy(pinned = true)
        is RailEntry.Play -> copy(pinned = true)
        is RailEntry.Reading -> this                        // never pinned: it is there while the voice reads
    }

    private fun tabEntry(tab: ReadingTab, shelf: Shelf, current: Long?): RailEntry.Tab {
        val ui = if (BookIds.isBook(tab.packId)) {
            val book = shelf.books[tab.packId]
            RailTabUi(
                tabId = tab.id,
                title = book?.title ?: tab.title,
                subtitle = book?.let { bookSubtitle(it, shelf.positions[it.id]) } ?: HistoryViewModel.REMOVED_BOOK,
                initial = initialOf(book?.title ?: tab.title),
                available = book?.status == PackStatus.OK,
                current = tab.id == current,
                coverPath = book?.coverPath,
            )
        } else {
            val pack = shelf.packs[tab.packId]
            RailTabUi(
                tabId = tab.id,
                title = tab.title,
                subtitle = pack?.title ?: HistoryViewModel.REMOVED_PACK,
                initial = initialOf(tab.title),
                available = pack?.status == PackStatus.OK,
                current = tab.id == current,
            )
        }
        return RailEntry.Tab(ui, tab.openedAt)
    }

    /** "Chapter 3 · 43%" once the book has been opened; its author before that. */
    private fun bookSubtitle(book: Book, position: BookPosition?): String {
        if (position == null) return book.author
        val percent = "${(position.totalProgression * 100).roundToInt().coerceIn(0, 100)}%"
        return if (position.chapterTitle.isBlank()) percent else "${position.chapterTitle} · $percent"
    }

    /** Long-press "Pin to top". Null when pinned; otherwise the notice to show (all [MAX_RAIL_PINS] are taken). */
    suspend fun pin(entry: RailEntry): String? = when (pins.pin(entry.key)) {
        PinResult.FULL -> PINS_FULL
        PinResult.PINNED, PinResult.ALREADY_PINNED -> null
    }

    fun unpin(entry: RailEntry) {
        viewModelScope.launch { pins.unpin(entry.key) }
    }

    /**
     * Long-press "Remove from rail". A tab is closed (History keeps the article) and a search forgotten; a chat or a
     * play stays where it lives and is only taken off the rail, until it's used again.
     */
    fun remove(entry: RailEntry) {
        viewModelScope.launch {
            pins.unpin(entry.key)
            when (entry) {
                is RailEntry.Tab -> sessions.closeTab(entry.tab.tabId)
                is RailEntry.Search -> sessions.forgetSearch(entry.query)
                is RailEntry.Chat, is RailEntry.Play -> hidden.hide(entry.key, entry.at)
                is RailEntry.Reading -> Unit                // it leaves with the voice; the mini-player stops it
            }
        }
    }

    private fun initialOf(title: String): Char = title.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() ?: '#'

    companion object {
        /** Unpinned items on the list. */
        const val UNPINNED = 12

        /** How many of each kind the rail watches; every recent search (50 are kept). */
        const val SCAN = 50

        const val PINS_FULL = "$MAX_RAIL_PINS items are pinned already. Unpin one first."
    }
}
