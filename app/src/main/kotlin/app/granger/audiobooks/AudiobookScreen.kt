package app.granger.audiobooks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.granger.core.library.AudioBookmark
import app.granger.library.ConfirmDialog
import app.granger.media.DownloadControls
import app.granger.player.formatTime

/**
 * One audiobook: cover and details, progress, Play/Resume, "Mark as finished", its chapters (tap: play from there)
 * and its bookmarks (tap: play from there; edit the note; delete). A MISSING book shows all of it but can't play.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudiobookScreen(vm: AudiobooksViewModel, audiobookId: String, onBack: () -> Unit) {
    val page by remember(audiobookId) { vm.book(audiobookId) }.collectAsStateWithLifecycle(null)
    var editing by remember { mutableStateOf<AudioBookmark?>(null) }
    var deleting by remember { mutableStateOf<AudioBookmark?>(null) }
    val loaded = page
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(loaded?.book?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        if (loaded == null) return@Scaffold
        val book = loaded.book
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    AudiobookCover(book.coverPath, Modifier.width(140.dp).alpha(if (loaded.missing) 0.4f else 1f))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(book.title, style = MaterialTheme.typography.titleLarge)
                        Text(book.author.ifBlank { UNKNOWN_AUTHOR }, style = MaterialTheme.typography.bodyMedium)
                        if (book.narrator.isNotBlank()) {
                            Text("Read by ${book.narrator}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(formatTime(loaded.progress.durationMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (loaded.missing) {
                            Text("Missing: its files aren't on this phone", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(progress = { loaded.fraction }, modifier = Modifier.fillMaxWidth())
                    val left = (loaded.progress.durationMs - loaded.progress.positionMs).coerceAtLeast(0L)
                    Text(
                        when (loaded.listening) {
                            Listening.FINISHED -> "Finished"
                            Listening.UNPLAYED -> "Not started"
                            Listening.IN_PROGRESS -> "${(loaded.fraction * 100).toInt()}% · ${formatTime(left)} left"
                        },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { vm.play(book.id) }, enabled = loaded.playable) { Text(loaded.playLabel) }
                    val finished = loaded.listening == Listening.FINISHED
                    OutlinedButton(onClick = { vm.setFinished(book.id, !finished) }, enabled = loaded.chapters.isNotEmpty()) {
                        Text(if (finished) "Mark as unfinished" else "Mark as finished")
                    }
                }
            }
            val download = loaded.download
            val remove = loaded.onRemoveDownload
            if (download != null && remove != null) {
                item(key = "download") {
                    DownloadControls(book.title, download, loaded.filesDone, loaded.filesTotal, "chapters", onRemove = remove)
                }
            }
            if (book.description.isNotBlank()) item { Description(book.description) }
            item { SectionTitle("Chapters") }
            items(loaded.chapters, key = { "chapter:${it.ordinal}" }) { chapter ->
                val current = chapter.ordinal == loaded.currentChapter
                ListItem(
                    modifier = Modifier.clickable(enabled = loaded.playable) { vm.playChapter(book.id, chapter) },
                    leadingContent = { Text("${chapter.ordinal + 1}", style = MaterialTheme.typography.labelLarge) },
                    headlineContent = {
                        Text(
                            chapter.title.ifBlank { "Chapter ${chapter.ordinal + 1}" }, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            fontWeight = if (current) FontWeight.Bold else null,
                            color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    trailingContent = { Text(formatTime(chapter.durationMs), style = MaterialTheme.typography.labelMedium) },
                )
            }
            item { HorizontalDivider(Modifier.padding(top = 8.dp)) }
            item { SectionTitle("Bookmarks") }
            if (loaded.bookmarks.isEmpty()) {
                item {
                    Text(
                        "No bookmarks yet. Add them from the player.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            items(loaded.bookmarks, key = { "bookmark:${it.id}" }) { mark ->
                ListItem(
                    modifier = Modifier.clickable(enabled = loaded.playable) { vm.playBookmark(mark) },
                    headlineContent = { Text(mark.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = if (mark.note.isBlank()) null else {
                        { Text(mark.note, maxLines = 4, overflow = TextOverflow.Ellipsis) }
                    },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { editing = mark }) { Icon(Icons.Filled.Edit, contentDescription = "Edit note") }
                            IconButton(onClick = { deleting = mark }) { Icon(Icons.Filled.Delete, contentDescription = "Delete bookmark ${mark.title}") }
                        }
                    },
                )
            }
        }
    }
    editing?.let { mark ->
        NoteDialog(mark.note, onSave = { vm.editNote(mark, it); editing = null }, onDismiss = { editing = null })
    }
    deleting?.let { mark ->
        ConfirmDialog(
            title = "Delete bookmark?", message = mark.title, confirm = "Delete",
            onConfirm = { vm.deleteBookmark(mark) }, onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp))
}

/** Collapsed to a few lines; tap to read it all. */
@Composable
private fun Description(text: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Text(
        text, style = MaterialTheme.typography.bodyMedium,
        maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clickable { expanded = !expanded }.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** A bookmark's note; it may be left empty. */
@Composable
private fun NoteDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var note by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Note") },
        text = { OutlinedTextField(note, { note = it }, label = { Text("Note") }, minLines = 3) },
        confirmButton = { TextButton(onClick = { onSave(note) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
