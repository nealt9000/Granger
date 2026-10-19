package app.granger.music

import androidx.compose.foundation.layout.padding
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** One genre: the albums with songs in it, then those songs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenreScreen(vm: MusicViewModel, name: String, nav: MusicNav) {
    val page by remember(name) { vm.genre(name) }.collectAsStateWithLifecycle(null)
    val dialogs = remember { MusicDialogs() }
    val loaded = page
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name) },
                navigationIcon = { IconButton(onClick = nav.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        if (loaded == null) return@Scaffold
        val source = MusicSources.genre(loaded.name)
        AlbumsAndSongs(
            Modifier.padding(padding),
            sections = listOfNotNull(loaded.albums.takeIf { it.isNotEmpty() }?.let { "Albums" to it }),
            songs = loaded.songs,
            vm = vm, dialogs = dialogs, nav = nav,
            onPlay = { i, shuffle -> vm.play(loaded.songs, i, source, shuffle) },
        )
    }
    MusicDialogHost(vm, dialogs)
}
