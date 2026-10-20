package app.granger.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.granger.R
import app.granger.core.library.Albums
import app.granger.core.library.Track
import app.granger.core.player.NowPlaying
import app.granger.core.player.PlayerMode
import app.granger.core.player.PlayerUi
import app.granger.theme.GrangerColors

/**
 * The music player (Auxio-style): cover, title/artist/album, seek bar, transport, shuffle, repeat and the queue.
 * [live]: music is the active mode and [ui]/[queue] are the player's. Otherwise they are the saved queue's
 * ([SavedMusic.toUi][app.granger.core.player.SavedMusic]), shown paused: any transport control first makes music the
 * active mode, playing from there; shuffle and repeat only show, and the queue is read-only.
 */
@Composable
internal fun MusicPlayer(ui: PlayerUi, queue: List<Track>, live: Boolean, viewModel: PlayerViewModel, onShelf: () -> Unit) {
    val music = ui.now as? NowPlaying.Music ?: return
    var showQueue by rememberSaveable { mutableStateOf(false) }
    fun control(action: PlayerViewModel.() -> Unit) = if (live) viewModel.action() else viewModel.activate(PlayerMode.MUSIC, action)

    BoxWithConstraints(Modifier.fillMaxSize().playerInsets()) {
        val coverSize = minOf(maxWidth - 48.dp, maxHeight * 0.5f, 360.dp)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PlayerCover(music.track.coverPath, book = false, size = coverSize)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    music.track.title, style = MaterialTheme.typography.titleLarge, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                )
                Text(
                    music.track.artists.ifBlank { music.track.albumArtists }, style = MaterialTheme.typography.bodyLarge,
                    color = GrangerColors.Flame, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    Albums.title(music.track), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            SeekBar(ui.positionMs, ui.durationMs, { p -> control { seekTo(p) } }, label = "Seek in track")
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ModeToggle(live, onClick = viewModel::toggleShuffle) {
                    Icon(
                        painterResource(R.drawable.ic_shuffle),
                        contentDescription = if (music.shuffle) "Shuffle on" else "Shuffle off",
                        tint = if (music.shuffle) GrangerColors.Flame else LocalContentColor.current,
                    )
                }
                IconButton(onClick = { control { previous() } }, modifier = Modifier.size(56.dp)) {
                    Icon(painterResource(R.drawable.ic_skip_previous), contentDescription = "Previous track", modifier = Modifier.size(36.dp))
                }
                BigPlayPause(ui.playing, { if (live) viewModel.playPause() else viewModel.activate(PlayerMode.MUSIC) })
                IconButton(onClick = { control { next() } }, modifier = Modifier.size(56.dp)) {
                    Icon(painterResource(R.drawable.ic_skip_next), contentDescription = "Next track", modifier = Modifier.size(36.dp))
                }
                ModeToggle(live, onClick = viewModel::cycleRepeat) {
                    Icon(
                        painterResource(if (music.repeat == 2) R.drawable.ic_repeat_one else R.drawable.ic_repeat),
                        contentDescription = when (music.repeat) { 1 -> "Repeat all"; 2 -> "Repeat one"; else -> "Repeat off" },
                        tint = if (music.repeat != 0) GrangerColors.Flame else LocalContentColor.current,
                    )
                }
            }
            TextButton(onClick = { showQueue = true }) {
                Icon(painterResource(R.drawable.ic_queue_music), contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Queue")
            }
            ShelfLink(PlayerMode.MUSIC, onShelf)
            Spacer(Modifier.height(8.dp))
        }
    }
    if (showQueue) {
        QueueSheet(
            queue = queue,
            current = music.index,
            onMove = viewModel::moveInQueue,
            onRemove = viewModel::removeFromQueue,
            onPlay = viewModel::playQueueItem,
            onDismiss = { showQueue = false },
            editable = live,
        )
    }
}

/** Shuffle or repeat: a button while music is live, just its state on the saved queue. */
@Composable
private fun ModeToggle(live: Boolean, onClick: () -> Unit, icon: @Composable () -> Unit) {
    if (live) {
        IconButton(onClick = onClick, content = icon)
    } else {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { icon() }
    }
}
