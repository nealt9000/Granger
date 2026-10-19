package app.granger.player

import androidx.compose.foundation.layout.Arrangement
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.granger.R
import app.granger.core.player.NowPlaying
import app.granger.theme.GrangerColors

/** The music player (Auxio-style): cover, title/artist/album, seek bar, transport, shuffle, repeat and the queue. */
@Composable
fun MusicPlayerScreen(viewModel: PlayerViewModel, onClose: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val music = ui.now as? NowPlaying.Music ?: return
    var showQueue by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        PlayerTopBar("Now playing · ${music.index + 1} of ${music.queueSize}", onClose)
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
                        music.track.album, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                SeekBar(ui.positionMs, ui.durationMs, viewModel::seekTo, label = "Seek in track")
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = viewModel::toggleShuffle) {
                        Icon(
                            painterResource(R.drawable.ic_shuffle),
                            contentDescription = if (music.shuffle) "Shuffle on" else "Shuffle off",
                            tint = if (music.shuffle) GrangerColors.Flame else LocalContentColor.current,
                        )
                    }
                    IconButton(onClick = viewModel::previous, modifier = Modifier.size(56.dp)) {
                        Icon(painterResource(R.drawable.ic_skip_previous), contentDescription = "Previous track", modifier = Modifier.size(36.dp))
                    }
                    BigPlayPause(ui.playing, viewModel::playPause)
                    IconButton(onClick = viewModel::next, modifier = Modifier.size(56.dp)) {
                        Icon(painterResource(R.drawable.ic_skip_next), contentDescription = "Next track", modifier = Modifier.size(36.dp))
                    }
                    IconButton(onClick = viewModel::cycleRepeat) {
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
                Spacer(Modifier.height(8.dp))
            }
        }
    }
    if (showQueue) {
        QueueSheet(
            queue = queue,
            current = music.index,
            onMove = viewModel::moveInQueue,
            onRemove = viewModel::removeFromQueue,
            onDismiss = { showQueue = false },
        )
    }
}
