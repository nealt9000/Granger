package app.granger.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.granger.core.library.Book
import app.granger.core.library.BookFormat
import app.granger.shell.rememberRailCover
import app.granger.theme.MediaTile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A cover for a book that has no cover file of its own yet (a Gutenberg book not yet opened: [GutenbergCovers]); null
 * when there is none. Called off the main thread.
 */
val LocalPackCovers = staticCompositionLocalOf<suspend (Book) -> String?> { { null } }

/**
 * A 2:3 book cover. The cover file is decoded off the main thread, bounds-checked and sized for the cell; with no
 * cover, or one that won't decode, the [MediaTile]: the title over a lantern rule, the same in Field and Night (a cover is an object).
 */
@Composable
fun BookCover(book: Book, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp))) {
        val packCovers = LocalPackCovers.current
        val path by produceState(book.coverPath, book.id, book.coverPath) {
            if (value == null) value = withContext(Dispatchers.IO) { packCovers(book) }
        }
        val cover = rememberRailCover(path, maxWidth)
        if (cover != null) {
            Image(cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            MediaTile(book.title, kind = null)
        }
    }
}

/** What a book's cell says about its format: nothing for an e-book; "PDF", and whether a scan can't be searched. */
fun formatLabel(book: Book): String? = when {
    book.format != BookFormat.PDF -> null
    book.searchable -> "PDF"
    else -> "PDF · Not searchable"
}
