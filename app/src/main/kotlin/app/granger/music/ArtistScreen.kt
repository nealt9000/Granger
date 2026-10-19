package app.granger.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.granger.core.library.AlbumSummary
import app.granger.core.library.PackStatus
import app.granger.core.library.Track

/** One artist: their albums by release type, then "Appears on", then their songs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistScreen(vm: MusicViewModel, name: String, nav: MusicNav) {
    val page by remember(name) { vm.artist(name) }.collectAsStateWithLifecycle(null)
    val dialogs = remember { MusicDialogs() }
    val loaded = page
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(loaded?.name ?: name) },
                navigationIcon = { IconButton(onClick = nav.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        if (loaded == null) return@Scaffold
        val source = MusicSources.artist(loaded.name)
        AlbumsAndSongs(
            Modifier.padding(padding),
            sections = loaded.sections.map { it.group.label to it.albums } +
                listOfNotNull(loaded.appearsOn.takeIf { it.isNotEmpty() }?.let { "Appears on" to it }),
            songs = loaded.songs,
            vm = vm, dialogs = dialogs, nav = nav,
            onPlay = { i, shuffle -> vm.play(loaded.songs, i, source, shuffle) },
        )
    }
    MusicDialogHost(vm, dialogs)
}

/**
 * A grid of album sections (each under its heading) followed by a Play/Shuffle row and the songs. Shared by the
 * artist and genre pages. [onPlay] gets the tapped song's index (null: from the start) and whether to shuffle.
 */
@Composable
internal fun AlbumsAndSongs(
    modifier: Modifier,
    sections: List<Pair<String, List<AlbumSummary>>>,
    songs: List<Track>,
    vm: MusicViewModel,
    dialogs: MusicDialogs,
    nav: MusicNav,
    onPlay: (index: Int?, shuffle: Boolean) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            PlayShuffleRow(
                enabled = songs.any { it.status == PackStatus.OK },
                onPlay = { onPlay(null, false) },
                onShuffle = { onPlay(null, true) },
            )
        }
        for ((title, albums) in sections) {
            heading(title)
            items(albums, key = { "$title:${it.key}" }) { album ->
                androidx.compose.foundation.layout.Box(Modifier.padding(horizontal = 8.dp)) { AlbumCell(album, vm, dialogs, nav) }
            }
        }
        if (songs.isNotEmpty()) heading("Songs")
        itemsIndexed(songs, key = { _, t -> "song:${t.id}" }, span = { _, _ -> GridItemSpan(maxLineSpan) }) { i, track ->
            SongRow(track, vm, dialogs, nav, onPlay = { onPlay(i, false) })
        }
    }
}

private fun LazyGridScope.heading(title: String) {
    item(key = "heading:$title", span = { GridItemSpan(maxLineSpan) }) {
        Text(
            title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}
