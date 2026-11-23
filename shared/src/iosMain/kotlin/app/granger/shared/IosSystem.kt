package app.granger.shared

import app.granger.UiText
import app.granger.core.library.MediaIndex
import app.granger.core.player.NowPlaying
import app.granger.core.player.PlayerHub
import app.granger.core.player.PlayerUi
import app.granger.core.player.SavedModes
import app.granger.media.MediaBrowse
import app.granger.media.PlayResumer
import app.granger.media.SongPlayer
import app.granger.widget.Secondary
import app.granger.widget.WidgetModel
import app.granger.widget.resumeMode

/**
 * The system's pieces on the iPhone (Plan I6 Task 8): CarPlay's browse tree and the player widget's model and buttons,
 * all shared rules (Android Auto's [MediaBrowse], the Glance widget's [WidgetModel] and its two actions).
 */
class IosSystem(private val library: IosLibrary, private val voice: IosVoice, private val music: IosMusic) {
    /** The library's media index, shared with Best matches' media cards. */
    private val index: MediaIndex get() = library.mediaIndex

    /**
     * What CarPlay browses (call 6): Recent, Music (Albums, Artists, Playlists) and Audiobooks, Android Auto's tree.
     * [text] gives a shared string key's words in the phone's language. Lists are built off the main thread; [MediaBrowse.play]
     * goes through the hub, as Auto's does.
     */
    fun browse(text: (String) -> String): MediaBrowse = MediaBrowse(
        library.media, library.playlists, library.database.playbackDao(), index,
        PlayResumer(library.media, library.playlists, voice.hub), SongPlayer(library.media, voice.hub), voice.hub,
        { res: UiText.Res -> text(res.key) },
    )

    /** The widget's model now: the active player, else the saved one play resumes, else "Nothing playing". */
    fun widgetModel(ui: PlayerUi, saved: SavedModes): WidgetModel = WidgetModel.of(ui, saved)

    /** Whether the widget must be redrawn for [next] after [last] (Android's: not for progress alone). */
    fun widgetChanged(last: WidgetModel?, next: WidgetModel): Boolean = last == null || !last.sameButProgress(next)

    /**
     * The widget's play/pause (Android's playFromWidget): toggles the loaded player; with nothing loaded, activates
     * the player the saved state picks, which loads and plays it. A voice that was last isn't reopened from here.
     */
    fun widgetPlayPause() {
        val hub = voice.hub
        if (hub.ui.value.now != NowPlaying.Idle) return music.focus.playPause(hub)
        val saved = hub.saved.value.takeIf { it.loaded } ?: return
        saved.resumeMode()?.let(hub::activate)
    }

    /** The widget's second button (Android's secondaryFromWidget): next song for music, back the skip interval for a book. */
    fun widgetSecondary() {
        val hub: PlayerHub = voice.hub
        if (music.focus.drivesMusic(hub.ui.value)) return hub.nextMusic()
        when (hub.ui.value.now) {
            is NowPlaying.Music -> hub.next()
            is NowPlaying.Book -> hub.skipBack()
            is NowPlaying.Voice -> hub.previousSentence()
            NowPlaying.Idle -> Unit
        }
    }

    /** The second button's kind as a word Swift can switch on: "next", "back" or "none". */
    fun secondaryName(model: WidgetModel): String = when (model.secondary) {
        Secondary.NEXT -> "next"
        Secondary.BACK -> "back"
        Secondary.NONE -> "none"
    }
}

