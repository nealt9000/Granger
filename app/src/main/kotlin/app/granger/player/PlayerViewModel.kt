package app.granger.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.granger.core.library.AudioBookmark
import app.granger.core.library.AudiobookChapter
import app.granger.core.library.Track
import app.granger.core.player.NowPlaying
import app.granger.core.player.PlayerHub
import app.granger.core.player.PlayerMode
import app.granger.core.player.PlayerUi
import app.granger.core.player.SavedBook
import app.granger.core.player.SavedModes
import app.granger.core.player.SavedMusic
import app.granger.core.player.SleepSetting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Where the listener is in the whole book: the earlier chapters' durations plus the position in this one. */
data class BookProgress(val positionMs: Long, val durationMs: Long)

/**
 * The book-wide position for [chapter] at [inChapterMs]. [chapters] are the book's chapters (any order); with none
 * known yet, the chapter itself is all there is. [bookDurationMs] is the fallback total when chapters carry no
 * durations.
 */
fun bookProgress(chapters: List<AudiobookChapter>, chapter: AudiobookChapter, inChapterMs: Long, bookDurationMs: Long): BookProgress {
    val known = chapters.ifEmpty { listOf(chapter) }
    val before = known.filter { it.ordinal < chapter.ordinal }.sumOf { it.durationMs }
    val total = known.sumOf { it.durationMs }.takeIf { it > 0 } ?: bookDurationMs
    val position = before + inChapterMs.coerceAtLeast(0L)
    return BookProgress(if (total > 0) position.coerceAtMost(total) else position, total)
}

/**
 * The chapter a saved position ([trackId], [positionMs] into that file) falls in, and the ms into it, by the
 * player's own rule (core:player's `ChapterMap.chapterAt`/`inChapter`, internal there): files in the order the
 * chapters first name them; the last chapter starting at or before the position, counting every chapter of earlier
 * files; a file the book doesn't hold counts as its first file at 0. Null with no chapters.
 */
fun chapterAt(chapters: List<AudiobookChapter>, trackId: String, positionMs: Long): Pair<AudiobookChapter, Long>? {
    val ordered = chapters.sortedBy { it.ordinal }.ifEmpty { return null }
    val files = ordered.map { it.trackId }.distinct()
    val file = files.indexOf(trackId)
    var found = ordered.first()
    for (c in ordered) {
        val f = files.indexOf(c.trackId)
        if (f < file || (f == file && c.startMs <= positionMs)) found = c else if (f > file) break
    }
    val inChapter = if (found.trackId == trackId) (positionMs - found.startMs).coerceAtLeast(0L) else 0L
    return found to inChapter
}

/** Book-wide progress for a saved position, through [bookProgress] so the shelf and the player agree. A [finished] book is complete. */
fun bookProgressAt(
    chapters: List<AudiobookChapter>,
    trackId: String,
    positionMs: Long,
    bookDurationMs: Long,
    finished: Boolean = false,
): BookProgress {
    val at = chapterAt(chapters, trackId, positionMs)
    val progress = if (at == null) BookProgress(0L, bookDurationMs) else bookProgress(chapters, at.first, at.second, bookDurationMs)
    return if (finished) progress.copy(positionMs = progress.durationMs) else progress
}

/** The mode [now] belongs to; null when idle. */
fun modeOf(now: NowPlaying): PlayerMode? = when (now) {
    is NowPlaying.Music -> PlayerMode.MUSIC
    is NowPlaying.Book -> PlayerMode.AUDIOBOOK
    NowPlaying.Idle -> null
}

/** The Player's tab: the one picked, else the active mode, else the mode last used, else Music. */
fun playerTab(picked: PlayerMode?, now: NowPlaying, lastMode: PlayerMode?): PlayerMode =
    picked ?: modeOf(now) ?: lastMode ?: PlayerMode.MUSIC

/** The saved queue as the music player shows it: paused at its saved track and position. */
fun SavedMusic.toUi(): PlayerUi = PlayerUi(
    NowPlaying.Music(track, index, queue.size, shuffle, repeat),
    playing = false, positionMs = positionMs, durationMs = track.durationMs, sleepRemainingMs = null,
)

/** The last audiobook as the audiobook player shows it: paused at its saved chapter and position. */
fun SavedBook.toUi(): PlayerUi = PlayerUi(
    NowPlaying.Book(book, chapter, chapters.size, speed, skipSilence, volumeBoostMb),
    playing = false, positionMs = inChapterMs, durationMs = chapter.durationMs, sleepRemainingMs = null,
)

/** The saved book's progress, as the shelf shows it (a finished book is complete). */
fun SavedBook.progress(): BookProgress = bookProgress(chapters, chapter, inChapterMs, book.durationMs)
    .let { if (finished) it.copy(positionMs = it.durationMs) else it }

/**
 * The mini-player and both full-screen players. Everything goes through [hub]; [chapters] and [bookmarks] read the
 * library for the loaded audiobook, and [skipBackSeconds]/[skipForwardSeconds] are the audiobook skip settings (for
 * the buttons' labels).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModel(
    private val hub: PlayerHub,
    private val chapters: (audiobookId: String) -> Flow<List<AudiobookChapter>> = { flowOf(emptyList()) },
    private val bookmarks: (audiobookId: String) -> Flow<List<AudioBookmark>> = { flowOf(emptyList()) },
    private val deleteBookmark: suspend (AudioBookmark) -> Unit = {},
    private val skipBackSeconds: () -> Int = { DEFAULT_SKIP_S },
    private val skipForwardSeconds: () -> Int = { DEFAULT_SKIP_S },
) : ViewModel() {
    val ui: StateFlow<PlayerUi> = hub.ui
    val queue: StateFlow<List<Track>> = hub.queue

    /** Both modes' saved states: the Player's inactive tab shows one. */
    val saved: StateFlow<SavedModes> = hub.saved

    private val _pickedTab = MutableStateFlow<PlayerMode?>(null)

    /** The tab the user picked in the Player, or null for the default ([playerTab]). Picking one plays nothing. */
    val pickedTab: StateFlow<PlayerMode?> = _pickedTab

    fun selectTab(mode: PlayerMode) { _pickedTab.value = mode }

    /** The Player opens on the active mode (or the last one used). */
    fun resetTab() { _pickedTab.value = null }

    /** The tab shown now. */
    fun tab(): PlayerMode = playerTab(_pickedTab.value, hub.ui.value.now, hub.saved.value.lastMode)

    /**
     * A control pressed on the inactive tab: [mode] becomes the active one, loaded from its saved state and playing
     * (the active mode is saved first), then [then] does what was pressed (a seek, next, a chapter…). The hub runs
     * them in that order. Only direct controls belong in [then]: the switch hasn't happened yet when it runs here.
     */
    fun activate(mode: PlayerMode, then: PlayerViewModel.() -> Unit = {}) {
        hub.activate(mode)
        then()
    }

    private val bookId: Flow<String?> = hub.ui.map { (it.now as? NowPlaying.Book)?.book?.id }.distinctUntilChanged()

    /** The loaded audiobook's chapters in ordinal order; empty for music. */
    val bookChapters: StateFlow<List<AudiobookChapter>> = bookId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else chapters(id).map { list -> list.sortedBy { it.ordinal } } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The loaded audiobook's bookmarks; empty for music. */
    val bookBookmarks: StateFlow<List<AudioBookmark>> = bookId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else bookmarks(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Book-wide progress while an audiobook is loaded, else null. */
    val progress: StateFlow<BookProgress?> = combine(hub.ui, bookChapters) { ui, list ->
        (ui.now as? NowPlaying.Book)?.let { bookProgress(list, it.chapter, ui.positionMs, it.book.durationMs) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The audiobook skip-back interval, read fresh each time (Settings can change it). */
    fun skipBackS(): Int = skipBackSeconds().takeIf { it > 0 } ?: DEFAULT_SKIP_S
    fun skipForwardS(): Int = skipForwardSeconds().takeIf { it > 0 } ?: DEFAULT_SKIP_S

    fun playPause() = hub.playPause()

    /** The mini-player's one secondary button: next track for music, skip back for audiobooks. */
    fun secondary() = when (hub.ui.value.now) {
        is NowPlaying.Music -> hub.next()
        is NowPlaying.Book -> hub.skipBack()
        NowPlaying.Idle -> Unit
    }

    fun next() = hub.next()
    fun previous() = hub.previous()
    fun skipBack() = hub.skipBack()
    fun skipForward() = hub.skipForward()
    fun seekTo(positionMs: Long) = hub.seekTo(positionMs)
    fun seekToChapter(ordinal: Int) = hub.seekToChapter(ordinal)

    /** The chapter before or after the current one; nothing past either end. */
    fun previousChapter() = stepChapter(-1)
    fun nextChapter() = stepChapter(+1)

    private fun stepChapter(by: Int) {
        val book = hub.ui.value.now as? NowPlaying.Book ?: return
        val list = bookChapters.value
        val i = list.indexOfFirst { it.ordinal == book.chapter.ordinal }
        if (i < 0) {
            // Chapters not read yet: ordinals are 0-based and the engine clamps out-of-range ones.
            hub.seekToChapter(book.chapter.ordinal + by)
            return
        }
        list.getOrNull(i + by)?.let { hub.seekToChapter(it.ordinal) }
    }

    fun toggleShuffle() {
        val music = hub.ui.value.now as? NowPlaying.Music ?: return
        hub.setShuffle(!music.shuffle)
    }

    fun cycleRepeat() = hub.cycleRepeat()

    /** Play-order positions, as [queue] lists them. */
    fun moveInQueue(from: Int, to: Int) { if (from != to) hub.moveInQueue(from, to) }
    fun removeFromQueue(i: Int) = hub.removeFromQueue(i)

    fun setSpeed(speed: Float) = hub.setSpeed(speed)
    fun setSkipSilence(on: Boolean) = hub.setSkipSilence(on)
    fun setVolumeBoost(on: Boolean) = hub.setVolumeBoost(if (on) VOLUME_BOOST_MB else 0)
    fun startSleepTimer(setting: SleepSetting) = hub.startSleepTimer(setting)
    fun cancelSleepTimer() = hub.cancelSleepTimer()

    /** A bookmark here, titled with the chapter and the time in it. */
    fun addBookmark() {
        val ui = hub.ui.value
        val book = ui.now as? NowPlaying.Book ?: return
        val title = "${book.chapter.title.ifBlank { "Chapter ${book.chapter.ordinal + 1}" }} · ${formatTime(ui.positionMs)}"
        viewModelScope.launch { hub.addBookmark(title) }
    }

    fun playBookmark(bookmark: AudioBookmark) = hub.playAudiobook(bookmark.audiobookId, bookmark)

    fun removeBookmark(bookmark: AudioBookmark) {
        viewModelScope.launch {
            try {
                deleteBookmark(bookmark)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("Granger", "Couldn't delete bookmark ${bookmark.id}", e)
            }
        }
    }

    companion object {
        const val DEFAULT_SKIP_S = 30

        /** The volume boost toggle's gain: +6 dB. */
        const val VOLUME_BOOST_MB = 600

        /** Speeds the speed sheet offers: 0.5× to 3.0× in 0.1 steps. */
        val SPEEDS: List<Float> = (5..30).map { it / 10f }

        /** The sleep timer's choices in minutes (plus end of chapter). */
        val SLEEP_MINUTES = listOf(5, 10, 15, 30, 45, 60)
    }
}

/** "m:ss", or "h:mm:ss" from an hour up. */
fun formatTime(ms: Long): String {
    val s = (ms.coerceAtLeast(0L) / 1000)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
