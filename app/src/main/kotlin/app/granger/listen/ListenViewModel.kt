package app.granger.listen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.granger.core.library.Shelves
import app.granger.core.player.NowPlaying
import app.granger.core.player.PlayerHub
import app.granger.core.player.PlayerMode
import app.granger.core.player.PlayerUi
import app.granger.core.player.SavedModes
import app.granger.library.KindRow
import app.granger.library.listenKinds
import app.granger.widget.WidgetModel
import app.granger.widget.resumeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/** What the Now playing card holds: music, an audiobook, or the voice reading a text. */
enum class CardKind { MUSIC, AUDIOBOOK, VOICE }

/**
 * Listen's Now playing card: the live item, else the last one played, which its play button resumes. [label] says
 * which ("Now playing", "Paused", "Last played"); [title] and [subtitle] are the widget's lines.
 */
data class NowPlayingCard(
    val label: String,
    val title: String,
    val subtitle: String,
    val coverPath: String?,
    val kind: CardKind,
    val live: Boolean,
    val playing: Boolean,
)

/**
 * Listen's home (Plan U2 Decision 4): the Now playing card, then the audio shelves (Music, Audiobooks), each only when
 * it holds something. With no shelf at all, [empty]: the screen offers the audio sources and the phone's own music.
 */
data class ListenUi(val nowPlaying: NowPlayingCard?, val shelves: List<KindRow>) {
    val empty: Boolean get() = shelves.isEmpty()
}

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
        !live -> if (kind == CardKind.VOICE) "Last read" else "Last played"
        !ui.playing -> "Paused"
        kind == CardKind.VOICE -> "Reading aloud"
        else -> "Now playing"
    }
    return NowPlayingCard(label, model.title, model.subtitle, model.coverPath, kind, live, playing = live && ui.playing)
}

/** Listen's home from the shelves (audio media types only) and the player's live and saved states. */
class ListenViewModel(
    shelves: Shelves,
    player: Flow<PlayerUi> = flowOf(PlayerUi.IDLE),
    saved: Flow<SavedModes> = flowOf(SavedModes.UNKNOWN),
) : ViewModel() {
    constructor(shelves: Shelves, hub: PlayerHub) : this(shelves, hub.ui, hub.saved)

    // The card ignores the position, so the player's ticks don't redraw the screen.
    private val card = combine(player, saved, ::nowPlayingCard).distinctUntilChanged()

    /** Null until the shelves first load. */
    val ui: StateFlow<ListenUi?> = combine(shelves.shelves, card) { list, now -> ListenUi(now, listenKinds(list)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
