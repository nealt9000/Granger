package app.granger.ebook

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.granger.shell.NarrowRailWidth
import app.granger.theme.GrangerColors

/** A side sheet from the right: a search box and the hits in context. A tap on a row jumps there. */
@Composable
fun BookSearchPanel(
    ui: BookSearchUi,
    onSearch: (String) -> Unit,
    onPick: (BookSearchRow) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(ui.query) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (ui.query.isEmpty()) focus.requestFocus() }
    Box(Modifier.fillMaxSize().padding(start = NarrowRailWidth)) {
        Box(Modifier.fillMaxSize().background(GrangerColors.Scrim.copy(alpha = 0.5f)).clickable(onClick = onDismiss))
        Surface(
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(320.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Search in this book") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch(text) }),
                    modifier = Modifier.fillMaxWidth().padding(16.dp).focusRequester(focus),
                )
                when {
                    ui.running -> Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
                        CircularProgressIndicator(color = GrangerColors.Flame)
                    }
                    ui.query.isNotBlank() && ui.results.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
                        Text("Nothing found for “${ui.query.trim()}”.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> LazyColumn {
                        itemsIndexed(ui.results) { _, row ->
                            Column(Modifier.fillMaxWidth().clickable { onPick(row) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                                if (row.chapter.isNotEmpty()) {
                                    Text(
                                        row.chapter,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(inContext(row), maxLines = 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The hit with its surroundings, the match in bold. */
private fun inContext(row: BookSearchRow): AnnotatedString = buildAnnotatedString {
    append(row.before)
    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = GrangerColors.Flame)) { append(row.match) }
    append(row.after)
}
