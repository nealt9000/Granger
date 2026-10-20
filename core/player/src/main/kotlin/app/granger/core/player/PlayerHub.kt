package app.granger.core.player

import app.granger.core.library.AudioBookmark
import app.granger.core.library.Audiobook
import app.granger.core.library.AudiobookChapter
import app.granger.core.library.Track
import kotlinx.coroutines.flow.StateFlow

/** What a play started from; written to recent plays. [key] follows `RecentPlay.key` ("album:<albumKey>", "songs", …). */
data class PlaySource(val key: String, val title: String)

sealed interface NowPlaying {
    data object Idle : NowPlaying

    /** [index] is the track's position in the queue as [PlayerHub.queue] lists it (play order). [repeat]: 0 off, 1 all, 2 one. */
    data class Music(val track: Track, val index: Int, val queueSize: Int, val shuffle: Boolean, val repeat: Int) : NowPlaying

    data class Book(val book: Audiobook, val chapter: AudiobookChapter, val chapterCount: Int, val speed: Float,
                    val skipSilence: Boolean, val volumeBoostMb: Int) : NowPlaying
}

/**
 * The player as the UI shows it. For music [positionMs]/[durationMs] are within the track; for audiobooks they are
 * within the current chapter (the book-wide progress is the earlier chapters' durations plus [positionMs]).
 * [playing] is "wants to play" (true while buffering), which is what the play/pause button shows.
 */
data class PlayerUi(val now: NowPlaying, val playing: Boolean, val positionMs: Long, val durationMs: Long, val sleepRemainingMs: Long?) {
    companion object {
        val IDLE = PlayerUi(NowPlaying.Idle, playing = false, positionMs = 0L, durationMs = 0L, sleepRemainingMs = null)
    }
}

/** The player's two modes (spec §8): one ExoPlayer, one mode active at a time, each with its own saved state. */
enum class PlayerMode { MUSIC, AUDIOBOOK }

/**
 * The saved music queue as it would resume: [queue] in play order (as [PlayerHub.queue] lists it), [index] the
 * current track's position in it, [positionMs] within that track. [repeat]: 0 off, 1 all, 2 one.
 */
data class SavedMusic(val queue: List<Track>, val index: Int, val positionMs: Long, val shuffle: Boolean, val repeat: Int) {
    val track: Track get() = queue[index]
}

/**
 * The last audiobook as it would resume: [chapter] and [inChapterMs] are where its saved state points ([state] is
 * null for a book that was loaded but never saved: its first chapter, at 0). [chapters] are in ordinal order.
 */
data class SavedBook(
    val book: Audiobook,
    val chapters: List<AudiobookChapter>,
    val chapter: AudiobookChapter,
    val inChapterMs: Long,
    val speed: Float,
    val skipSilence: Boolean,
    val volumeBoostMb: Int,
    val finished: Boolean,
)

/**
 * Each mode's saved state, read from the db without loading anything into the player; it follows every save.
 * [music]/[book] are null for a mode never used. [lastMode] is the mode last loaded (what a restore brings back).
 * [loaded] is false until the db has been read once.
 */
data class SavedModes(val music: SavedMusic?, val book: SavedBook?, val lastMode: PlayerMode?, val loaded: Boolean = true) {
    companion object {
        val UNKNOWN = SavedModes(null, null, null, loaded = false)
    }
}

/**
 * The one way the app talks to the player (spec §8). Calls are fire-and-forget and run in order; [ui] and [queue]
 * reflect them once applied.
 */
interface PlayerHub {
    val ui: StateFlow<PlayerUi>

    /** The music queue in play order (empty while an audiobook is the active player). */
    val queue: StateFlow<List<Track>>

    /** Both modes' saved states, for showing the inactive one without loading it. The player stays their only writer. */
    val saved: StateFlow<SavedModes>

    /**
     * Makes [mode] the active one and plays it. Already active: plays. Otherwise the active mode is saved first (as
     * any switch does), then [mode] is loaded from its saved state (music: the saved queue, track, position, shuffle
     * and repeat; audiobook: the last book at its saved position) and played. Nothing happens for a mode never used.
     */
    fun activate(mode: PlayerMode)

    fun playTracks(trackIds: List<String>, startIndex: Int, shuffle: Boolean, source: PlaySource)
    /**
     * Plays the audiobook from [at], else from its saved position. A book whose state is finished starts from its
     * first chapter instead; playing a book un-finishes it (its next save writes `finished = false`).
     */
    fun playAudiobook(id: String, at: AudioBookmark? = null)

    /**
     * The book page has just written [finished] into [audiobookId]'s state. If that book is loaded, the player adopts
     * it (so its next save doesn't undo it) and saves; unfinished while at the end, it goes back to the first chapter.
     * Otherwise nothing happens: the row is the library's.
     */
    fun setFinished(audiobookId: String, finished: Boolean)

    /**
     * Their download is being removed: if the loaded book is [audiobookId], or the music queue holds any of
     * [trackIds], the player saves its place and empties, rather than sitting there unplayable.
     */
    fun unload(audiobookId: String, trackIds: Set<String>)
    fun playPause(); fun next(); fun previous()

    /** Audiobooks: ± the skip interval; music: no-op. */
    fun skipBack(); fun skipForward()

    /** Music: within the track. Audiobooks: within the current chapter (as [PlayerUi.positionMs]). */
    fun seekTo(positionMs: Long); fun seekToChapter(ordinal: Int)

    /** [moveInQueue] and [removeFromQueue] take positions in [queue] (play order). */
    fun setShuffle(on: Boolean); fun cycleRepeat(); fun moveInQueue(from: Int, to: Int); fun removeFromQueue(i: Int)
    fun playNext(trackIds: List<String>); fun addToQueue(trackIds: List<String>)
    fun setSpeed(speed: Float); fun setSkipSilence(on: Boolean); fun setVolumeBoost(mb: Int)
    fun startSleepTimer(setting: SleepSetting); fun cancelSleepTimer()

    /** A bookmark at the current audiobook position; its id, or -1 when no audiobook is loaded. */
    suspend fun addBookmark(title: String): Long
}

/** SharedPreferences (file [PREFS]) shared by the player and the media settings screen. */
object PlayerSettingsKeys {
    const val PREFS = "player"

    /** A [ReplayGainMode] name; default AUTO. */
    const val REPLAYGAIN_MODE = "replaygain_mode"

    /** Seconds; default 30 each. */
    const val SKIP_BACK_S = "skip_back_s"
    const val SKIP_FORWARD_S = "skip_forward_s"

    /** Resume when a headset connects; default false (as in Auxio). */
    const val HEADSET_AUTOPLAY = "headset_autoplay"

    // Kept by the engine, not settings: which player to restore and when it was paused (survive process death).
    internal const val LAST_MODE = "last_mode"
    internal const val LAST_AUDIOBOOK = "last_audiobook"
    internal const val PAUSED_AT = "paused_at"
}
