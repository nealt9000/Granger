package app.granger.music

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.granger.core.library.PackStatus
import app.granger.core.library.PlaylistItem

/**
 * One playlist, in order; MISSING songs stay in place, dimmed. A song's menu moves it up or down past the row next to
 * it, or removes it, by its entry ordinal ([PlaylistItem.ordinal]), not its row position: a removed download's track
 * row is deleted while its entry stays (for a re-download), and such an entry shows no row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(vm: MusicViewModel, playlistId: Long, nav: MusicNav) {
    val page by remember(playlistId) { vm.playlist(playlistId) }.collectAsStateWithLifecycle(null)
    val dialogs = remember { MusicDialogs() }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    // Leave once the playlist has been seen and then deleted.
    var seen by remember { mutableStateOf(false) }
    LaunchedEffect(page) { if (page != null) seen = true else if (seen) nav.onBack() }
    val loaded = page
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(loaded?.playlist?.name.orEmpty()) },
                navigationIcon = { IconButton(onClick = nav.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    if (loaded != null) Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renaming = true })
                            DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; deleting = true })
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (loaded == null) return@Scaffold
        val tracks = loaded.tracks
        val source = MusicSources.playlist(loaded.playlist)
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                PlayShuffleRow(
                    enabled = tracks.any { it.status == PackStatus.OK },
                    onPlay = { vm.play(tracks, null, source) },
                    onShuffle = { vm.play(tracks, null, source, shuffle = true) },
                )
            }
            if (tracks.isEmpty()) item { Empty("No songs yet. Long-press a song or album to add it.") }
            // A song can be in a playlist twice, so rows are keyed by entry ordinal.
            val items = loaded.items
            itemsIndexed(items, key = { _, item -> "${item.ordinal}:${item.track.id}" }) { i, item ->
                SongRow(item.track, vm, dialogs, nav, onPlay = { vm.play(tracks, i, source) }) { close ->
                    if (i > 0) DropdownMenuItem(text = { Text("Move up") }, onClick = { close(); vm.moveInPlaylist(playlistId, item.ordinal, items[i - 1].ordinal) })
                    if (i < items.lastIndex) DropdownMenuItem(text = { Text("Move down") }, onClick = { close(); vm.moveInPlaylist(playlistId, item.ordinal, items[i + 1].ordinal) })
                    DropdownMenuItem(text = { Text("Remove from playlist") }, onClick = { close(); vm.removeFromPlaylist(playlistId, item.ordinal) })
                }
            }
        }
        if (renaming) RenamePlaylistDialog(loaded.playlist, vm) { renaming = false }
        if (deleting) DeletePlaylistDialog(loaded.playlist, vm) { deleting = false }
    }
    MusicDialogHost(vm, dialogs)
}
