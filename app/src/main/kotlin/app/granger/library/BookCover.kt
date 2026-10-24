package app.granger.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.granger.core.library.Book
import app.granger.core.library.BookFormat
import app.granger.shell.rememberRailCover

private val TileBackground = Color(0xFF141210)
private val TileText = Color(0xFFF1ECE2)
private val TileRule = Color(0xFFE8601C)

/**
 * A 2:3 book cover. The cover file is decoded off the main thread, bounds-checked and sized for the cell; with no
 * cover, or one that won't decode, a near-black tile shows the title over a flame rule.
 */
@Composable
fun BookCover(book: Book, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp))) {
        val cover = rememberRailCover(book.coverPath, maxWidth)
        if (cover != null) {
            Image(cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            TitleTile(book.title)
        }
    }
}

@Composable
private fun TitleTile(title: String) {
    Box(Modifier.fillMaxSize().background(TileBackground).padding(10.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                title,
                color = TileText,
                fontFamily = FontFamily.Serif,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
            )
            Box(Modifier.padding(top = 10.dp).width(28.dp).height(2.dp).background(TileRule))
        }
    }
}

/** What a book's cell says about its format: nothing for an e-book; "PDF", and whether a scan can't be searched. */
fun formatLabel(book: Book): String? = when {
    book.format != BookFormat.PDF -> null
    book.searchable -> "PDF"
    else -> "PDF · Not searchable"
}
