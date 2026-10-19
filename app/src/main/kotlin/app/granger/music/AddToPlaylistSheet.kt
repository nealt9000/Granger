package app.granger.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.granger.core.library.Playlist
import app.granger.library.NameDialog

/** Pick a playlist to add to, or make a new one ("New playlist" asks for its name). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(playlists: List<Playlist>, onPick: (playlistId: Long) -> Unit, onNew: (name: String) -> Unit, onDismiss: () -> Unit) {
    var naming by rememberSaveable { mutableStateOf(false) }
    if (naming) {
        NameDialog(
            title = "New playlist",
            label = "Playlist name",
            confirm = "Create",
            onConfirm = { onNew(it) },
            onDismiss = { naming = false },
        )
        return
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding()) {
            Text("Add to playlist", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            LazyColumn {
                item {
                    ListItem(
                        modifier = Modifier.clickable { naming = true },
                        leadingContent = { Icon(Icons.Filled.Add, contentDescription = null) },
                        headlineContent = { Text("New playlist") },
                    )
                }
                items(playlists, key = { it.id }) { playlist ->
                    ListItem(
                        modifier = Modifier.clickable { onPick(playlist.id) },
                        headlineContent = { Text(playlist.name) },
                    )
                }
            }
        }
    }
}
