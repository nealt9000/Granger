package app.granger.music

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.granger.core.library.PackStatus

/** The songs in one folder, in album order. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderScreen(vm: MusicViewModel, folder: String, nav: MusicNav) {
    val page by remember(folder) { vm.folder(folder) }.collectAsStateWithLifecycle(null)
    val dialogs = remember { MusicDialogs() }
    val loaded = page
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(MusicCatalog.folderName(folder), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = nav.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        if (loaded == null) return@Scaffold
        val source = MusicSources.folder(loaded)
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                PlayShuffleRow(
                    enabled = loaded.songs.any { it.status == PackStatus.OK },
                    onPlay = { vm.play(loaded.songs, null, source) },
                    onShuffle = { vm.play(loaded.songs, null, source, shuffle = true) },
                )
            }
            if (loaded.songs.isEmpty()) item { Empty("No songs") }
            itemsIndexed(loaded.songs, key = { _, t -> t.id }) { i, track ->
                SongRow(track, vm, dialogs, nav, onPlay = { vm.play(loaded.songs, i, source) })
            }
        }
    }
    MusicDialogHost(vm, dialogs)
}
