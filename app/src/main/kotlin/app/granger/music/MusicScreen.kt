package app.granger.music

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.granger.core.library.Playlist
import app.granger.library.ConfirmDialog
import app.granger.library.NameDialog

enum class MusicTab(val title: String) { SONGS("Songs"), ALBUMS("Albums"), ARTISTS("Artists"), GENRES("Genres"), PLAYLISTS("Playlists"), FOLDERS("Folders") }

/** The Auxio-style tabs for one Music collection, or for "All music". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicScreen(vm: MusicViewModel, nav: MusicNav) {
    val loaded by vm.ui.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(MusicTab.SONGS) }
    val dialogs = remember { MusicDialogs() }
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(loaded?.title.orEmpty()) },
                    navigationIcon = { IconButton(onClick = nav.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                )
                PrimaryScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 8.dp) {
                    for (t in MusicTab.entries) Tab(selected = tab == t, onClick = { tab = t }, text = { Text(t.title) })
                }
            }
        },
    ) { padding ->
        val ui = loaded ?: return@Scaffold
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                MusicTab.SONGS -> SongsTab(ui, vm, dialogs, nav)
                MusicTab.ALBUMS -> AlbumGrid(ui.albums, vm, dialogs, nav)
                MusicTab.ARTISTS -> LazyColumn(Modifier.fillMaxSize()) {
                    if (ui.artists.isEmpty()) item { Empty("No artists") }
                    items(ui.artists, key = { it.name }) { artist ->
                        ListItem(
                            modifier = Modifier.clickable { nav.onArtist(artist.name) },
                            headlineContent = { Text(artist.name) },
                            supportingContent = { Text(listOf(albumCount(artist.albumCount), songCount(artist.songCount)).joinToString(" · ")) },
                        )
                    }
                }
                MusicTab.GENRES -> LazyColumn(Modifier.fillMaxSize()) {
                    if (ui.genres.isEmpty()) item { Empty("No genres") }
                    items(ui.genres, key = { it.name }) { genre ->
                        ListItem(
                            modifier = Modifier.clickable { nav.onGenre(genre.name) },
                            headlineContent = { Text(genre.name) },
                            supportingContent = { Text(songCount(genre.songCount)) },
                        )
                    }
                }
                MusicTab.PLAYLISTS -> PlaylistsTab(ui.playlists, vm, nav)
                MusicTab.FOLDERS -> LazyColumn(Modifier.fillMaxSize()) {
                    if (ui.folders.isEmpty()) item { Empty("No folders") }
                    items(ui.folders, key = { it.path }) { folder ->
                        ListItem(
                            modifier = Modifier.clickable { nav.onFolder(folder.path) },
                            headlineContent = { Text(folder.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(songCount(folder.songCount)) },
                        )
                    }
                }
            }
        }
    }
    MusicDialogHost(vm, dialogs)
}

@Composable
internal fun Empty(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
}

@Composable
private fun SongsTab(ui: MusicUi, vm: MusicViewModel, dialogs: MusicDialogs, nav: MusicNav) {
    val source = MusicSources.songs(ui.title.ifBlank { "Songs" })
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { vm.play(ui.songs, null, source, shuffle = true) }, enabled = ui.songs.isNotEmpty()) { Text("Shuffle all") }
                Spacer(Modifier.weight(1f))
                Box {
                    var open by remember { mutableStateOf(false) }
                    TextButton(onClick = { open = true }) { Text("Sort: ${ui.sort.label}") }
                    DropdownMenu(open, { open = false }) {
                        for (s in SongSort.entries) DropdownMenuItem(text = { Text(s.label) }, onClick = { open = false; vm.setSort(s) })
                    }
                }
            }
        }
        if (ui.songs.isEmpty()) item { Empty("No songs") }
        itemsIndexed(ui.songs, key = { _, t -> t.id }) { i, track ->
            SongRow(track, vm, dialogs, nav, onPlay = { vm.play(ui.songs, i, source) })
        }
    }
}

@Composable
internal fun AlbumGrid(albums: List<app.granger.core.library.AlbumSummary>, vm: MusicViewModel, dialogs: MusicDialogs, nav: MusicNav) {
    if (albums.isEmpty()) {
        Empty("No albums")
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(albums, key = { it.key }) { album -> AlbumCell(album, vm, dialogs, nav) }
    }
}

private sealed interface PlaylistDialog {
    data object New : PlaylistDialog
    data class Rename(val playlist: Playlist) : PlaylistDialog
    data class Delete(val playlist: Playlist) : PlaylistDialog
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistsTab(playlists: List<Playlist>, vm: MusicViewModel, nav: MusicNav) {
    var dialog by remember { mutableStateOf<PlaylistDialog?>(null) }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            ListItem(
                modifier = Modifier.clickable { dialog = PlaylistDialog.New },
                leadingContent = { Icon(Icons.Filled.Add, contentDescription = null) },
                headlineContent = { Text("New playlist") },
            )
        }
        items(playlists, key = { it.id }) { playlist ->
            Box {
                var menu by remember { mutableStateOf(false) }
                ListItem(
                    modifier = Modifier.combinedClickable(onClick = { nav.onPlaylist(playlist.id) }, onLongClick = { menu = true }),
                    headlineContent = { Text(playlist.name) },
                )
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; dialog = PlaylistDialog.Rename(playlist) })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; dialog = PlaylistDialog.Delete(playlist) })
                }
            }
        }
    }
    PlaylistDialogs(dialog, vm) { dialog = null }
}

@Composable
private fun PlaylistDialogs(dialog: PlaylistDialog?, vm: MusicViewModel, onDone: () -> Unit) {
    when (dialog) {
        null -> Unit
        PlaylistDialog.New -> NameDialog(
            title = "New playlist", label = "Playlist name", confirm = "Create",
            onConfirm = { vm.createPlaylist(it); onDone() }, onDismiss = onDone,
        )
        is PlaylistDialog.Rename -> RenamePlaylistDialog(dialog.playlist, vm, onDone)
        is PlaylistDialog.Delete -> DeletePlaylistDialog(dialog.playlist, vm, onDone)
    }
}

@Composable
internal fun RenamePlaylistDialog(playlist: Playlist, vm: MusicViewModel, onDone: () -> Unit) {
    NameDialog(
        title = "Rename playlist", label = "Playlist name", confirm = "Rename", initial = playlist.name,
        onConfirm = { vm.renamePlaylist(playlist.id, it); onDone() }, onDismiss = onDone,
    )
}

@Composable
internal fun DeletePlaylistDialog(playlist: Playlist, vm: MusicViewModel, onDone: () -> Unit) {
    ConfirmDialog(
        title = "Delete playlist?",
        message = "${playlist.name} will be deleted. The songs stay on the phone.",
        confirm = "Delete",
        onConfirm = { vm.deletePlaylist(playlist.id); onDone() },
        onDismiss = onDone,
    )
}
