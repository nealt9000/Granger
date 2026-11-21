package app.granger.listen

import androidx.annotation.StringRes
import app.granger.ScreenTextIds
import app.granger.UiText
import app.granger.core.library.Shelves
import app.granger.core.player.NowPlaying
import app.granger.core.player.PlayerHub
import app.granger.core.player.PlayerMode
import app.granger.core.player.PlayerUi
import app.granger.core.player.SavedModes
import app.granger.player.PlayerFocus
import app.granger.player.oneSide
import app.granger.widget.WidgetModel
import app.granger.widget.resumeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

// Listen's home is shared (core:screens ListenModel, Plan I5 Task 2); the card is made here from Android's player.

/** The card's label as an Android resource. */
@get:StringRes
val NowPlayingCard.label: Int get() = ScreenTextIds.string(labelKey)

/** The card for what [ui] has loaded, else what [saved] would resume; null with nothing ever played. */
fun nowPlayingCard(ui: PlayerUi, saved: SavedModes): NowPlayingCard? {
    val model = WidgetModel.of(ui, saved)
    if (model.empty) return null
    val now = ui.now
    val kind = when (now) {
        is NowPlaying.Music -> CardKind.MUSIC
        is NowPlaying.Book -> CardKind.AUDIOBOOK
        is NowPlaying.Voice -> CardKind.VOICE
        NowPlaying.Idle -> when {
            saved.lastWasVoice && saved.voice != null -> CardKind.VOICE
            saved.resumeMode() == PlayerMode.AUDIOBOOK -> CardKind.AUDIOBOOK
            else -> CardKind.MUSIC
        }
    }
    val live = now != NowPlaying.Idle
    val label = when {
        !live -> if (kind == CardKind.VOICE) NowPlayingCard.LAST_READ else NowPlayingCard.LAST_PLAYED
        !ui.playing -> NowPlayingCard.PAUSED
        kind == CardKind.VOICE -> NowPlayingCard.READING_ALOUD
        else -> NowPlayingCard.NOW_PLAYING
    }
    return NowPlayingCard(label, model.title, model.subtitle, model.coverPath, kind, live, playing = live && ui.playing)
}

/** Listen's home on Android: the shared [ListenModel], its card from the player's live and saved states. */
class ListenViewModel(
    shelves: Shelves,
    player: Flow<PlayerUi> = flowOf(PlayerUi.IDLE),
    saved: Flow<SavedModes> = flowOf(SavedModes.UNKNOWN),
) : ListenModel(shelves, combine(player, saved, ::nowPlayingCard)) {
    constructor(shelves: Shelves, hub: PlayerHub, focus: PlayerFocus = PlayerFocus()) :
        this(shelves, combine(hub.ui, focus.prefersMusic, PlayerUi::oneSide), hub.saved)
}
