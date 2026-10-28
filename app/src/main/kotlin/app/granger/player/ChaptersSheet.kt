package app.granger.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.granger.core.library.AudiobookChapter
import app.granger.core.speech.ReadChapter
import app.granger.theme.readout

/** The book's chapters with their lengths; the current one in the accent. Picking one jumps to its start ([onPick] gets its ordinal). */
@Composable
fun ChaptersSheet(chapters: List<AudiobookChapter>, currentOrdinal: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    ChapterList(
        count = chapters.size, current = chapters.indexOfFirst { it.ordinal == currentOrdinal }.takeIf { it >= 0 },
        key = { chapters[it].ordinal }, onDismiss = onDismiss,
    ) { i, current ->
        val chapter = chapters[i]
        ChapterLine(
            "${chapter.ordinal + 1}", chapter.title.ifBlank { "Chapter ${chapter.ordinal + 1}" }, depth = 0, current = current,
            trailing = formatTime(chapter.durationMs), onClick = { onPick(chapter.ordinal) },
        )
    }
}

/**
 * The text's chapters for Reader (Plan U2): a book's contents (nested entries indented) or an article's sections, in
 * the audiobook sheet's look; [current] in the accent. Picking one moves the voice to its start ([onPick] gets its index).
 */
@Composable
fun ReadChaptersSheet(chapters: List<ReadChapter>, current: Int?, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    ChapterList(count = chapters.size, current = current, key = { it }, onDismiss = onDismiss) { i, isCurrent ->
        val chapter = chapters[i]
        ChapterLine(
            "${i + 1}", chapter.title.ifBlank { "Chapter ${i + 1}" }, depth = chapter.depth, current = isCurrent,
            trailing = null, onClick = { onPick(i) }, modifier = Modifier.testTag(ReaderTags.chapter(i)),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChapterList(count: Int, current: Int?, key: (Int) -> Any, onDismiss: () -> Unit, row: @Composable (Int, Boolean) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding()) {
            Text("Chapters", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            if (count == 0) {
                Text(
                    "No chapters yet", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
            LazyColumn(state = rememberLazyListState(initialFirstVisibleItemIndex = current ?: 0)) {
                items(count, key = key) { i -> row(i, i == current) }
            }
        }
    }
}

@Composable
private fun ChapterLine(
    number: String, title: String, depth: Int, current: Boolean, trailing: String?, onClick: () -> Unit, modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            number, style = MaterialTheme.typography.labelLarge.readout,
            color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(36.dp),
        )
        Text(
            title, maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyLarge,
            color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).padding(start = (depth.coerceIn(0, 3) * 16).dp),
        )
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            Text(trailing, style = MaterialTheme.typography.labelMedium.readout, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
