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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.granger.R
import app.granger.core.library.AudioBookmark
import app.granger.library.ConfirmDialog
import app.granger.media.DownloadControls
import app.granger.media.DownloadUnit
import app.granger.media.citedMark
import app.granger.media.citedTint
import app.granger.player.chapterName
import app.granger.player.formatTime
import app.granger.theme.readout

/**
 * One audiobook: cover and details, progress, Play/Resume, "Mark as finished", its chapters (tap: play from there)
 * and its bookmarks (tap: play from there; edit the note; delete). A MISSING book shows all of it but can't play.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudiobookScreen(vm: AudiobooksViewModel, audiobookId: String, onBack: () -> Unit, markedChapter: Int? = null) {
    val page by remember(audiobookId) { vm.book(audiobookId) }.collectAsStateWithLifecycle(null)
    var editing by remember { mutableStateOf<AudioBookmark?>(null) }
    var deleting by remember { mutableStateOf<AudioBookmark?>(null) }
    val loaded = page
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(loaded?.book?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back)) } },
            )
        },
    ) { padding ->
        if (loaded == null) return@Scaffold
        val book = loaded.book
        val download = loaded.download
        val remove = loaded.onRemoveDownload
        val list = rememberLazyListState()
        val citedMarked = citedMark(
            list, citedChapterIndex(loaded.chapters.map { it.ordinal }, markedChapter, download != null && remove != null, book.description.isNotBlank()),
        )
        LazyColumn(Modifier.padding(padding).fillMaxSize(), state = list) {
            item {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    AudiobookCover(book.coverPath, book.title, Modifier.width(140.dp).alpha(if (loaded.missing) 0.4f else 1f))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(book.title, style = MaterialTheme.typography.titleLarge)
                        Text(authorOf(book.author), style = MaterialTheme.typography.bodyMedium)
                        if (book.narrator.isNotBlank()) {
                            Text(stringResource(R.string.audiobooks_read_by, book.narrator), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(formatTime(loaded.progress.durationMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (loaded.missing) {
                            Text(stringResource(R.string.audiobooks_missing_files), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
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
                            Listening.FINISHED -> stringResource(R.string.audiobooks_finished)
                            Listening.UNPLAYED -> stringResource(R.string.audiobooks_not_started)
                            Listening.IN_PROGRESS -> stringResource(R.string.audiobooks_percent_left, (loaded.fraction * 100).toInt(), formatTime(left))
                        },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { vm.play(book.id) }, enabled = loaded.playable) { Text(stringResource(loaded.playLabel)) }
                    val finished = loaded.listening == Listening.FINISHED
                    OutlinedButton(onClick = { vm.setFinished(book.id, !finished) }, enabled = loaded.chapters.isNotEmpty()) {
                        Text(stringResource(if (finished) R.string.audiobooks_mark_unfinished else R.string.audiobooks_mark_finished))
                    }
                }
            }
            if (download != null && remove != null) {
                item(key = "download") {
                    DownloadControls(book.title, download, loaded.filesDone, loaded.filesTotal, DownloadUnit.CHAPTERS, onRemove = remove)
                }
            }
            if (book.description.isNotBlank()) item { Description(book.description) }
            item { SectionTitle(stringResource(R.string.player_chapters)) }
            items(loaded.chapters, key = { "chapter:${it.ordinal}" }) { chapter ->
                val current = chapter.ordinal == loaded.currentChapter
                ListItem(
                    modifier = Modifier.citedTint(citedMarked && chapter.ordinal == markedChapter)
                        .clickable(enabled = loaded.playable) { vm.playChapter(book.id, chapter) },
                    leadingContent = { Text("${chapter.ordinal + 1}", style = MaterialTheme.typography.labelLarge.readout) },
                    headlineContent = {
                        Text(
                            chapterName(chapter.title, chapter.ordinal).resolve(), maxLines = 2, overflow = TextOverflow.Ellipsis,
                            fontWeight = if (current) FontWeight.Bold else null,
                            color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    trailingContent = { Text(formatTime(chapter.durationMs), style = MaterialTheme.typography.labelMedium.readout) },
                )
            }
            item { HorizontalDivider(Modifier.padding(top = 8.dp)) }
            item { SectionTitle(stringResource(R.string.player_bookmarks)) }
            if (loaded.bookmarks.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.audiobooks_no_bookmarks), style = MaterialTheme.typography.bodyMedium,
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
                            IconButton(onClick = { editing = mark }) { Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.audiobooks_edit_note)) }
                            IconButton(onClick = { deleting = mark }) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.player_delete_bookmark, mark.title)) }
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
            title = stringResource(R.string.audiobooks_delete_bookmark), message = mark.title, confirm = stringResource(R.string.common_delete),
            onConfirm = { vm.deleteBookmark(mark) }, onDismiss = { deleting = null },
        )
    }
}

/**
 * The list index of chapter [ordinal] in an audiobook page: after the header, the progress, the buttons, the download
 * controls and the description (when shown) and the "Chapters" title. [ordinals] are the chapters in page order.
 */
internal fun citedChapterIndex(ordinals: List<Int>, ordinal: Int?, hasDownload: Boolean, hasDescription: Boolean): Int? {
    if (ordinal == null) return null
    val i = ordinals.indexOf(ordinal)
    return if (i < 0) null else i + 4 + (if (hasDownload) 1 else 0) + (if (hasDescription) 1 else 0)
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
        title = { Text(stringResource(R.string.audiobooks_note)) },
        text = { OutlinedTextField(note, { note = it }, label = { Text(stringResource(R.string.audiobooks_note)) }, minLines = 3) },
        confirmButton = { TextButton(onClick = { onSave(note) }) { Text(stringResource(R.string.audiobooks_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
