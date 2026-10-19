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

/**
 * The one way the app talks to the player (spec §8). Calls are fire-and-forget and run in order; [ui] and [queue]
 * reflect them once applied.
 */
interface PlayerHub {
    val ui: StateFlow<PlayerUi>

    /** The music queue in play order (empty while an audiobook is the active player). */
    val queue: StateFlow<List<Track>>

    fun playTracks(trackIds: List<String>, startIndex: Int, shuffle: Boolean, source: PlaySource)
    fun playAudiobook(id: String, at: AudioBookmark? = null)
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
