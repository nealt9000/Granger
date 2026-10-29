package app.granger.ebook

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextOverflow
import app.granger.player.VoiceButton
import app.granger.reader.ReaderBarActions
import org.readium.r2.shared.publication.Locator

/**
 * The e-book's and the PDF's bars (Plan U3c Decision 4), the wiki's shared ones. Top: ←, the title and, for an EPUB
 * not being read aloud, Listen (while it is, the mini player over the page has the controls). Bottom: Contents, Search
 * in book (an EPUB's only: a PDF's pages are found from the library index), Aa and the bookmark. No ⋯: the reader has
 * no book actions of its own yet.
 */
internal fun bookBarActions(
    isPdf: Boolean,
    bookmarked: Boolean,
    onBack: () -> Unit,
    onContents: () -> Unit = {},
    onSearch: () -> Unit = {},
    onTextSettings: () -> Unit = {},
    onBookmark: () -> Unit = {},
    /** Starts the voice on this page (Plan V1); null leaves Listen out, as it is for a PDF. */
    onListen: (() -> Unit)? = null,
    /** Whether the voice is on this book: then Listen leaves the bar. */
    voice: VoiceButton = VoiceButton.IDLE,
): ReaderBarActions = ReaderBarActions(
    onBack = onBack,
    onListen = onListen?.takeIf { !isPdf && voice == VoiceButton.IDLE },
    onContents = onContents,
    onSearch = onSearch.takeIf { !isPdf },
    searchLabel = "Search in book",
    onTextSettings = onTextSettings,
    onBookmark = onBookmark,
    bookmarked = bookmarked,
)

/**
 * The progress label over a slider through the whole book, above the bottom bar's buttons. The slider follows
 * [locator] until the finger is down; the jump happens once, on release.
 */
@Composable
fun BookProgress(locator: Locator?, positionsCount: Int, onSeek: (Double) -> Unit) {
    // The released value stays shown until the navigator reports a new place, so the thumb doesn't snap back.
    var dragging by remember { mutableStateOf<Float?>(null) }
    var released by remember { mutableStateOf(false) }
    LaunchedEffect(locator) {
        if (released) {
            dragging = null
            released = false
        }
    }
    val followed = (locator?.locations?.totalProgression ?: 0.0).toFloat().coerceIn(0f, 1f)
    Column {
        if (locator != null) {
            Text(
                ProgressLabel.of(locator, positionsCount),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Slider(
            value = dragging ?: followed,
            onValueChange = { dragging = it; released = false },
            onValueChangeFinished = {
                dragging?.let {
                    released = true
                    onSeek(it.toDouble())
                }
            },
            colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary),
        )
    }
}
