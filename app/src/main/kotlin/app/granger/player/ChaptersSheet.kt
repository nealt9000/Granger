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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.granger.core.library.AudiobookChapter
import app.granger.theme.readout

/** The book's chapters with their lengths; the current one in the accent. Picking one jumps to its start ([onPick] gets its ordinal). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChaptersSheet(chapters: List<AudiobookChapter>, currentOrdinal: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding()) {
            Text("Chapters", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            if (chapters.isEmpty()) {
                Text(
                    "No chapters yet", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
            val start = chapters.indexOfFirst { it.ordinal == currentOrdinal }.coerceAtLeast(0)
            LazyColumn(state = rememberLazyListState(initialFirstVisibleItemIndex = start)) {
                items(chapters, key = { it.ordinal }) { chapter ->
                    val current = chapter.ordinal == currentOrdinal
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(chapter.ordinal) }.padding(horizontal = 24.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${chapter.ordinal + 1}", style = MaterialTheme.typography.labelLarge.readout,
                            color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(36.dp),
                        )
                        Text(
                            chapter.title.ifBlank { "Chapter ${chapter.ordinal + 1}" }, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(formatTime(chapter.durationMs), style = MaterialTheme.typography.labelMedium.readout, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
