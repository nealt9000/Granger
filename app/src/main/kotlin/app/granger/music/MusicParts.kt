package app.granger.music

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.granger.core.library.MediaNames
import app.granger.library.SourceLink
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.granger.R
import app.granger.core.library.AlbumSummary
import app.granger.core.library.Albums
import app.granger.core.library.PackStatus
import app.granger.core.library.Track
import app.granger.library.MovePackDialog
import app.granger.library.shelf.ShelfCoverSize
import app.granger.library.shelf.ShelfRow
import app.granger.player.PlayingBars
import app.granger.player.formatTime
import app.granger.shell.rememberRailCover
import app.granger.theme.MediaKind
import app.granger.theme.MediaTile
import app.granger.theme.readout

/** Where the music screens lead. */
data class MusicNav(
    val onAlbum: (albumKey: String) -> Unit,
    val onArtist: (name: String) -> Unit,
    val onGenre: (name: String) -> Unit,
    val onPlaylist: (id: Long) -> Unit,
    val onFolder: (folder: String) -> Unit,
    val onBack: () -> Unit,
    /** An empty Music page's links: a download source, and Settings → Music & audiobooks. Null: not offered. */
    val onOpenSource: ((SourceLink) -> Unit)? = null,
    val onMediaSettings: (() -> Unit)? = null,
)

/**
 * A name as shown: the library's "Unknown artist", "Unknown album" and "Unknown genre" placeholders are data (stored,
 * sorted and compared in English), so only their display goes through resources.
 */
@Composable
internal fun shownName(name: String): String = when (name) {
    Albums.UNKNOWN_ARTIST -> stringResource(R.string.music_unknown_artist)
    Albums.UNKNOWN_ALBUM -> stringResource(R.string.music_unknown_album)
    MediaNames.UNKNOWN_GENRE -> stringResource(R.string.music_unknown_genre)
    else -> name
}

/** Display names, with the "Unknown …" fallbacks applied here rather than stored. */
internal fun trackTitle(t: Track) = t.title.ifBlank { t.uri.substringAfterLast('/') }
@Composable internal fun trackArtist(t: Track) = MusicCatalog.trackArtistsOf(t).map { shownName(it) }.joinToString(", ")
@Composable internal fun trackAlbum(t: Track) = shownName(Albums.title(t))
internal fun albumArtistNames(a: AlbumSummary): List<String> =
    a.albumArtist.split("; ").map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf(Albums.UNKNOWN_ARTIST) }

@Composable internal fun songCount(n: Int) = pluralStringResource(R.plurals.music_songs_count, n, n)
@Composable internal fun albumCount(n: Int) = pluralStringResource(R.plurals.common_albums, n, n)

/** An album's artists as shown, joined. */
@Composable internal fun shownArtists(album: AlbumSummary) = albumArtistNames(album).map { shownName(it) }.joinToString(", ")

/** What the long-press menus open: a playlist picker for some tracks or an album, or the move dialog for an album. */
@Stable
class MusicDialogs {
    var addTracks by mutableStateOf<List<Track>?>(null)
    var addAlbum by mutableStateOf<AlbumSummary?>(null)
    var moveAlbum by mutableStateOf<AlbumSummary?>(null)
}

/** Shows whatever [dialogs] asks for. Every music screen hosts one. */
@Composable
fun MusicDialogHost(vm: MusicViewModel, dialogs: MusicDialogs) {
    val playlists by vm.allPlaylists.collectAsStateWithLifecycle()
    // Collected up front so the move dialog knows the album's current collection when it opens.
    val targets by vm.moveTargets.collectAsStateWithLifecycle()
    val tracks = dialogs.addTracks
    val album = dialogs.addAlbum
    if (tracks != null || album != null) {
        AddToPlaylistSheet(
            playlists = playlists,
            onPick = { id ->
                if (album != null) vm.addAlbumToPlaylist(id, album) else vm.addToPlaylist(id, tracks.orEmpty())
                dialogs.addTracks = null; dialogs.addAlbum = null
            },
            onNew = { name ->
                if (album != null) vm.createPlaylistFromAlbum(name, album) else vm.createPlaylist(name, tracks.orEmpty())
                dialogs.addTracks = null; dialogs.addAlbum = null
            },
            onDismiss = { dialogs.addTracks = null; dialogs.addAlbum = null },
        )
    }
    val moving = dialogs.moveAlbum
    if (moving != null) {
        MovePackDialog(
            shelves = targets,
            currentCollectionId = vm.collectionOf(moving),
            onMove = { vm.moveAlbum(moving, it) },
            onMoveToNewCollection = { _, name -> vm.moveAlbumToNewCollection(moving, name) },
            onMoveToNewMediaType = { _, _ -> },
            onDismiss = { dialogs.moveAlbum = null },
            allowNewMediaType = false,
        )
    }
}

/**
 * A song row. A MISSING track is dimmed and can't be played, but still opens its menu. [playing] marks the player's
 * current song (Plan U2 Decision 8): null for any other, true while it's heard, false while paused. The mark is the
 * [PlayingBars] in place of [leading], and the title in the accent colour.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    track: Track,
    vm: MusicViewModel,
    dialogs: MusicDialogs,
    nav: MusicNav,
    onPlay: () -> Unit,
    leading: String? = null,
    showAlbum: Boolean = true,
    playing: Boolean? = null,
    extraMenu: @Composable (close: () -> Unit) -> Unit = {},
) {
    val playable = track.status == PackStatus.OK
    var menu by remember { mutableStateOf(false) }
    Box {
        ListItem(
            modifier = Modifier
                .combinedClickable(onClick = { if (playable) onPlay() }, onLongClick = { menu = true })
                .alpha(if (playable) 1f else 0.4f),
            // A fixed, right-aligned column, so titles line up whether the number is "9", "10" or blank.
            leadingContent = when {
                playing != null && leading != null -> {
                    { Box(Modifier.width(28.dp), contentAlignment = Alignment.CenterEnd) { PlayingBars(heard = playing) } }
                }
                leading != null -> {
                    { Text(leading, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, modifier = Modifier.width(28.dp)) }
                }
                playing != null -> { { PlayingBars(heard = playing) } }
                else -> null
            },
            headlineContent = {
                Text(
                    trackTitle(track), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (playing != null) MaterialTheme.colorScheme.primary else Color.Unspecified,
                )
            },
            supportingContent = {
                val parts = listOfNotNull(trackArtist(track), if (showAlbum) trackAlbum(track) else null)
                Text(
                    if (playable) parts.joinToString(" · ") else stringResource(R.string.music_file_not_found),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            },
            trailingContent = { Text(formatTime(track.durationMs), style = MaterialTheme.typography.labelMedium.readout) },
        )
        DropdownMenu(menu, { menu = false }) {
            val close = { menu = false }
            DropdownMenuItem(text = { Text(stringResource(R.string.music_play_next)) }, enabled = playable, onClick = { close(); vm.playNext(listOf(track)) })
            DropdownMenuItem(text = { Text(stringResource(R.string.music_add_to_queue)) }, enabled = playable, onClick = { close(); vm.addToQueue(listOf(track)) })
            DropdownMenuItem(text = { Text(stringResource(R.string.music_add_to_playlist_menu)) }, onClick = { close(); dialogs.addTracks = listOf(track) })
            extraMenu(close)
            DropdownMenuItem(text = { Text(stringResource(R.string.music_go_to_album)) }, onClick = { close(); nav.onAlbum(track.albumKey) })
            for (artist in MusicCatalog.trackArtistsOf(track).take(3)) {
                DropdownMenuItem(text = { Text(stringResource(R.string.music_go_to, shownName(artist))) }, onClick = { close(); nav.onArtist(artist) })
            }
        }
    }
}

/** A square cover, or the themed tile with [title] and [kind]'s glyph when there's none (Plan U3c Decision 6). */
@Composable
fun AlbumCover(path: String?, title: String, modifier: Modifier = Modifier, kind: MediaKind = MediaKind.MUSIC) {
    val cover = rememberRailCover(path, 180.dp)
    Box(modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        if (cover != null) {
            Image(cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            MediaTile(title, kind)
        }
    }
}

/** An album in a grid: square cover, title and artist. Long-press: play next, queue, playlist, artist, move. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumCell(album: AlbumSummary, vm: MusicViewModel, dialogs: MusicDialogs, nav: MusicNav) {
    val playable = album.status == PackStatus.OK
    var menu by remember { mutableStateOf(false) }
    Box {
        Column(
            Modifier.combinedClickable(onClick = { nav.onAlbum(album.key) }, onLongClick = { menu = true }).alpha(if (playable) 1f else 0.4f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AlbumCover(album.coverPath, shownName(album.title), Modifier.fillMaxWidth())
            Text(shownName(album.title), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                shownArtists(album), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        AlbumMenu(album, menu, { menu = false }, vm, dialogs, nav)
    }
}

/** An album in the list (Plan LV): a 48 dp cover, the title, and "artist · year". Long-press: as [AlbumCell]. */
@Composable
fun AlbumListRow(album: AlbumSummary, vm: MusicViewModel, dialogs: MusicDialogs, nav: MusicNav) {
    var menu by remember { mutableStateOf(false) }
    Box {
        ShelfRow(
            title = shownName(album.title),
            subtitle = (listOf(shownArtists(album)) + listOfNotNull(album.year.ifEmpty { null })).joinToString(" · "),
            cover = { AlbumCover(album.coverPath, shownName(album.title), Modifier.size(ShelfCoverSize)) },
            onClick = { nav.onAlbum(album.key) },
            onLongClick = { menu = true },
            dimmed = album.status != PackStatus.OK,
        )
        AlbumMenu(album, menu, { menu = false }, vm, dialogs, nav)
    }
}

@Composable
fun AlbumMenu(album: AlbumSummary, open: Boolean, onClose: () -> Unit, vm: MusicViewModel, dialogs: MusicDialogs, nav: MusicNav, showArtists: Boolean = true) {
    val playable = album.status == PackStatus.OK
    DropdownMenu(open, onClose) {
        DropdownMenuItem(text = { Text(stringResource(R.string.music_play_next)) }, enabled = playable, onClick = { onClose(); vm.playNextAlbum(album) })
        DropdownMenuItem(text = { Text(stringResource(R.string.music_add_to_queue)) }, enabled = playable, onClick = { onClose(); vm.queueAlbum(album) })
        DropdownMenuItem(text = { Text(stringResource(R.string.music_add_to_playlist_menu)) }, onClick = { onClose(); dialogs.addAlbum = album })
        if (showArtists) for (artist in albumArtistNames(album).take(3)) {
            DropdownMenuItem(text = { Text(stringResource(R.string.music_go_to, shownName(artist))) }, onClick = { onClose(); nav.onArtist(artist) })
        }
        DropdownMenuItem(text = { Text(stringResource(R.string.music_move_to_collection)) }, onClick = { onClose(); dialogs.moveAlbum = album })
    }
}

/**
 * "Play" and "Shuffle" over a list of tracks. With [onStop] (the album page, while its queue plays), Play is "Stop"
 * instead, and stops the player; once stopped it's Play again (Plan U2 Decision 8).
 */
@Composable
fun PlayShuffleRow(enabled: Boolean, onPlay: () -> Unit, onShuffle: () -> Unit, modifier: Modifier = Modifier, onStop: (() -> Unit)? = null) {
    Row(modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (onStop != null) {
            Button(onClick = onStop) {
                Icon(painterResource(R.drawable.ic_stop), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.common_stop))
            }
        } else {
            Button(onClick = onPlay, enabled = enabled) { Text(stringResource(R.string.common_play)) }
        }
        OutlinedButton(onClick = onShuffle, enabled = enabled) { Text(stringResource(R.string.music_shuffle)) }
    }
}
