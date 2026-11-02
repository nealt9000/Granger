package app.granger.ebook

import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.ui.res.stringResource
import app.granger.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.granger.core.library.BookAnnotation
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import app.granger.theme.readout

/** The swatch for a highlight colour name. */
internal fun highlightColor(name: String): Color = Color(HighlightColors.argb(name))


/** The stored locator's share of the book, as a percent; null when it can't be read. */
internal fun markPercent(mark: BookAnnotation): Int? = try {
    Locator.fromJSON(JSONObject(mark.locatorJson))?.locations?.totalProgression?.let { (it * 100).toInt().coerceIn(0, 100) }
} catch (e: Exception) {
    null
}

/**
 * What a side sheet's content keeps clear of: the status bar (its tab row or search box would sit under it and lose
 * every tap), the right-hand cutout or navigation bar in landscape, and the navigation bar below. The sheet's
 * surface still runs edge to edge behind them.
 */
internal val SidePanelInsets @Composable get() =
    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End + WindowInsetsSides.Bottom)

/** A side sheet from the right with Contents, Bookmarks and (not for a PDF) Highlights; a tap on a row navigates and closes it. */
@Composable
fun ContentsPanel(
    state: EbookUi,
    onGoTo: (String) -> Unit,
    onMark: (BookAnnotation) -> Unit,
    onDismiss: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    // In landscape the navigation bar or cutout sits at the end: the sheet grows by it, so its tabs keep their 320 dp
    // instead of cutting their labels short.
    val endInset = SidePanelInsets.asPaddingValues().calculateEndPadding(LocalLayoutDirection.current)
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f)).clickable(onClick = onDismiss))
        Surface(
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(320.dp + endInset),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.windowInsetsPadding(SidePanelInsets)) {
                TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.surface) {
                    // A PDF can't be highlighted (Readium's PDF navigator has no selection), so it has no Highlights tab.
                    listOf(R.string.ebook_tab_contents, R.string.ebook_tab_bookmarks, R.string.ebook_tab_highlights).take(if (state.isPdf) 2 else 3).forEachIndexed { i, label ->
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(label), maxLines = 1) })
                    }
                }
                when (tab) {
                    0 -> if (state.toc.isEmpty()) Empty(stringResource(if (state.isPdf) R.string.ebook_no_pdf_contents else R.string.ebook_no_contents)) else LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                        itemsIndexed(state.toc) { i, row ->
                            val current = i == state.currentTocIndex
                            Text(
                                row.title.ifEmpty { stringResource(R.string.ebook_untitled) },
                                color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.fillMaxWidth().clickable { onGoTo(row.href) }
                                    .padding(start = (16 + 16 * row.depth).dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                            )
                        }
                    }
                    1 -> if (state.bookmarks.isEmpty()) Empty(stringResource(R.string.ebook_no_bookmarks)) else LazyColumn {
                        items(state.bookmarks, key = { it.id }) { mark ->
                            Column(Modifier.fillMaxWidth().clickable { onMark(mark) }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                Text(mark.text.ifEmpty { stringResource(R.string.ebook_bookmark) }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                markPercent(mark)?.let {
                                    Text(stringResource(R.string.ebook_percent, it), style = MaterialTheme.typography.labelMedium.readout, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    else -> if (state.highlights.isEmpty()) Empty(stringResource(R.string.ebook_no_highlights)) else LazyColumn {
                        items(state.highlights, key = { it.id }) { mark ->
                            val lost = mark.id in state.unplaceable
                            Row(
                                Modifier.fillMaxWidth().then(if (lost) Modifier else Modifier.clickable { onMark(mark) })
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                            ) {
                                Box(Modifier.padding(top = 4.dp).size(12.dp).clip(CircleShape).background(highlightColor(mark.color)))
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(mark.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                    if (lost) {
                                        Text(stringResource(R.string.ebook_cant_find), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                    }
                                    if (mark.note.isNotBlank()) {
                                        Text(mark.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Empty(message: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}
