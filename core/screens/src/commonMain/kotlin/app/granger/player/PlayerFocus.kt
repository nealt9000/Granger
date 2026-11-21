package app.granger.player

import app.granger.core.player.NowPlaying
import app.granger.core.player.PlayerHub
import app.granger.core.player.PlayerUi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Which side a one-item view (the mini-player, Listen's card, the widget, the full player as it opens) shows while the
 * voice has music under it (Plan V2): the one playing; with both playing, or both paused, the side the full player last
 * showed. One for the app, so every view agrees.
 */
class PlayerFocus {
    private val _prefersMusic = MutableStateFlow(false)

    /** The full player last showed its media tab while the voice had music under it. */
    val prefersMusic: StateFlow<Boolean> = _prefersMusic

    /** The full player showed [tab] with [ui] loaded; only remembered while there are two sides to choose between. */
    fun shown(tab: ListenTab, ui: PlayerUi) {
        if (ui.musicUnder() != null) _prefersMusic.value = tab == ListenTab.MEDIA
    }

    /**
     * Play/pause on a one-item view: the side it shows, which stays shown (pausing the music under a paused voice
     * doesn't flip the view back to the voice).
     */
    fun playPause(hub: PlayerHub) {
        val ui = hub.ui.value
        if (ui.musicUnder() == null) return hub.playPause()
        val music = ui.showsMusicUnder(_prefersMusic.value)
        _prefersMusic.value = music
        if (music) hub.playPauseMusic() else hub.playPause()
    }

    /** True when a one-item view's buttons drive the music under the voice now. */
    fun drivesMusic(ui: PlayerUi): Boolean = ui.showsMusicUnder(_prefersMusic.value)
}

/** The music under the voice as a player of its own, as the Music tab shows it; null without music under the voice. */
fun PlayerUi.musicUnder(): PlayerUi? {
    val voice = now as? NowPlaying.Voice ?: return null
    val music = voice.music ?: return null
    return PlayerUi(music, voice.musicPlaying, voice.musicPositionMs, voice.musicDurationMs, sleepRemainingMs)
}

/** A one-item view shows the music under the voice ([PlayerFocus]), not the voice. False without music under it. */
fun PlayerUi.showsMusicUnder(prefersMusic: Boolean): Boolean {
    val voice = now as? NowPlaying.Voice ?: return false
    if (voice.music == null) return false
    val reading = playing && !voice.ended
    return if (reading != voice.musicPlaying) voice.musicPlaying else prefersMusic
}

/** What a one-item view shows: the music under the voice when [showsMusicUnder], else the player as it is. */
fun PlayerUi.oneSide(prefersMusic: Boolean): PlayerUi =
    if (showsMusicUnder(prefersMusic)) musicUnder()!! else this
