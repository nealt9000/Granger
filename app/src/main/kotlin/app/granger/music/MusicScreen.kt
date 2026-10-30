package app.granger.music

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import app.granger.library.SourceLink
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.granger.core.library.Playlist
import app.granger.library.shelf.ShelfGrid
import app.granger.library.shelf.ShelfHeader
import app.granger.library.shelf.ShelfList
import app.granger.library.shelf.ShelfView
import androidx.compose.foundation.lazy.grid.GridItemSpan
import app.granger.library.ConfirmDialog
import app.granger.library.NameDialog

/** In tab order. */
enum class MusicTab(val title: String) {
    ALBUMS("Albums"), ARTISTS("Artists"), SONGS("Songs"), GENRES("Genres"), PLAYLISTS("Playlists"), FOLDERS("Folders");

    companion object {
        /** The tab Music opens on. */
        val DEFAULT = ALBUMS
    }
}

/** The Auxio-style tabs for one Music collection, or for "All music". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicScreen(vm: MusicViewModel, nav: MusicNav) {
    val loaded by vm.ui.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(MusicTab.DEFAULT) }
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
            // Nothing in this scope at all: say so once, with where to get some (playlists still work).
            if (ui.songs.isEmpty() && tab != MusicTab.PLAYLISTS) {
                EmptyMedia(
                    "No music yet",
                    links = listOf(SourceLink.ARCHIVE_ORG_MUSIC),
                    onOpenSource = nav.onOpenSource,
                    onMediaSettings = nav.onMediaSettings,
                )
                return@Box
            }
            when (tab) {
                MusicTab.SONGS -> SongsTab(ui, vm, dialogs, nav)
                MusicTab.ALBUMS -> AlbumsTab(ui, vm, dialogs, nav)
                MusicTab.ARTISTS -> if (ui.artistSections.isEmpty) Empty("No artists") else ShelfList(ui.artistSections, Modifier.fillMaxSize(), contentType = "artist") { artist ->
                    ListItem(
                        modifier = Modifier.clickable { nav.onArtist(artist.name) },
                        headlineContent = { Text(artist.name) },
                        supportingContent = { Text(listOf(albumCount(artist.albumCount), songCount(artist.songCount)).joinToString(" · ")) },
                    )
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

/**
 * An empty Music page or Audiobooks shelf: [text], then buttons for its download [links] and Settings → Music &
 * audiobooks, each shown only when its callback is given.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EmptyMedia(text: String, links: List<SourceLink>, onOpenSource: ((SourceLink) -> Unit)?, onMediaSettings: (() -> Unit)?) {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onOpenSource != null) for (link in links) OutlinedButton(onClick = { onOpenSource(link) }) { Text(link.title) }
            if (onMediaSettings != null) TextButton(onClick = onMediaSettings) { Text("Music & audiobooks settings") }
        }
    }
}

@Composable
internal fun Empty(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
}

/** Songs (Plan LV): the shared list, under letters for Title, Artist and Album. No grid: a song has no cover of its own. */
@Composable
private fun SongsTab(ui: MusicUi, vm: MusicViewModel, dialogs: MusicDialogs, nav: MusicNav) {
    val source = MusicSources.songs(ui.title.ifBlank { "Songs" })
    val songs = ui.songs
    // A tap plays the list as shown, from the tapped song.
    val index = remember(songs) { songs.withIndex().associate { it.value.id to it.index } }
    ShelfList(
        ui.songSections,
        Modifier.fillMaxSize(),
        contentType = "song",
        header = {
            item(key = "header", contentType = "header") {
                ShelfHeader(ui.sort, SongSort.entries, { it.label }, vm::setSort, leading = {
                    OutlinedButton(onClick = { vm.play(songs, null, source, shuffle = true) }, enabled = songs.isNotEmpty()) { Text("Shuffle all") }
                })
            }
            if (songs.isEmpty()) item(key = "none") { Empty("No songs") }
        },
    ) { track ->
        SongRow(track, vm, dialogs, nav, onPlay = { vm.play(songs, index[track.id], source) })
    }
}

/** Albums (Plan LV): List (the default) or Grid, by Title, Artist or Year. */
@Composable
private fun AlbumsTab(ui: MusicUi, vm: MusicViewModel, dialogs: MusicDialogs, nav: MusicNav) {
    if (ui.albumSections.isEmpty) {
        Empty("No albums")
        return
    }
    val view by vm.albumView.collectAsStateWithLifecycle()
    val header: @Composable () -> Unit = {
        ShelfHeader(ui.albumSort, AlbumSort.entries, { it.label }, vm::setAlbumSort, view = view, onToggleView = vm::toggleAlbumView)
    }
    if (view == ShelfView.LIST) {
        ShelfList(ui.albumSections, Modifier.fillMaxSize(), contentType = "album", header = { item(key = "header", contentType = "header") { header() } }) { album ->
            AlbumListRow(album, vm, dialogs, nav)
        }
    } else {
        ShelfGrid(
            ui.albumSections,
            cellMin = 150.dp,
            modifier = Modifier.fillMaxSize(),
            // 12 dp edges, so two 150 dp columns still fit beside the rail and the letter strip on a 411 dp phone.
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp),
            headerIndent = 4.dp,
            contentType = "album",
            header = { item(key = "header", span = { GridItemSpan(maxLineSpan) }, contentType = "header") { header() } },
        ) { album -> AlbumCell(album, vm, dialogs, nav) }
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
