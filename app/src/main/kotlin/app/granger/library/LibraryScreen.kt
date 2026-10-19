package app.granger.library

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import app.granger.Features
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.granger.R
import app.granger.audiobooks.ALL_AUDIOBOOKS
import app.granger.audiobooks.AUDIOBOOKS_TYPE_ID
import app.granger.music.ALL_MUSIC
import app.granger.music.MUSIC_TYPE_ID

/** "Media" (media types, each expanding to its collections) and "Archive sources". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    packsFolder: String,
    /** Shown once as a snackbar, e.g. why the app didn't resume reading. */
    notice: String?,
    onOpenCollection: (collectionId: String) -> Unit,
    /** A Music collection, or "All music" (null). */
    onOpenMusic: (collectionId: String?) -> Unit,
    /** An Audiobooks collection's shelf, or "All audiobooks" (null). */
    onOpenAudiobooks: (collectionId: String?) -> Unit,
    onOpenSource: (SourceLink) -> Unit,
    onSearch: () -> Unit,
    onHistory: () -> Unit,
    onReadiness: () -> Unit,
    /**
     * Settings → Music & audiobooks. Empty media types are hidden, so while Music or Audiobooks has nothing on it a
     * row at the end of Media says so and links there. Null: no link (the player is off).
     */
    onMediaSettings: (() -> Unit)? = null,
) {
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var noticeShown by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(notice) {
        if (notice != null && !noticeShown) {
            noticeShown = true
            snackbar.showSnackbar(notice)
        }
    }
    val importNotice by viewModel.notice.collectAsStateWithLifecycle()
    LaunchedEffect(importNotice) {
        val text = importNotice ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
        viewModel.dismissNotice()
    }
    var menuOpen by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.import(uris)
    }
    // Media type ids the user has collapsed; all start expanded.
    var collapsed by rememberSaveable { mutableStateOf(emptyList<String>()) }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Library") },
                actions = {
                    IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, contentDescription = "Search") }
                    IconButton(onClick = onHistory) { Icon(painterResource(R.drawable.ic_history), contentDescription = "History") }
                    IconButton(onClick = onReadiness) { Icon(Icons.Filled.CheckCircle, contentDescription = "Readiness") }
                    if (Features.IMPORT) Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                        DropdownMenu(menuOpen, { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Import books…") },
                                onClick = { menuOpen = false; picker.launch(IMPORT_TYPES) },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        val loaded = ui ?: return@Scaffold
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item { SectionHeader("Media") }
            if (loaded.emptyLibrary) {
                item {
                    Column(
                        Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("No packs installed yet.", style = MaterialTheme.typography.titleMedium)
                        Text("Get packs from Granger or Kiwix", style = MaterialTheme.typography.bodyMedium)
                        Text("Or copy .zim files into $packsFolder", style = MaterialTheme.typography.bodySmall)
                        if (Features.IMPORT) {
                            Text("Or import your own EPUB, TXT or Markdown books", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            for (shelf in loaded.media) {
                val id = shelf.mediaType.id
                val open = id !in collapsed
                item(key = "type:$id") {
                    ListItem(
                        modifier = Modifier.clickable { collapsed = if (open) collapsed + id else collapsed - id },
                        headlineContent = { Text(shelf.mediaType.name, style = MaterialTheme.typography.titleMedium) },
                        trailingContent = {
                            Icon(
                                if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                contentDescription = if (open) "Collapse" else "Expand",
                            )
                        },
                    )
                }
                // Music opens its own tabs: "All music" first, then each collection scoped to its albums. Audiobooks
                // does the same with its shelf: "All audiobooks", then each collection scoped to its audiobooks.
                val music = id == MUSIC_TYPE_ID
                val audiobooks = id == AUDIOBOOKS_TYPE_ID
                if (open && (music || audiobooks)) {
                    item(key = if (music) "all-music" else "all-audiobooks") {
                        ListItem(
                            modifier = Modifier.clickable { if (music) onOpenMusic(null) else onOpenAudiobooks(null) }.padding(start = 16.dp),
                            headlineContent = { Text(if (music) ALL_MUSIC else ALL_AUDIOBOOKS) },
                            supportingContent = {
                                Text(countText(0, 0, shelf.collections.sumOf { it.albums.size }, shelf.collections.sumOf { it.audiobooks.size }))
                            },
                        )
                    }
                }
                if (open) for (row in shelf.collections) {
                    item(key = "collection:${row.collection.id}") {
                        ListItem(
                            modifier = Modifier
                                .clickable {
                                    when {
                                        music -> onOpenMusic(row.collection.id)
                                        audiobooks -> onOpenAudiobooks(row.collection.id)
                                        else -> onOpenCollection(row.collection.id)
                                    }
                                }
                                .padding(start = 16.dp),
                            headlineContent = { Text(row.collection.name) },
                            supportingContent = { Text(countText(row.packs.size, row.books.size, row.albums.size, row.audiobooks.size)) },
                        )
                    }
                }
                item(key = "divider:$id") { HorizontalDivider() }
            }
            val shownTypes = loaded.media.map { it.mediaType.id }
            val missing = missingMediaText(shownTypes)
            if (onMediaSettings != null && missing != null) {
                item(key = "media-hint") {
                    MediaHintRow(missing, mediaHintLinks(shownTypes), onOpenSource, onMediaSettings)
                    HorizontalDivider()
                }
            }
            item { SectionHeader("Archive sources") }
            for (group in loaded.sources) {
                item(key = "source-group:${group.title}") { GroupHeader(group.title) }
                for (source in group.links) {
                    item(key = "source:${source.name}") {
                        ListItem(
                            modifier = Modifier.clickable { onOpenSource(source) }.padding(start = 16.dp),
                            headlineContent = { Text(source.title) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The row that says Music or Audiobooks has nothing yet: links to the missing types' download sources and to
 * Settings → Music & audiobooks (phone media). Tapping the row itself opens Settings, as before.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MediaHintRow(text: String, links: List<SourceLink>, onOpenSource: (SourceLink) -> Unit, onMediaSettings: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onMediaSettings),
        headlineContent = { Text(text) },
        supportingContent = {
            Column {
                Text("Download them, or find them on this phone or add a folder in Settings")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (link in links) OutlinedButton(onClick = { onOpenSource(link) }) { Text(hintLabel(link)) }
                    TextButton(onClick = onMediaSettings) { Text("Settings") }
                }
            }
        },
        trailingContent = { Icon(painterResource(R.drawable.ic_music_note), contentDescription = null) },
    )
}

/** The download sources of the media types not on the shelves: Audiobooks' first, then Music's. */
internal fun mediaHintLinks(shownTypeIds: List<String>): List<SourceLink> = buildList {
    if (AUDIOBOOKS_TYPE_ID !in shownTypeIds) addAll(SOURCE_GROUPS.first { it.title == "Audiobooks" }.links)
    if (MUSIC_TYPE_ID !in shownTypeIds) addAll(SOURCE_GROUPS.first { it.title == "Music" }.links)
}

/** A source's name where it stands without its header, as in the hint row's buttons. */
internal fun hintLabel(link: SourceLink): String = when (link) {
    SourceLink.ARCHIVE_ORG_SPOKEN -> "Archive.org audiobooks"
    SourceLink.ARCHIVE_ORG_MUSIC -> "Archive.org music"
    else -> link.title
}

/** What the media-settings row says, given the media types on the shelves; null when Music and Audiobooks both show. */
internal fun missingMediaText(shownTypeIds: List<String>): String? {
    val music = MUSIC_TYPE_ID in shownTypeIds
    val audiobooks = AUDIOBOOKS_TYPE_ID in shownTypeIds
    return when {
        !music && !audiobooks -> "No music or audiobooks yet"
        !music -> "No music yet"
        !audiobooks -> "No audiobooks yet"
        else -> null
    }
}

private val IMPORT_TYPES = arrayOf(
    "application/epub+zip", "text/plain", "text/markdown", "text/x-markdown", "application/octet-stream",
)

private fun countText(packs: Int, books: Int, albums: Int = 0, audiobooks: Int = 0): String = listOfNotNull(
    if (packs > 0) (if (packs == 1) "1 pack" else "$packs packs") else null,
    if (books > 0) (if (books == 1) "1 book" else "$books books") else null,
    if (albums > 0) (if (albums == 1) "1 album" else "$albums albums") else null,
    if (audiobooks > 0) (if (audiobooks == 1) "1 audiobook" else "$audiobooks audiobooks") else null,
).joinToString(" · ")

/** The file's display name from the document provider, falling back to the last part of its URI. */
fun displayNameOf(context: Context, uri: Uri): String {
    val queried = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }
    return queried?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment.orEmpty()
}

@Composable
private fun GroupHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}
