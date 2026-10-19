package app.granger.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.granger.R
import app.granger.core.library.AudioBookmark

/** This book's bookmarks, newest first. Tap one to play from it; the bin deletes it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioBookmarksSheet(
    bookmarks: List<AudioBookmark>,
    onAdd: () -> Unit,
    onPlay: (AudioBookmark) -> Unit,
    onDelete: (AudioBookmark) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Bookmarks", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onAdd) {
                    Icon(painterResource(R.drawable.ic_bookmark_border), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Add here")
                }
            }
            if (bookmarks.isEmpty()) {
                Text(
                    "No bookmarks in this book yet", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp),
                )
            }
            LazyColumn {
                items(bookmarks.sortedByDescending { it.createdAt }, key = { it.id }) { bookmark ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPlay(bookmark) }.padding(start = 24.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(bookmark.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (bookmark.note.isNotBlank()) {
                                Text(
                                    bookmark.note, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        IconButton(onClick = { onDelete(bookmark) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete bookmark ${bookmark.title}")
                        }
                    }
                }
            }
        }
    }
}
