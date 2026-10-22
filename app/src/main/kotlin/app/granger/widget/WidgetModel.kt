package app.granger.widget

import app.granger.core.player.NowPlaying
import app.granger.core.player.PlayerMode
import app.granger.core.player.PlayerUi
import app.granger.core.player.SavedModes
import app.granger.player.chapterTitle
import app.granger.player.fraction
import app.granger.player.voiceSubtitle

/** The widget's second button: next track for music, skip back for books (as the mini-player); none when nothing is loaded. */
enum class Secondary { NEXT, BACK, NONE }

/**
 * What the home-screen widget shows (spec §6, §8): the active player; when nothing is loaded, the last active
 * player's saved item, which play resumes; with nothing ever played, [empty] ("Nothing playing" / "Open Granger").
 * The title is the track, or the book's chapter (as in the notification and Auto); the subtitle the artist or book.
 */
data class WidgetModel(
    val title: String, val subtitle: String, val coverPath: String?,
    val playing: Boolean, val secondary: Secondary,
    val progress: Float?,           // null when nothing is loaded or saved
    val empty: Boolean,             // nothing ever played: "Nothing playing" / "Open Granger"
) {
    /** Equal but for [progress]: the widget re-renders only when this changes, so it never ticks. */
    fun sameButProgress(other: WidgetModel): Boolean = copy(progress = null) == other.copy(progress = null)

    companion object {
        val EMPTY = WidgetModel("", "", null, playing = false, secondary = Secondary.NONE, progress = null, empty = true)

        fun of(ui: PlayerUi, saved: SavedModes): WidgetModel = when (val now = ui.now) {
            is NowPlaying.Music -> WidgetModel(
                now.track.title, now.track.artists.ifBlank { now.track.albumArtists }, now.track.coverPath,
                ui.playing, Secondary.NEXT, fraction(ui.positionMs, ui.durationMs), empty = false,
            )
            is NowPlaying.Book -> WidgetModel(
                chapterTitle(now), now.book.title, now.book.coverPath,
                ui.playing, Secondary.BACK, fraction(ui.positionMs, ui.durationMs), empty = false,
            )
            is NowPlaying.Voice -> WidgetModel(
                now.title, voiceSubtitle(now), null,
                ui.playing, Secondary.BACK, progress = null, empty = false,
            )
            NowPlaying.Idle -> ofSaved(saved)
        }

        private fun ofSaved(saved: SavedModes): WidgetModel = savedVoice(saved) ?: when (saved.resumeMode()) {
            PlayerMode.MUSIC -> {
                val m = saved.music!!
                val t = m.track
                WidgetModel(
                    t.title, t.artists.ifBlank { t.albumArtists }, t.coverPath, playing = false, Secondary.NONE,
                    fraction(m.positionMs, t.durationMs), empty = false,
                )
            }
            PlayerMode.AUDIOBOOK -> {
                val b = saved.book!!
                WidgetModel(
                    b.chapter.title.ifBlank { "Chapter ${b.chapter.ordinal + 1}" }, b.book.title, b.book.coverPath,
                    playing = false, Secondary.NONE, fraction(b.inChapterMs, b.chapter.durationMs), empty = false,
                )
            }
            null -> EMPTY
        }

        /** The voice was the last thing playing (Plan V2): its title, paused, so the widget's play resumes it. */
        private fun savedVoice(saved: SavedModes): WidgetModel? {
            val v = saved.voice?.takeIf { saved.lastWasVoice } ?: return null
            val subtitle = listOfNotNull("Reading", v.section?.takeIf { it.isNotBlank() }).joinToString(" · ")
            return WidgetModel(v.title, subtitle, null, playing = false, Secondary.NONE, progress = null, empty = false)
        }
    }
}

/**
 * The player play resumes when nothing is loaded: the last active one, else whichever has a saved state (books
 * first, as the player restores them). Null when there's nothing to resume (or the saved state isn't read yet).
 */
fun SavedModes.resumeMode(): PlayerMode? {
    val musicOk = music?.let { it.index in it.queue.indices } == true
    val bookOk = book != null
    return when {
        lastMode == PlayerMode.MUSIC && musicOk -> PlayerMode.MUSIC
        lastMode == PlayerMode.AUDIOBOOK && bookOk -> PlayerMode.AUDIOBOOK
        bookOk -> PlayerMode.AUDIOBOOK
        musicOk -> PlayerMode.MUSIC
        else -> null
    }
}
