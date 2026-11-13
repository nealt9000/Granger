package app.granger.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.granger.R
import app.granger.UiText
import app.granger.core.library.BookMention
import app.granger.ebook.ArticleFooter
import app.granger.ebook.LibraryBook
import app.granger.ebook.LinkDecorations
import app.granger.shell.rememberRailCover

object ArticleFooterTags {
    const val CARD = "article_footer_card"
    const val SHEET = "article_footer_sheet"
}

/** The room under an article the footer's card takes, in dp: the page is padded by it so the article ends above the card. */
const val FOOTER_BAND_DP = 96

/** How long after a page or its footer arrives the reader looks whether the page's end is in view. */
const val FOOTER_SETTLE_MS = 400L

internal object FooterSummary {
    /** The card's line: the work on the phone, how many books mention the subject, how many by this author. */
    fun parts(footer: ArticleFooter): List<UiText> = buildList {
        if (footer.thisWork.isNotEmpty()) add(UiText.Res(R.string.reader_footer_this_work))
        if (footer.inBooks.isNotEmpty()) add(UiText.Plural(R.plurals.reader_footer_in_books, footer.inBooksTotal, footer.inBooksTotal))
        if (footer.byAuthor.isNotEmpty()) add(UiText.Plural(R.plurals.reader_footer_by_author, footer.byAuthor.size, footer.byAuthor.size))
    }

    /** A passage's words before the mention, as the card shows them: the same paragraph's last few words. */
    fun lead(m: BookMention): String = LinkDecorations.contextOf(m.before)?.trimStart()?.let { "…$it" }.orEmpty()
}

/** The card in the band under the article, once the page is read to its end; a tap opens [ArticleFooterSheet]. */
@Composable
fun ArticleFooterCard(footer: ArticleFooter, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 3.dp,
        shadowElevation = 2.dp,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag(ArticleFooterTags.CARD),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.reader_footer_title), style = MaterialTheme.typography.titleMedium)
            Text(
                FooterSummary.parts(footer).map { it.resolve() }.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Everything the library holds about the article: its book, the passages in your books, the author's books. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticleFooterSheet(
    footer: ArticleFooter,
    onOpenBook: (bookId: String) -> Unit,
    onOpenPassage: (bookId: String, mention: BookMention) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag(ArticleFooterTags.SHEET)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (footer.thisWork.isNotEmpty()) {
                Heading(R.string.reader_footer_title)
                footer.thisWork.forEach { BookRow(it, read = true) { onOpenBook(it.bookId) } }
            }
            if (footer.inBooks.isNotEmpty()) {
                Heading(R.string.reader_footer_in_books_heading)
                for (hit in footer.inBooks) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(hit.book.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(pluralStringResource(R.plurals.reader_footer_mentions, hit.count, hit.count), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        for (m in hit.passages) {
                            Text(
                                buildAnnotatedString {
                                    append(FooterSummary.lead(m))
                                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)) { append(m.quote) }
                                    append("…")
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth().clickable { onOpenPassage(hit.book.bookId, m) }.padding(vertical = 6.dp),
                            )
                        }
                    }
                }
            }
            val more = footer.inBooksTotal - footer.inBooks.size
            if (footer.inBooks.isNotEmpty() && more > 0) {
                Text(pluralStringResource(R.plurals.reader_footer_more_books, more, more), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (footer.byAuthor.isNotEmpty()) {
                Heading(R.string.reader_footer_by_author_heading)
                footer.byAuthor.forEach { BookRow(it, read = false) { onOpenBook(it.bookId) } }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun Heading(label: Int) {
    Text(stringResource(label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun BookRow(book: LibraryBook, read: Boolean, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val cover = rememberRailCover(book.cover, COVER)
        if (cover != null) {
            Image(cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(width = COVER, height = COVER * 1.5f).clip(RoundedCornerShape(4.dp)))
        } else {
            Spacer(Modifier.width(COVER))
        }
        Text(book.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (read) TextButton(onClick = onOpen) { Text(stringResource(R.string.reader_footer_read)) }
    }
}

private val COVER: Dp = 40.dp
