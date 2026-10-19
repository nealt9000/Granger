package app.granger.ebook

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.granger.core.library.BookAnnotation
import app.granger.theme.GrangerColors

/**
 * A bottom sheet for one highlight: its colour, its note and Delete. A colour tap saves at once; the note is
 * saved when the sheet closes. [focusNote] opens with the keyboard on the note (a new note).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HighlightSheet(
    mark: BookAnnotation,
    focusNote: Boolean,
    onChange: (BookAnnotation) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var note by remember(mark.id) { mutableStateOf(mark.note) }
    var color by remember(mark.id) { mutableStateOf(mark.color) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(focusNote) { if (focusNote) focus.requestFocus() }
    fun save() {
        if (note != mark.note || color != mark.color) onChange(mark.copy(note = note, color = color))
    }
    ModalBottomSheet(onDismissRequest = { save(); onDismiss() }) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(mark.text, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                HighlightColors.names.forEach { name ->
                    Box(
                        Modifier.size(36.dp).ring(name == color).background(Color(HighlightColors.argb(name)), CircleShape)
                            .clickable {
                                color = name
                                onChange(mark.copy(note = note, color = name))
                            }
                            .semantics { contentDescription = "$name highlight" },
                    )
                }
            }
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Note") },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                minLines = 2,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { save(); onDismiss() }) { Text("Done", color = GrangerColors.Flame) }
            }
        }
    }
}

/** A ring around the chosen swatch. */
private fun Modifier.ring(selected: Boolean): Modifier =
    if (selected) border(2.dp, GrangerColors.Flame, CircleShape) else this
