package app.granger.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.granger.R
import app.granger.core.player.NowPlaying
import app.granger.core.player.PlayerUi

/** The mini-player's height, the same for music and audiobooks (spec §8). */
val MiniPlayerHeight = 64.dp

object MiniPlayerTags {
    const val ROOT = "mini_player"
    const val COVER = "mini_player_cover"
    const val TITLE = "mini_player_title"
    const val SUBTITLE = "mini_player_subtitle"
    const val PLAY_PAUSE = "mini_player_play_pause"
    const val PROGRESS = "mini_player_progress"
    const val SECONDARY = "mini_player_secondary"
    const val MUSIC = "mini_player_music"
}

/** Where the shell draws the mini-player. */
enum class MiniPlayerPlacement {
    HIDDEN,

    /** In the shell's slot at the top of the content column, pushing the screen down. */
    PUSH,
}

/**
 * Nothing loaded, or the full-screen player showing: hidden. In a reader it follows the reader's chrome (the rail's
 * visibility); whenever it shows it pushes the screen (the reader's page too) down.
 */
fun miniPlayerPlacement(loaded: Boolean, onPlayer: Boolean, reading: Boolean, railVisible: Boolean): MiniPlayerPlacement = when {
    !loaded || onPlayer -> MiniPlayerPlacement.HIDDEN
    reading -> if (railVisible) MiniPlayerPlacement.PUSH else MiniPlayerPlacement.HIDDEN
    else -> MiniPlayerPlacement.PUSH
}

/**
 * One mini-player for both players: cover, title and subtitle, play/pause, one secondary button and a progress line.
 * The secondary button is next track for music and skip back [skipBackS] seconds for audiobooks. Tapping the rest
 * calls [onOpen]. Nothing is drawn while [ui] is idle.
 */
@Composable
fun MiniPlayer(
    ui: PlayerUi,
    onPlayPause: () -> Unit,
    onSecondary: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    skipBackS: Int = PlayerViewModel.DEFAULT_SKIP_S,
) {
    val now = ui.now
    if (now == NowPlaying.Idle) return
    val info = nowInfo(now) ?: return
    val book = now is NowPlaying.Book
    val voice = now is NowPlaying.Voice
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth().testTag(MiniPlayerTags.ROOT),
    ) {
        Column(Modifier.fillMaxWidth().height(MiniPlayerHeight)) {
            Row(
                Modifier.fillMaxWidth().weight(1f).clickable(onClickLabel = if (voice) "Open the voice" else "Open player", onClick = onOpen).padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayerCover(info.coverPath, info.title, book, size = 44.dp, modifier = Modifier.testTag(MiniPlayerTags.COVER), voice = voice)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        info.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.testTag(MiniPlayerTags.TITLE),
                    )
                    Text(
                        info.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag(MiniPlayerTags.SUBTITLE),
                    )
                    // Music under the voice (Plan V2): a thin third line names it.
                    (now as? NowPlaying.Voice)?.music?.let { under ->
                        Text(
                            "♪ ${under.track.title} · ${under.track.artists.ifBlank { under.track.albumArtists }}",
                            maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag(MiniPlayerTags.MUSIC),
                        )
                    }
                }
                IconButton(onClick = onPlayPause, modifier = Modifier.testTag(MiniPlayerTags.PLAY_PAUSE)) {
                    PlayPauseIcon(ui.playing)
                }
                IconButton(onClick = onSecondary, modifier = Modifier.testTag(MiniPlayerTags.SECONDARY)) {
                    if (voice) {
                        Icon(painterResource(R.drawable.ic_replay), contentDescription = "Back a sentence")
                    } else if (book) {
                        Icon(painterResource(R.drawable.ic_replay), contentDescription = "Back $skipBackS seconds")
                    } else {
                        Icon(painterResource(R.drawable.ic_skip_next), contentDescription = "Next track")
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(2.dp).testTag(MiniPlayerTags.PROGRESS)) {
                LinearProgressIndicator(
                    progress = { fraction(ui.positionMs, ui.durationMs) },
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                    drawStopIndicator = {},
                    gapSize = 0.dp,
                )
            }
        }
    }
}
