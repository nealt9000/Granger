package app.granger.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.granger.core.library.PackStatus
import app.granger.player.formatTime

/** One album: cover, details, Play/Shuffle, and its tracks in disc/track order. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumScreen(vm: MusicViewModel, albumKey: String, nav: MusicNav) {
    val page by remember(albumKey) { vm.album(albumKey) }.collectAsStateWithLifecycle(null)
    val dialogs = remember { MusicDialogs() }
    var menu by remember { mutableStateOf(false) }
    val loaded = page
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(loaded?.album?.title.orEmpty()) },
                navigationIcon = { IconButton(onClick = nav.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    if (loaded != null) Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                        AlbumMenu(loaded.album, menu, { menu = false }, vm, dialogs, nav)
                    }
                },
            )
        },
    ) { padding ->
        if (loaded == null) return@Scaffold
        val album = loaded.album
        val source = MusicSources.album(album)
        val multiDisc = loaded.tracks.mapNotNull { it.disc }.distinct().size > 1
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    AlbumCover(album.coverPath, Modifier.width(140.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(album.title, style = MaterialTheme.typography.titleLarge)
                        for (artist in albumArtistNames(album)) {
                            Text(
                                artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { nav.onArtist(artist) }.padding(vertical = 2.dp),
                            )
                        }
                        val details = listOfNotNull(album.year.ifBlank { null }, songCount(album.trackCount), formatTime(album.durationMs))
                        Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                PlayShuffleRow(
                    enabled = loaded.tracks.any { it.status == PackStatus.OK },
                    onPlay = { vm.play(loaded.tracks, null, source) },
                    onShuffle = { vm.play(loaded.tracks, null, source, shuffle = true) },
                )
            }
            itemsIndexed(loaded.tracks, key = { _, t -> t.id }) { i, track ->
                val number = track.trackNo?.let { n -> if (multiDisc) "${track.disc ?: 1}.$n" else "$n" } ?: "–"
                SongRow(track, vm, dialogs, nav, onPlay = { vm.play(loaded.tracks, i, source) }, leading = number, showAlbum = false)
            }
        }
    }
    MusicDialogHost(vm, dialogs)
}
