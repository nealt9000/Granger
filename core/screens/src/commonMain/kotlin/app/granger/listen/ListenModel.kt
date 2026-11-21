package app.granger.listen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.granger.UiText
import app.granger.core.library.Shelves
import app.granger.library.KindRow
import app.granger.library.listenKinds
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
 * Listen's Now playing card: the live item, else the last one played, which its play button resumes. [labelKey] names
 * which ("Now playing", "Paused", "Last played"; Android: `label`); [title] and [subtitle] are the widget's lines.
 */
data class NowPlayingCard(
    val labelKey: String,
    val title: UiText,
    val subtitle: UiText,
    val coverPath: String?,
    val kind: CardKind,
    val live: Boolean,
    val playing: Boolean,
) {
    companion object {
        // The card's labels, by key.
        const val NOW_PLAYING = "listen_now_playing"
        const val PAUSED = "listen_paused"
        const val READING_ALOUD = "listen_reading_aloud"
        const val LAST_PLAYED = "listen_last_played"
        const val LAST_READ = "listen_last_read"
    }
}

/**
 * Listen's home (Plan U2 Decision 4): the Now playing card, then the audio shelves (Music, Audiobooks), each only when
 * it holds something. With no shelf at all, [empty]: the screen offers the audio sources and the phone's own music.
 */
data class ListenUi(val nowPlaying: NowPlayingCard?, val shelves: List<KindRow>) {
    val empty: Boolean get() = shelves.isEmpty()
}

/**
 * Listen's home from the shelves (audio media types only) and the Now playing [card]: what the player has loaded, else
 * what it would resume, made by each phone's player (Android: `nowPlayingCard` over the PlayerHub). Shared since Plan
 * I5 Task 2; the player itself joins the core in Task 3.
 */
open class ListenModel(
    shelves: Shelves,
    card: Flow<NowPlayingCard?> = flowOf(null),
) : ViewModel() {
    // The card ignores the position, so the player's ticks don't redraw the screen.
    private val distinctCard = card.distinctUntilChanged()

    /** Null until the shelves first load. */
    val ui: StateFlow<ListenUi?> = combine(shelves.shelves, distinctCard) { list, now -> ListenUi(now, listenKinds(list)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
