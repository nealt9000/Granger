package app.granger.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.granger.core.library.Shelf

/** One row of a "Move to…" list. A [Folder] isn't a place to move to; its children follow it, [Existing.indented]. */
internal sealed interface MoveChoice {
    data class Header(val name: String) : MoveChoice
    data class Folder(val name: String) : MoveChoice
    data class Existing(val id: String, val name: String, val indented: Boolean = false) : MoveChoice
    data class NewCollection(val mediaTypeId: String, val mediaTypeName: String) : MoveChoice
    data object NewMediaType : MoveChoice
}

/**
 * The "Move to collection…" list: each media type's collections, a folder's children indented under it, leaving out
 * [currentCollectionId]; then "New collection…" per type and, if [allowNewMediaType], "New media type…".
 */
internal fun moveChoices(shelves: List<Shelf>, currentCollectionId: String?, allowNewMediaType: Boolean): List<MoveChoice> = buildList {
    for (shelf in shelves) {
        add(MoveChoice.Header(shelf.mediaType.name))
        for (row in shelf.collections) {
            if (row.isFolder) {
                val children = row.children.filter { it.collection.id != currentCollectionId }
                if (children.isEmpty()) continue
                add(MoveChoice.Folder(row.collection.name))
                children.forEach { add(MoveChoice.Existing(it.collection.id, it.collection.name, indented = true)) }
            } else if (row.collection.id != currentCollectionId) {
                add(MoveChoice.Existing(row.collection.id, row.collection.name))
            }
        }
        add(MoveChoice.NewCollection(shelf.mediaType.id, shelf.mediaType.name))
    }
    if (allowNewMediaType) add(MoveChoice.NewMediaType)
}

/**
 * Move a pack (or book, or album) to a collection, to a new collection under a media type, or to a new media type.
 * [allowNewMediaType] false leaves the last choice out (an album stays in Music).
 */
@Composable
fun MovePackDialog(
    shelves: List<Shelf>,
    currentCollectionId: String?,
    onMove: (collectionId: String) -> Unit,
    onMoveToNewCollection: (mediaTypeId: String, name: String) -> Unit,
    onMoveToNewMediaType: (mediaType: String, collection: String) -> Unit,
    onDismiss: () -> Unit,
    allowNewMediaType: Boolean = true,
) {
    var newIn by rememberSaveable { mutableStateOf<String?>(null) }
    var newMediaType by rememberSaveable { mutableStateOf(false) }
    val choices = moveChoices(shelves, currentCollectionId, allowNewMediaType)
    when {
        newIn != null -> NameDialog(
            title = "New collection",
            label = "Collection name",
            confirm = "Move",
            onConfirm = { onMoveToNewCollection(newIn!!, it); onDismiss() },
            onDismiss = { newIn = null },
        )
        newMediaType -> TwoNameDialog(
            title = "New media type",
            firstLabel = "Media type name",
            secondLabel = "Collection name",
            onConfirm = { type, collection -> onMoveToNewMediaType(type, collection); onDismiss() },
            onDismiss = { newMediaType = false },
        )
        else -> ChoiceDialog("Move to collection…", choices, onDismiss) { choice ->
            when (choice) {
                is MoveChoice.Existing -> { onMove(choice.id); onDismiss() }
                is MoveChoice.NewCollection -> newIn = choice.mediaTypeId
                MoveChoice.NewMediaType -> newMediaType = true
                is MoveChoice.Header, is MoveChoice.Folder -> Unit
            }
        }
    }
}

/** Move a whole collection under another media type, or a new one. */
@Composable
fun MoveCollectionDialog(
    shelves: List<Shelf>,
    currentMediaTypeId: String,
    onMove: (mediaTypeId: String) -> Unit,
    onMoveToNewMediaType: (name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var creating by rememberSaveable { mutableStateOf(false) }
    if (creating) {
        NameDialog(
            title = "New media type",
            label = "Media type name",
            confirm = "Move",
            onConfirm = { onMoveToNewMediaType(it); onDismiss() },
            onDismiss = { creating = false },
        )
        return
    }
    val choices = shelves.filter { it.mediaType.id != currentMediaTypeId }
        .map { MoveChoice.Existing(it.mediaType.id, it.mediaType.name) } + MoveChoice.NewMediaType
    ChoiceDialog("Move collection to…", choices, onDismiss) { choice ->
        when (choice) {
            is MoveChoice.Existing -> { onMove(choice.id); onDismiss() }
            MoveChoice.NewMediaType -> creating = true
            else -> Unit
        }
    }
}

@Composable
private fun ChoiceDialog(title: String, choices: List<MoveChoice>, onDismiss: () -> Unit, onPick: (MoveChoice) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn {
                items(choices) { choice ->
                    when (choice) {
                        is MoveChoice.Header -> Text(
                            choice.name,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable(enabled = false) {},
                        )
                        is MoveChoice.Folder -> ListItem(
                            headlineContent = { Text(choice.name, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        )
                        is MoveChoice.Existing -> ListItem(
                            headlineContent = { Text(choice.name) },
                            modifier = Modifier.clickable { onPick(choice) }.padding(start = if (choice.indented) 16.dp else 0.dp),
                        )
                        is MoveChoice.NewCollection -> ListItem(
                            headlineContent = { Text("New collection…") },
                            modifier = Modifier.clickable { onPick(choice) },
                        )
                        MoveChoice.NewMediaType -> ListItem(
                            headlineContent = { Text("New media type…") },
                            modifier = Modifier.clickable { onPick(choice) },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A single-field dialog; the confirm button stays disabled while the name is blank. */
@Composable
fun NameDialog(
    title: String,
    label: String,
    confirm: String,
    initial: String = "",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, label = { Text(label) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TwoNameDialog(
    title: String,
    firstLabel: String,
    secondLabel: String,
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var first by rememberSaveable { mutableStateOf("") }
    var second by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(first, { first = it }, label = { Text(firstLabel) }, singleLine = true)
                OutlinedTextField(second, { second = it }, label = { Text(secondLabel) }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(first, second) }, enabled = first.isNotBlank() && second.isNotBlank()) { Text("Move") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(title: String, message: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
