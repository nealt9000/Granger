package app.granger.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.granger.R
import app.granger.core.library.Book
import app.granger.core.library.BookPosition
import app.granger.core.library.CollectionRow
import app.granger.core.library.PackStatus
import app.granger.core.library.onShelf
import app.granger.explore.Eyebrow
import app.granger.explore.SpreadColors
import app.granger.explore.SpreadType

/**
 * The reading shelf (Plan O4 Task 5; S1 on the O3 canvas, Neal 2026-11-19): what you're reading now, most recent first,
 * and what you've finished, from the books' positions.
 */
data class ShelfReading(val book: Book, val progress: Float)

data class ReadingShelfUi(val now: List<ShelfReading>, val finished: List<Book>)

object ReadingShelf {
    /** A book read past this is finished. */
    const val FINISHED = 0.98

    /** Reading now at most (one large, two small, then a short row), and Finished at most. */
    const val NOW = 6
    const val DONE = 12

    /** From the shelf's books and their positions (pure, for tests): on the shelf, readable, opened at least once. */
    fun of(books: List<Book>, positions: Map<String, BookPosition>): ReadingShelfUi {
        val read = books.filter { it.onShelf && it.status == PackStatus.OK }.mapNotNull { b -> positions[b.id]?.let { b to it } }
            .sortedByDescending { it.second.updatedAt }
        val now = read.filter { it.second.totalProgression < FINISHED }.take(NOW).map { (b, p) -> ShelfReading(b, p.totalProgression.toFloat()) }
        val finished = read.filter { it.second.totalProgression >= FINISHED }.take(DONE).map { it.first }
        return ReadingShelfUi(now, finished)
    }
}

/** Reading now: the first book's cover large with its progress, the next two small beside it, the rest in a row. */
@Composable
internal fun ReadingNow(now: List<ShelfReading>, onOpen: (Book) -> Unit) {
    if (now.isEmpty()) return
    Column(Modifier.padding(horizontal = 16.dp).padding(top = 12.dp).testTag(ShelfTags.READING_NOW), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Eyebrow(stringResource(R.string.shelf_reading_now))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ShelfCover(now[0], Modifier.width(150.dp), large = true, onOpen = onOpen)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (r in now.drop(1).take(2)) ShelfCover(r, Modifier.width(92.dp), large = false, onOpen = onOpen)
            }
        }
        if (now.size > 3) LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(now.drop(3), key = { it.book.id }) { r -> ShelfCover(r, Modifier.width(72.dp), large = false, onOpen = onOpen) }
        }
    }
}

@Composable
private fun ShelfCover(r: ShelfReading, modifier: Modifier, large: Boolean, onOpen: (Book) -> Unit) {
    val percent = (r.progress * 100).toInt()
    val words = stringResource(R.string.shelf_read_percent, r.book.title, percent)
    Column(modifier.clickable { onOpen(r.book) }.semantics(mergeDescendants = true) { contentDescription = words }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BookCover(r.book, Modifier.fillMaxWidth())
        LinearProgressIndicator(progress = { r.progress }, modifier = Modifier.fillMaxWidth().height(3.dp), color = SpreadColors.accent)
        if (large) {
            Text(r.book.title, style = SpreadType.serif.copy(fontSize = 17.sp, lineHeight = 21.sp), maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
            if (r.book.author.isNotBlank()) Text(r.book.author, style = SpreadType.ui, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** A collection as a fanned stack of up to three of its covers (S1), with its name and count. */
@Composable
internal fun CollectionStack(row: CollectionRow, name: String, onOpen: () -> Unit) {
    val books = (row.books + row.children.flatMap { it.books }).take(3)
    val count = row.books.size + row.children.sumOf { it.books.size }
    Column(Modifier.width(132.dp).clickable(onClick = onOpen).testTag(ShelfTags.stack(row.collection.id)), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.width(132.dp).height(150.dp)) {
            books.reversed().forEachIndexed { i, b ->
                val at = books.size - 1 - i
                BookCover(b, Modifier.width(92.dp).offset(x = (at * 18).dp, y = (at * 8).dp))
            }
        }
        Text(name, style = SpreadType.uiMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(androidx.compose.ui.res.pluralStringResource(R.plurals.shelf_books, count, count), style = SpreadType.ui, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Finished: a row of small covers. */
@Composable
internal fun Finished(books: List<Book>, onOpen: (Book) -> Unit) {
    if (books.isEmpty()) return
    Column(Modifier.padding(top = 18.dp).testTag(ShelfTags.FINISHED), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Eyebrow(stringResource(R.string.shelf_finished), Modifier.padding(horizontal = 16.dp))
        LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(books, key = { it.id }) { b -> Box(Modifier.width(64.dp).clickable { onOpen(b) }.semantics { contentDescription = b.title }) { BookCover(b, Modifier.fillMaxWidth()) } }
        }
    }
}

/** The footer: sizes, files and sources are Outfitting's now (builder's call 6). */
@Composable
internal fun ShelfFooter(onOutfitting: () -> Unit) {
    TextButton(onClick = onOutfitting, modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp).testTag(ShelfTags.FOOTER)) {
        Text(stringResource(R.string.shelf_footer), color = SpreadColors.accent, style = SpreadType.uiMedium)
    }
}

object ShelfTags {
    const val READING_NOW = "shelf_reading_now"
    const val FINISHED = "shelf_finished"
    const val FOOTER = "shelf_footer"
    fun stack(id: String) = "shelf_stack_$id"
}
