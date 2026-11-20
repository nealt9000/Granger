package app.granger.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.granger.UiText
import app.granger.core.library.Book
import app.granger.core.library.BookFormat
import app.granger.core.library.Loose
import app.granger.core.library.ManifestItem
import app.granger.online.Availability
import app.granger.online.RemoteCollection
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One book of a collection read online (Plan BK). [mark] is where it lives: on this phone, saved for now, or online (null). */
data class RemoteBookRow(
    val key: String,
    val bookId: String,
    val title: String,
    val author: String,
    val bytes: Long,
    val format: BookFormat,
    val mark: Availability?,
    /** Its reading edition's title, when Granger made one. */
    val edition: String?,
    /** The reading edition's catalog book id: a tap opens it, and "Original scan" the pack's file. */
    val editionId: String? = null,
    /** The reading edition's size, shown in place of the scan's. */
    val editionBytes: Long = 0,
    /** Whether it can be read online: the list says where its bytes are. */
    val readable: Boolean,
)

/** A section of the collection, in the list's order; [name] blank for the books before the first section. */
data class RemoteSection(val name: String, val rows: List<RemoteBookRow>)

/** A book coming down from the pack: [done] of [total] bytes. */
data class Opening(val key: String, val title: String, val done: Long, val total: Long, val thenKeep: Boolean)

/** The question before a big book comes down (builder's call 7: over 50 MB). */
data class BigAsk(val key: String, val title: String, val bytes: Long, val overCap: Boolean)

data class RemoteCollectionUi(
    val loading: Boolean = true,
    /** The list couldn't be had (offline with none saved, or it didn't check out). */
    val noList: Boolean = false,
    val title: String = "",
    val provider: String = "",
    val packBytes: Long = 0,
    val total: Int = 0,
    val kept: Int = 0,
    val sections: List<RemoteSection> = emptyList(),
    val filter: String = "",
    val opening: Opening? = null,
    val bigAsk: BigAsk? = null,
    /** The whole collection is queued to come to this phone. */
    val packQueued: Boolean = false,
    /** The whole collection is on this phone: its own screen is the one to read it from. */
    val packHere: Boolean = false,
    val online: Boolean = true,
)

/** What the screen does once: open a book, or say something. */
sealed interface RemoteEvent {
    data class Open(val bookId: String, val title: String) : RemoteEvent
    data class Say(val text: UiText) : RemoteEvent
}

/**
 * A book pack's collection before (or without) the pack (Plan BK Task 4): its list, read online and kept in Saved for
 * now; each book's mark from the library's rows; opening one reads it from the pack by range; Keep this book keeps it on
 * its own; Keep the whole collection is the pack's download.
 */
class RemoteCollectionViewModel(
    private val load: suspend () -> RemoteCollection?,
    books: Flow<List<Book>>,
    private val read: suspend (key: String, progress: (Long, Long) -> Unit) -> String?,
    private val keepBook: suspend (bookId: String) -> Boolean,
    private val keepPack: suspend () -> Boolean,
    /** Opens a reading edition (a catalog book) without keeping it; its library id, or null. */
    private val readEdition: suspend (editionId: String) -> String? = { null },
    /** Keeps a reading edition: the catalog book's ordinary download. */
    private val keepEdition: suspend (editionId: String) -> Boolean = { false },
    packQueued: Flow<Boolean>,
    private val capBytes: () -> Long,
    private val online: () -> Boolean,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Whether the pack is installed (Plan BK: then every book is on this phone). */
    packHere: Flow<Boolean> = kotlinx.coroutines.flow.flowOf(false),
) : ViewModel() {
    private val remote = MutableStateFlow<RemoteCollection?>(null)
    private val state = MutableStateFlow(RemoteCollectionUi(online = online()))
    private val events = Channel<RemoteEvent>(Channel.BUFFERED)
    private var job: Job? = null

    val event: Flow<RemoteEvent> = events.receiveAsFlow()

    /** The library's rows for this collection's books, by id, with whether each one's file is here. */
    private val held = combine(remote, books) { r, all ->
        if (r == null) emptyMap() else {
            val prefix = r.bookId(r.items.firstOrNull() ?: return@combine emptyMap()).substringBeforeLast('/') + "/"
            val editions = r.items.mapNotNullTo(HashSet()) { it.edition?.id?.takeIf(String::isNotBlank)?.let(::editionBookId) }
            all.filter { it.id.startsWith(prefix) || it.id in editions }.associate { it.id to markOf(it) }
        }
    }.flowOn(io)

    private val pack = combine(packQueued, packHere) { queued, here -> queued to here }

    val ui: StateFlow<RemoteCollectionUi> = combine(state, remote, held, pack) { s, r, marks, (queued, here) ->
        if (r == null) return@combine s.copy(packQueued = queued && !here, packHere = here)
        val onPhone = Availability.OnPhone(full = true)
        val rows = r.items.filter { it.partOf == null }.map { item ->
            val editionId = item.edition?.id?.takeIf(String::isNotBlank)
            val mark = if (editionId != null) marks[editionBookId(editionId)] else marks[r.bookId(item)]
            rowOf(r, item, if (here && editionId == null) onPhone else mark)
        }
        val shown = if (s.filter.isBlank()) rows else rows.filter { it.title.contains(s.filter, true) || it.author.contains(s.filter, true) }
        val order = r.collection.groups
        val groupOf = r.items.associate { it.key to it.groupName }
        val sections = shown.groupBy { groupOf[it.key].orEmpty() }
            .entries.sortedBy { (name, _) -> if (name.isBlank()) -1 else order.indexOf(name).let { if (it < 0) order.size else it } }
            .map { (name, list) -> RemoteSection(name, list) }
        s.copy(
            loading = false, title = r.collection.title.ifBlank { r.pack.title }, provider = r.collection.provider.ifBlank { r.pack.provider },
            packBytes = r.pack.sizeBytes, total = rows.size, kept = rows.count { it.mark is Availability.OnPhone },
            sections = sections, packQueued = queued && !here, packHere = here,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RemoteCollectionUi(online = online()))

    init {
        viewModelScope.launch {
            val got = runCatching { load() }.getOrNull()
            if (got == null) state.value = state.value.copy(loading = false, noList = true) else remote.value = got
        }
    }

    fun setFilter(text: String) {
        state.value = state.value.copy(filter = text)
    }

    /** Taps a book: its reading edition when there is one, else the book, asking first when it's big and not here yet. */
    fun open(row: RemoteBookRow, thenKeep: Boolean = false) {
        if (state.value.opening != null) return
        val edition = row.editionId
        if (edition != null && !thenKeep) {
            openEdition(row, edition)
            return
        }
        if (row.mark == null && row.bytes > BIG_BYTES && state.value.bigAsk == null) {
            state.value = state.value.copy(bigAsk = BigAsk(row.key, row.title, row.bytes, overCap = row.bytes > capBytes()))
            return
        }
        start(row.key, row.title, row.bytes, thenKeep)
    }

    /** "Original scan": the pack's own file, read by range, even when there's a reading edition. */
    fun openOriginal(row: RemoteBookRow) = open(row.copy(editionId = null))

    private fun openEdition(row: RemoteBookRow, editionId: String) {
        state.value = state.value.copy(opening = Opening(row.key, row.edition ?: row.title, 0, 0, thenKeep = false))
        job = viewModelScope.launch {
            val id = readEdition(editionId)
            state.value = state.value.copy(opening = null)
            job = null
            if (id == null) say(COULDNT_OPEN) else events.send(RemoteEvent.Open(id, row.edition ?: row.title))
        }
    }

    /** The big-book question's answers. */
    fun answerBig(read: Boolean, keep: Boolean) {
        val ask = state.value.bigAsk ?: return
        state.value = state.value.copy(bigAsk = null)
        if (read || keep) start(ask.key, ask.title, ask.bytes, thenKeep = keep)
    }

    /** Keep this book: a reading edition is the catalog book's download; a pack book is read first, then kept on its own. */
    fun keep(row: RemoteBookRow) {
        if (row.mark is Availability.OnPhone) return
        row.editionId?.let { id ->
            viewModelScope.launch { say(if (keepEdition(id)) KEPT else COULDNT_KEEP) }
            return
        }
        if (row.mark == Availability.Saved) {
            viewModelScope.launch { say(if (keepBook(row.bookId)) KEPT else COULDNT_KEEP) }
            return
        }
        if (row.bytes > BIG_BYTES) {
            state.value = state.value.copy(bigAsk = BigAsk(row.key, row.title, row.bytes, overCap = row.bytes > capBytes()))
            return
        }
        start(row.key, row.title, row.bytes, thenKeep = true)
    }

    fun cancel() {
        job?.cancel()
        job = null
        state.value = state.value.copy(opening = null)
    }

    fun keepWholeCollection() {
        viewModelScope.launch { if (!keepPack()) say(COULDNT_KEEP) }
    }

    private fun start(key: String, title: String, bytes: Long, thenKeep: Boolean) {
        state.value = state.value.copy(opening = Opening(key, title, 0, bytes, thenKeep))
        job = viewModelScope.launch {
            val id = read(key) { done, total ->
                state.value.opening?.takeIf { it.key == key }?.let { state.value = state.value.copy(opening = it.copy(done = done, total = total)) }
            }
            state.value = state.value.copy(opening = null)
            job = null
            when {
                id == null -> say(COULDNT_OPEN)
                thenKeep -> say(if (keepBook(id)) KEPT else COULDNT_KEEP)
                else -> events.send(RemoteEvent.Open(id, title))
            }
        }
    }

    private suspend fun say(text: UiText) = events.send(RemoteEvent.Say(text))

    private fun rowOf(r: RemoteCollection, item: ManifestItem, mark: Availability?) = RemoteBookRow(
        key = item.key, bookId = r.bookId(item), title = item.workTitle ?: item.title, author = item.author,
        bytes = item.bytes, format = item.format, mark = mark, edition = item.edition?.title, readable = item.zimOffset != null,
        editionId = item.edition?.id?.takeIf { it.isNotBlank() }, editionBytes = item.edition?.bytes ?: 0,
    )

    companion object {
        /** Builder's call 7: a book over this asks before it comes down. */
        const val BIG_BYTES = 50L * 1024 * 1024

        private val KEPT = UiText.Res(app.granger.R.string.bk_kept_notice)
        private val COULDNT_KEEP = UiText.Res(app.granger.R.string.bk_couldnt_keep)
        private val COULDNT_OPEN = UiText.Res(app.granger.R.string.bk_couldnt_open)

        /** A reading edition's library id: the Granger book its catalog id names. */
        internal fun editionBookId(editionId: String) = app.granger.core.library.BookIds.of(app.granger.core.library.BookOrigin.GRANGER, editionId)

        /**
         * A library row's mark: kept, or the pack's own copy, is on this phone; read online is saved for now (a pack
         * book read online, or a Granger book opened without keeping, which is hidden).
         */
        internal fun markOf(book: Book): Availability? {
            if (!File(book.filePath).isFile) return null
            return when {
                book.loose == Loose.SAVED -> Availability.Saved
                book.origin == app.granger.core.library.BookOrigin.GRANGER && book.hidden -> Availability.Saved
                else -> Availability.OnPhone(full = true)
            }
        }
    }
}
