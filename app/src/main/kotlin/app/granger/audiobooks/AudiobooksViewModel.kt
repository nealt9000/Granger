package app.granger.audiobooks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.granger.core.library.AudioBookmark
import app.granger.core.library.Audiobook
import app.granger.core.library.AudiobookChapter
import app.granger.core.library.AudiobookState
import app.granger.core.library.MediaLibrary
import app.granger.core.library.PackStatus
import app.granger.core.library.PlaybackDao
import app.granger.core.library.Shelves
import app.granger.core.player.NowPlaying
import app.granger.core.player.PlayerHub
import app.granger.core.player.PlayerUi
import app.granger.player.BookProgress
import app.granger.player.bookProgressAt
import app.granger.player.chapterAt
import app.granger.core.downloads.DownloadRecord
import app.granger.media.MediaDownloads
import app.granger.core.library.SearchText
import app.granger.core.library.ShelfKey
import app.granger.library.authorKey
import app.granger.library.shelf.LibraryViewPrefs
import app.granger.library.shelf.ShelfKind
import app.granger.library.shelf.ShelfSections
import app.granger.library.shelf.ShelfView
import app.granger.library.shelf.sortNamed
import app.granger.library.shelf.sortedByShelfKey
import app.granger.library.shelf.toggled
import app.granger.sources.ItemStatus
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The Audiobooks media type's id (seeded). */
const val AUDIOBOOKS_TYPE_ID = "audiobooks"

/** The unscoped shelf, mirroring Music's "All music". */
const val ALL_AUDIOBOOKS = "All audiobooks"

/** The listener's audiobook rows: positions and bookmarks ([PlaybackDao] in the app). */
interface AudiobookListening {
    val states: Flow<List<AudiobookState>>
    suspend fun state(audiobookId: String): AudiobookState?
    suspend fun saveState(state: AudiobookState)
    fun bookmarks(audiobookId: String): Flow<List<AudioBookmark>>
    suspend fun updateBookmark(bookmark: AudioBookmark)
    suspend fun deleteBookmark(bookmark: AudioBookmark)
}

class DaoListening(private val dao: PlaybackDao) : AudiobookListening {
    override val states: Flow<List<AudiobookState>> = dao.observeAudiobookStates()
    override suspend fun state(audiobookId: String) = dao.audiobookState(audiobookId)
    override suspend fun saveState(state: AudiobookState) = dao.upsertAudiobookState(state)
    override fun bookmarks(audiobookId: String) = dao.observeBookmarks(audiobookId)
    override suspend fun updateBookmark(bookmark: AudioBookmark) = dao.updateBookmark(bookmark)
    override suspend fun deleteBookmark(bookmark: AudioBookmark) = dao.deleteBookmark(bookmark)
}

/** Where the listener is with a book: never started (no state row), part-way, or finished. */
enum class Listening { UNPLAYED, IN_PROGRESS, FINISHED }

private fun fractionOf(p: BookProgress): Float =
    if (p.durationMs > 0) (p.positionMs.toFloat() / p.durationMs).coerceIn(0f, 1f) else 0f

data class ShelfBook(val book: Audiobook, val listening: Listening, val progress: BookProgress, val lastPlayedAt: Long?) {
    val missing: Boolean get() = book.status != PackStatus.OK
    val fraction: Float get() = fractionOf(progress)
}

/** How the audiobook shelf is ordered (Plan LV Decision 5); Recent is by last played, with no letters. */
enum class AudiobookSort(val label: String) { TITLE("Title"), AUTHOR("Author"), RECENT("Recent") }

/**
 * The shelf: every book as [sections] (A–Z under letters when long and sorted by name), and [continueRow], the books
 * part-way through by last played, at most [AudiobookShelf.CONTINUE_MAX] (Plan LV Decision 7). [books] is [sections]
 * in the order shown.
 */
data class AudiobookShelfUi(
    val title: String,
    val sections: ShelfSections<ShelfBook>,
    val continueRow: List<ShelfBook> = emptyList(),
    val sort: AudiobookSort = AudiobookSort.TITLE,
) {
    val books: List<ShelfBook> get() = sections.items.map { it.value }
}

/**
 * [currentChapter]: the ordinal the listener is in, while part-way. [bookmarks] are in book order. A downloaded book
 * (LibriVox, archive.org) has its [download] state, its files in ([filesDone]) of [filesTotal], and [onRemoveDownload];
 * phone media has neither (null). [inPlayer]: the player has this book loaded, [playing] or paused.
 */
data class AudiobookPage(
    val book: Audiobook,
    val listening: Listening,
    val progress: BookProgress,
    val chapters: List<AudiobookChapter>,
    val currentChapter: Int?,
    val bookmarks: List<AudioBookmark>,
    val download: ItemStatus? = null,
    val filesDone: Int = 0,
    val filesTotal: Int = 0,
    val onRemoveDownload: (() -> Unit)? = null,
    val inPlayer: Boolean = false,
    val playing: Boolean = false,
) {
    val missing: Boolean get() = book.status != PackStatus.OK
    val playable: Boolean get() = !missing && chapters.isNotEmpty()
    val fraction: Float get() = fractionOf(progress)
    val playLabel: String get() = when {
        missing -> "Missing"
        inPlayer && playing -> "Pause"
        listening == Listening.FINISHED -> "Play from start"
        listening == Listening.IN_PROGRESS || inPlayer -> "Resume"
        else -> "Play"
    }
}

/** Pure shelf logic. */
object AudiobookShelf {
    /** Within this of the end, a book marked unfinished goes back to its start (it would only end again). */
    const val END_SLACK_MS = 5_000L

    fun listening(state: AudiobookState?): Listening = when {
        state == null -> Listening.UNPLAYED
        state.finished -> Listening.FINISHED
        else -> Listening.IN_PROGRESS
    }

    fun progress(book: Audiobook, chapters: List<AudiobookChapter>, state: AudiobookState?): BookProgress =
        if (state == null) bookProgressAt(chapters, "", 0L, book.durationMs)
        else bookProgressAt(chapters, state.trackId, state.positionMs, book.durationMs, state.finished)

    /** The Continue row holds at most this many books. */
    const val CONTINUE_MAX = 4

    private fun titleKey(b: ShelfBook) = ShelfKey.of(b.book.title)
    private val byTitle = compareBy<ShelfBook>({ it.book.title.lowercase(Locale.ROOT) }, { it.book.id })
    private val byLastPlayed = compareByDescending<ShelfBook> { it.lastPlayedAt ?: 0L }.thenBy(::titleKey).then(byTitle)

    /**
     * Every book by [sort], finished ones in their place (Plan LV Decision 7): Title A–Z by [ShelfKey]; Author by
     * surname as books are, unknown authors last; Recent by last played, never-played ones after by title.
     */
    fun sections(books: List<ShelfBook>, sort: AudiobookSort): ShelfSections<ShelfBook> = when (sort) {
        AudiobookSort.TITLE -> ShelfSections.of(books.sortedByShelfKey(::titleKey, byTitle), byName = true, key = { it.book.id }) {
            ShelfKey.letter(titleKey(it))
        }
        AudiobookSort.AUTHOR -> ShelfSections.of(
            books.sortedWith(
                compareBy<ShelfBook> { it.book.author.isBlank() }.thenBy { authorKey(it.book.author) }
                    .thenBy { SearchText.fold(it.book.author) }.thenBy(::titleKey).then(byTitle),
            ),
            byName = true, key = { it.book.id },
        ) { ShelfKey.letter(authorKey(it.book.author)) }
        AudiobookSort.RECENT -> ShelfSections.of(books.sortedWith(byLastPlayed), byName = false, key = { it.book.id }) { "" }
    }

    /** Android Auto's order: part-way books by last played, then unplayed ones by title, then finished ones by last played (Voice). */
    fun order(books: List<ShelfBook>): List<ShelfBook> {
        val groups = books.groupBy { it.listening }
        return groups[Listening.IN_PROGRESS].orEmpty().sortedWith(byLastPlayed) +
            groups[Listening.UNPLAYED].orEmpty().sortedWith(byTitle) +
            groups[Listening.FINISHED].orEmpty().sortedWith(byLastPlayed)
    }

    /** The books part-way through, last played first: the Continue row. */
    fun continueRow(books: List<ShelfBook>): List<ShelfBook> =
        books.filter { it.listening == Listening.IN_PROGRESS }.sortedWith(byLastPlayed).take(CONTINUE_MAX)

    fun shelfBook(book: Audiobook, chapters: List<AudiobookChapter>, state: AudiobookState?) =
        ShelfBook(book, listening(state), progress(book, chapters, state), state?.updatedAt)

    /** Where the book starts: its first chapter. Null without chapters. */
    fun startOf(book: Audiobook, chapters: List<AudiobookChapter>, now: Long): AudioBookmark? =
        chapters.minByOrNull { it.ordinal }?.let { at(book.id, it, now) }

    /** A bookmark-shaped start point at [chapter], for [PlayerHub.playAudiobook]; it isn't saved. */
    fun at(audiobookId: String, chapter: AudiobookChapter, now: Long) =
        AudioBookmark(0L, audiobookId, chapter.trackId, chapter.startMs, chapter.title, "", now)

    fun atEnd(book: Audiobook, chapters: List<AudiobookChapter>, state: AudiobookState): Boolean {
        val p = bookProgressAt(chapters, state.trackId, state.positionMs, book.durationMs)
        return p.durationMs > 0 && p.positionMs >= p.durationMs - END_SLACK_MS
    }
}

/**
 * The Audiobooks shelf for [collectionId] (the audiobooks placed in it), or every audiobook when it's null, and the
 * book pages (built with null: their route isn't scoped). Playing goes through [hub]; marking finished and editing
 * bookmarks write [listening]. Nothing here deletes a state row.
 */
class AudiobooksViewModel(
    collectionId: String?,
    private val media: MediaLibrary,
    shelves: Shelves,
    private val listening: AudiobookListening,
    private val hub: PlayerHub,
    private val now: () -> Long = System::currentTimeMillis,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    /** The downloads ledger: a downloaded book's progress. */
    private val downloads: Flow<List<DownloadRecord>> = flowOf(emptyList()),
    /** Removes a downloaded book's files by its group (`MediaDownloader.remove`, run in the app scope). */
    private val remover: (groupId: String) -> Unit = {},
    /** The shelf's remembered sort and view (Plan LV). */
    private val viewPrefs: LibraryViewPrefs = LibraryViewPrefs(),
) : ViewModel() {
    /** Groups' file counts from their manifests, read once (on [compute]). */
    private val fileCounts = ConcurrentHashMap<String, Int>()

    private val mediaRecords: Flow<List<DownloadRecord>> =
        downloads.map { all -> all.filter { it.groupId?.startsWith(MediaDownloads.PREFIX) == true } }.distinctUntilChanged()

    private val scope: Flow<Pair<String, List<Audiobook>>> =
        if (collectionId == null) {
            media.audiobooks.map { ALL_AUDIOBOOKS to it }
        } else {
            combine(media.audiobooks, shelves.collection(collectionId)) { books, row ->
                val ids = row?.audiobooks.orEmpty().mapTo(HashSet()) { it.id }
                row?.collection?.name.orEmpty() to books.filter { it.id in ids }
            }
        }.distinctUntilChanged()

    /** Null until loaded. */
    val shelf: StateFlow<AudiobookShelfUi?> = combine(
        scope, media.allChapters, listening.states, viewPrefs.sort(ShelfKind.AUDIOBOOKS),
    ) { (title, books), chapters, states, sortName ->
        val chaptersOf = chapters.groupBy { it.audiobookId }
        val stateOf = states.associateBy { it.audiobookId }
        val shelfBooks = books.map { AudiobookShelf.shelfBook(it, chaptersOf[it.id].orEmpty(), stateOf[it.id]) }
        val sort = sortNamed(sortName, AudiobookSort.TITLE)
        AudiobookShelfUi(title, AudiobookShelf.sections(shelfBooks, sort), AudiobookShelf.continueRow(shelfBooks), sort)
    }.flowOn(compute).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** List (the default) or the cover grid. */
    val view: StateFlow<ShelfView> = viewPrefs.view(ShelfKind.AUDIOBOOKS)

    fun setSort(sort: AudiobookSort) = viewPrefs.setSort(ShelfKind.AUDIOBOOKS, sort.name)

    fun toggleView() = viewPrefs.setView(ShelfKind.AUDIOBOOKS, view.value.toggled())

    /** Null while loading, or if there's no such book. */
    fun book(id: String): Flow<AudiobookPage?> = combine(page(id), hub.ui.map { inPlayer(it, id) to it.playing }.distinctUntilChanged()) { page, (loaded, playing) ->
        page?.copy(inPlayer = loaded, playing = loaded && playing)
    }

    private fun inPlayer(ui: PlayerUi, id: String) = (ui.now as? NowPlaying.Book)?.book?.id == id

    private fun page(id: String): Flow<AudiobookPage?> = combine(
        media.audiobooks.map { all -> all.firstOrNull { it.id == id } }.distinctUntilChanged(),
        media.chapters(id),
        listening.states.map { all -> all.firstOrNull { it.audiobookId == id } }.distinctUntilChanged(),
        listening.bookmarks(id),
        mediaRecords,
    ) { book, chapters, state, bookmarks, records ->
        book ?: return@combine null
        val ordered = chapters.sortedBy { it.ordinal }
        val l = AudiobookShelf.listening(state)
        val current = if (l == Listening.IN_PROGRESS && state != null) chapterAt(ordered, state.trackId, state.positionMs)?.first?.ordinal else null
        val group = MediaDownloads.groupOf(book)
        val download = group?.let { g ->
            val count = fileCounts[g] ?: MediaDownloads.manifestFileCount(g, records)?.also { fileCounts[g] = it }
            MediaDownloads.progress(g, records, count)
        }
        AudiobookPage(
            book, l, AudiobookShelf.progress(book, ordered, state), ordered, current,
            bookmarks.sortedWith(compareBy({ bookProgressAt(ordered, it.trackId, it.positionMs, book.durationMs).positionMs }, { it.id })),
            download = download?.status,
            filesDone = download?.done ?: 0,
            filesTotal = download?.total ?: 0,
            onRemoveDownload = group?.let { g -> { fileCounts.remove(g); remover(g) } },
        )
    }.flowOn(compute)

    /**
     * Play or resume; a finished book starts again from its first chapter. A MISSING book doesn't play. The book already
     * in the player pauses or carries on where it is, rather than reloading from its last saved position.
     */
    fun play(audiobookId: String) = act("play $audiobookId") {
        val book = playableBook(audiobookId) ?: return@act
        val state = listening.state(audiobookId)
        val ui = hub.ui.value
        if (inPlayer(ui, audiobookId) && (ui.playing || state?.finished != true)) {
            hub.playPause()
            return@act
        }
        if (state?.finished == true) {
            val start = AudiobookShelf.startOf(book, media.chapters(audiobookId).first(), now()) ?: return@act
            hub.playAudiobook(audiobookId, start)
        } else {
            hub.playAudiobook(audiobookId)
        }
    }

    /** From [chapter]'s start, in one call (no seek after the load). */
    fun playChapter(audiobookId: String, chapter: AudiobookChapter) = act("play chapter ${chapter.ordinal} of $audiobookId") {
        playableBook(audiobookId) ?: return@act
        hub.playAudiobook(audiobookId, AudiobookShelf.at(audiobookId, chapter, now()))
    }

    fun playBookmark(bookmark: AudioBookmark) = act("play bookmark ${bookmark.id}") {
        playableBook(bookmark.audiobookId) ?: return@act
        hub.playAudiobook(bookmark.audiobookId, bookmark)
    }

    /**
     * Marks the book finished or not, keeping its row (position and settings). Marked unfinished at its very end, it
     * goes back to its first chapter; anywhere else it keeps its place. A loaded copy in the player follows.
     */
    fun setFinished(audiobookId: String, finished: Boolean) = act("mark $audiobookId finished=$finished") {
        val book = media.audiobook(audiobookId) ?: return@act
        val chapters = media.chapters(audiobookId).first()
        val state = listening.state(audiobookId)
        val at = now()
        val updated = when {
            state == null -> {
                if (!finished) return@act
                val start = AudiobookShelf.startOf(book, chapters, at) ?: return@act
                AudiobookState(audiobookId, start.trackId, start.positionMs, 1f, false, 0, finished = true, updatedAt = at)
            }
            finished -> state.copy(finished = true, updatedAt = at)
            AudiobookShelf.atEnd(book, chapters, state) -> {
                val start = AudiobookShelf.startOf(book, chapters, at)
                state.copy(trackId = start?.trackId ?: state.trackId, positionMs = start?.positionMs ?: 0L, finished = false, updatedAt = at)
            }
            else -> state.copy(finished = false, updatedAt = at)
        }
        listening.saveState(updated)
        hub.setFinished(audiobookId, finished)
    }

    fun editNote(bookmark: AudioBookmark, note: String) = act("edit bookmark ${bookmark.id}") {
        listening.updateBookmark(bookmark.copy(note = note.trim()))
    }

    fun deleteBookmark(bookmark: AudioBookmark) = act("delete bookmark ${bookmark.id}") {
        listening.deleteBookmark(bookmark)
    }

    private suspend fun playableBook(id: String): Audiobook? = media.audiobook(id)?.takeIf { it.status == PackStatus.OK }

    private fun act(what: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("Granger", "Couldn't $what", e)
            }
        }
    }
}
