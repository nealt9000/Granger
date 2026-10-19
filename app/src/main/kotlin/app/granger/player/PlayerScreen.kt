package app.granger.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.granger.core.player.NowPlaying
import app.granger.theme.GrangerColors

/** [PlayerRoute][app.granger.PlayerRoute]: the music or audiobook player, whichever is loaded. */
@Composable
fun PlayerScreen(viewModel: PlayerViewModel, onClose: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    // A Surface, like the Scaffold every other screen sits in: it provides onBackground as LocalContentColor, without
    // which every untinted Icon and uncoloured Text here falls back to black on the dark background.
    Surface(
        Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        when (ui.now) {
            is NowPlaying.Music -> MusicPlayerScreen(viewModel, onClose)
            is NowPlaying.Book -> AudiobookPlayerScreen(viewModel, onClose)
            NowPlaying.Idle -> Column(Modifier.fillMaxSize()) {
                PlayerTopBar("Player", onClose)
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text("Nothing is playing", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Both players' bar: close (back to the mini-player) and a small label. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerTopBar(label: String, onClose: () -> Unit) {
    TopAppBar(
        title = { Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        navigationIcon = {
            IconButton(onClick = onClose) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Close player") }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

/** The big flame play/pause button both players centre their controls on. */
@Composable
internal fun BigPlayPause(playing: Boolean, onClick: () -> Unit, size: Dp = 72.dp) {
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(size),
        shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = GrangerColors.Flame, contentColor = GrangerColors.OnFlame),
    ) {
        PlayPauseIcon(playing, Modifier.size(size / 2))
    }
}

/** Keeps the players' content clear of the navigation bar and an end-side cutout. */
@Composable
internal fun Modifier.playerInsets(): Modifier = windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.End))
