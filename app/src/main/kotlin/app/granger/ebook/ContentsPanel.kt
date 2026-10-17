package app.granger.ebook

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import app.granger.core.library.BookMark
import app.granger.shell.NarrowRailWidth
import app.granger.theme.GrangerColors
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator

/** The swatch for a highlight colour name. */
internal fun highlightColor(name: String): Color = when (name) {
    "green" -> Color(0xFF7BC47F)
    "blue" -> Color(0xFF6FA8DC)
    "pink" -> Color(0xFFE88FB4)
    else -> Color(0xFFE8C94A)
}

/** The stored locator's share of the book, as a percent; null when it can't be read. */
internal fun markPercent(mark: BookMark): Int? = try {
    Locator.fromJSON(JSONObject(mark.locatorJson))?.locations?.totalProgression?.let { (it * 100).toInt().coerceIn(0, 100) }
} catch (e: Exception) {
    null
}

/** A side sheet from the right with Contents, Bookmarks and Highlights; a tap on a row navigates and closes it. */
@Composable
fun ContentsPanel(
    state: EbookUi,
    onGoTo: (String) -> Unit,
    onMark: (BookMark) -> Unit,
    onDismiss: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    // Clear of the shell's rail, as the bars are.
    Box(Modifier.fillMaxSize().padding(start = NarrowRailWidth)) {
        Box(Modifier.fillMaxSize().background(GrangerColors.Scrim.copy(alpha = 0.5f)).clickable(onClick = onDismiss))
        Surface(
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(320.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column {
                TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.surface) {
                    listOf("Contents", "Bookmarks", "Highlights").forEachIndexed { i, label ->
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label, maxLines = 1) })
                    }
                }
                when (tab) {
                    0 -> if (state.toc.isEmpty()) Empty("This book has no contents list.") else LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                        itemsIndexed(state.toc) { i, row ->
                            val current = i == state.currentTocIndex
                            Text(
                                row.title.ifEmpty { "Untitled" },
                                color = if (current) GrangerColors.Flame else MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.fillMaxWidth().clickable { onGoTo(row.href) }
                                    .padding(start = (16 + 16 * row.depth).dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                            )
                        }
                    }
                    1 -> if (state.bookmarks.isEmpty()) Empty("No bookmarks yet. Tap the star on the page you want to keep.") else LazyColumn {
                        items(state.bookmarks, key = { it.id }) { mark ->
                            Column(Modifier.fillMaxWidth().clickable { onMark(mark) }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                Text(mark.text.ifEmpty { "Bookmark" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                markPercent(mark)?.let {
                                    Text("$it%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    else -> if (state.highlights.isEmpty()) Empty("No highlights yet. Select text in the book to highlight it.") else LazyColumn {
                        items(state.highlights, key = { it.id }) { mark ->
                            Row(Modifier.fillMaxWidth().clickable { onMark(mark) }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                Box(Modifier.padding(top = 4.dp).size(12.dp).clip(CircleShape).background(highlightColor(mark.color)))
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(mark.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
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
