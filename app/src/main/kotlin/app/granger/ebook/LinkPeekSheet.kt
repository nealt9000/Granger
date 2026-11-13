package app.granger.ebook

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.granger.R
import app.granger.shell.rememberRailCover

/**
 * A tapped reference link's card (Plan LL1 Decision 7): the article's picture, title and first sentence, and Open. The
 * book stays where it is underneath; it never jumps straight out of the book.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkPeekSheet(peek: LinkPeek, onOpen: () -> Unit, onDismiss: () -> Unit, onReadBook: (bookId: String) -> Unit = {}) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag(EbookTags.LINK_PEEK)) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                val image = rememberRailCover(peek.thumbnail, THUMB)
                if (image != null) {
                    Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(THUMB).clip(RoundedCornerShape(8.dp)))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(peek.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (peek.sentence.isNotBlank()) {
                        Text(peek.sentence, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 6, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            peek.book?.let { book ->
                // A work whose book is on the phone (plan Task 8, moved to Task 9): read it, not only about it.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.ebook_link_in_library, book.title), style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = { onReadBook(book.bookId) }) { Text(stringResource(R.string.ebook_link_read)) }
                }
            }
            Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ebook_link_open)) }
        }
    }
}

private val THUMB = 88.dp
