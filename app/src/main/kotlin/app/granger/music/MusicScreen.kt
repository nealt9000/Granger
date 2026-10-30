package app.granger.music

import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.res.stringResource
import app.granger.R
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
enum class MusicTab(@StringRes val title: Int) {
    ALBUMS(R.string.music_tab_albums), ARTISTS(R.string.music_tab_artists), SONGS(R.string.music_tab_songs),
    GENRES(R.string.music_tab_genres), PLAYLISTS(R.string.music_tab_playlists), FOLDERS(R.string.music_tab_folders);

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
                    title = { Text(loaded?.title?.resolve().orEmpty()) },
                    navigationIcon = { IconButton(onClick = nav.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back)) } },
                )
                PrimaryScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 8.dp) {
                    for (t in MusicTab.entries) Tab(selected = tab == t, onClick = { tab = t }, text = { Text(stringResource(t.title)) })
                }
            }
        },
    ) { padding ->
        val ui = loaded ?: return@Scaffold
        Box(Modifier.padding(padding).fillMaxSize()) {
            // Nothing in this scope at all: say so once, with where to get some (playlists still work).
            if (ui.songs.isEmpty() && tab != MusicTab.PLAYLISTS) {
                EmptyMedia(
                    stringResource(R.string.music_none_yet),
                    links = listOf(SourceLink.ARCHIVE_ORG_MUSIC),
                    onOpenSource = nav.onOpenSource,
                    onMediaSettings = nav.onMediaSettings,
                )
                return@Box
            }
            when (tab) {
                MusicTab.SONGS -> SongsTab(ui, vm, dialogs, nav)
                MusicTab.ALBUMS -> AlbumsTab(ui, vm, dialogs, nav)
                MusicTab.ARTISTS -> if (ui.artistSections.isEmpty) Empty(stringResource(R.string.music_no_artists)) else ShelfList(ui.artistSections, Modifier.fillMaxSize(), contentType = "artist") { artist ->
                    ListItem(
                        modifier = Modifier.clickable { nav.onArtist(artist.name) },
                        headlineContent = { Text(shownName(artist.name)) },
                        supportingContent = { Text(stringResource(R.string.music_albums_and_songs, albumCount(artist.albumCount), songCount(artist.songCount))) },
                    )
                }
                MusicTab.GENRES -> LazyColumn(Modifier.fillMaxSize()) {
                    if (ui.genres.isEmpty()) item { Empty(stringResource(R.string.music_no_genres)) }
                    items(ui.genres, key = { it.name }) { genre ->
                        ListItem(
                            modifier = Modifier.clickable { nav.onGenre(genre.name) },
                            headlineContent = { Text(shownName(genre.name)) },
                            supportingContent = { Text(songCount(genre.songCount)) },
                        )
                    }
                }
                MusicTab.PLAYLISTS -> PlaylistsTab(ui.playlists, vm, nav)
                MusicTab.FOLDERS -> LazyColumn(Modifier.fillMaxSize()) {
                    if (ui.folders.isEmpty()) item { Empty(stringResource(R.string.music_no_folders)) }
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
            if (onOpenSource != null) for (link in links) OutlinedButton(onClick = { onOpenSource(link) }) { Text(stringResource(link.title)) }
            if (onMediaSettings != null) TextButton(onClick = onMediaSettings) { Text(stringResource(R.string.music_media_settings)) }
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
    val source = MusicSources.songs(ui.title.resolve().ifBlank { stringResource(R.string.music_songs) })
    val songs = ui.songs
    // A tap plays the list as shown, from the tapped song.
    val index = remember(songs) { songs.withIndex().associate { it.value.id to it.index } }
    val songSorts = SongSort.entries.associateWith { stringResource(it.label) }
    ShelfList(
        ui.songSections,
        Modifier.fillMaxSize(),
        contentType = "song",
        header = {
            item(key = "header", contentType = "header") {
                ShelfHeader(ui.sort, SongSort.entries, { songSorts.getValue(it) }, vm::setSort, leading = {
                    OutlinedButton(onClick = { vm.play(songs, null, source, shuffle = true) }, enabled = songs.isNotEmpty()) { Text(stringResource(R.string.music_shuffle_all)) }
                })
            }
            if (songs.isEmpty()) item(key = "none") { Empty(stringResource(R.string.music_no_songs)) }
        },
    ) { track ->
        SongRow(track, vm, dialogs, nav, onPlay = { vm.play(songs, index[track.id], source) })
    }
}

/** Albums (Plan LV): List (the default) or Grid, by Title, Artist or Year. */
@Composable
private fun AlbumsTab(ui: MusicUi, vm: MusicViewModel, dialogs: MusicDialogs, nav: MusicNav) {
    if (ui.albumSections.isEmpty) {
        Empty(stringResource(R.string.music_no_albums))
        return
    }
    val view by vm.albumView.collectAsStateWithLifecycle()
    val albumSorts = AlbumSort.entries.associateWith { stringResource(it.label) }
    val header: @Composable () -> Unit = {
        ShelfHeader(ui.albumSort, AlbumSort.entries, { albumSorts.getValue(it) }, vm::setAlbumSort, view = view, onToggleView = vm::toggleAlbumView)
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
                headlineContent = { Text(stringResource(R.string.music_new_playlist)) },
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
                    DropdownMenuItem(text = { Text(stringResource(R.string.music_rename)) }, onClick = { menu = false; dialog = PlaylistDialog.Rename(playlist) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.common_delete)) }, onClick = { menu = false; dialog = PlaylistDialog.Delete(playlist) })
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
            title = stringResource(R.string.music_new_playlist), label = stringResource(R.string.music_playlist_name),
            confirm = stringResource(R.string.music_create),
            onConfirm = { vm.createPlaylist(it); onDone() }, onDismiss = onDone,
        )
        is PlaylistDialog.Rename -> RenamePlaylistDialog(dialog.playlist, vm, onDone)
        is PlaylistDialog.Delete -> DeletePlaylistDialog(dialog.playlist, vm, onDone)
    }
}

@Composable
internal fun RenamePlaylistDialog(playlist: Playlist, vm: MusicViewModel, onDone: () -> Unit) {
    NameDialog(
        title = stringResource(R.string.music_rename_playlist), label = stringResource(R.string.music_playlist_name),
        confirm = stringResource(R.string.music_rename), initial = playlist.name,
        onConfirm = { vm.renamePlaylist(playlist.id, it); onDone() }, onDismiss = onDone,
    )
}

@Composable
internal fun DeletePlaylistDialog(playlist: Playlist, vm: MusicViewModel, onDone: () -> Unit) {
    ConfirmDialog(
        title = stringResource(R.string.music_delete_playlist),
        message = stringResource(R.string.music_delete_playlist_message, playlist.name),
        confirm = stringResource(R.string.common_delete),
        onConfirm = { vm.deletePlaylist(playlist.id); onDone() },
        onDismiss = onDone,
    )
}
