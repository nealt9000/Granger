package app.granger.library

import android.text.format.Formatter
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.granger.core.library.Pack
import app.granger.core.library.PackStatus
import kotlinx.coroutines.launch

/** One collection's packs. Tap opens a pack in a reader tab; long-press organises or removes it. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CollectionScreen(viewModel: CollectionViewModel, onOpened: (tabId: Long) -> Unit, onBack: () -> Unit) {
    val row by viewModel.ui.collectAsStateWithLifecycle()
    val targets by viewModel.targets.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // The collection disappears when its last pack moves out or it is deleted; leave once it has been seen.
    var seen by remember { mutableStateOf(false) }
    LaunchedEffect(row) {
        if (row != null) seen = true else if (seen) onBack()
    }
    var menuOpen by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<CollectionDialog?>(null) }
    val loaded = row

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(loaded?.collection?.name.orEmpty()) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (loaded != null) Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                        DropdownMenu(menuOpen, { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Move collection to…") },
                                onClick = { menuOpen = false; dialog = CollectionDialog.MoveCollection },
                            )
                            if (loaded.collection.userCreated) {
                                DropdownMenuItem(
                                    text = { Text("Rename") },
                                    onClick = { menuOpen = false; dialog = CollectionDialog.Rename },
                                )
                                DropdownMenuItem(
                                    text = { Text("Delete") },
                                    onClick = { menuOpen = false; dialog = CollectionDialog.Delete },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (loaded != null) LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            items(loaded.packs, key = { it.id }) { pack ->
                Box {
                    var packMenu by remember { mutableStateOf(false) }
                    PackRow(
                        pack,
                        onClick = { scope.launch { viewModel.open(pack)?.let(onOpened) } },
                        onLongClick = { packMenu = true },
                    )
                    DropdownMenu(packMenu, { packMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Move to collection…") },
                            onClick = { packMenu = false; dialog = CollectionDialog.MovePack(pack) },
                        )
                        DropdownMenuItem(
                            text = { Text("Remove from phone") },
                            onClick = { packMenu = false; dialog = CollectionDialog.RemovePack(pack) },
                        )
                    }
                }
                HorizontalDivider()
            }
        }
    }

    val collection = loaded?.collection ?: return
    when (val d = dialog) {
        null -> Unit
        is CollectionDialog.MovePack -> MovePackDialog(
            shelves = targets,
            currentCollectionId = collection.id,
            onMove = { viewModel.movePack(d.pack, it) },
            onMoveToNewCollection = { type, name -> viewModel.movePackToNewCollection(d.pack, type, name) },
            onMoveToNewMediaType = { type, name -> viewModel.movePackToNewMediaType(d.pack, type, name) },
            onDismiss = { dialog = null },
        )
        is CollectionDialog.RemovePack -> ConfirmDialog(
            title = "Remove from phone?",
            message = "${d.pack.title} will be deleted from this phone. You can download it again later.",
            confirm = "Remove",
            onConfirm = { viewModel.remove(d.pack) },
            onDismiss = { dialog = null },
        )
        CollectionDialog.MoveCollection -> MoveCollectionDialog(
            shelves = targets,
            currentMediaTypeId = collection.mediaTypeId,
            onMove = viewModel::moveCollection,
            onMoveToNewMediaType = viewModel::moveCollectionToNewMediaType,
            onDismiss = { dialog = null },
        )
        CollectionDialog.Rename -> NameDialog(
            title = "Rename collection",
            label = "Collection name",
            confirm = "Rename",
            initial = collection.name,
            onConfirm = { viewModel.rename(it); dialog = null },
            onDismiss = { dialog = null },
        )
        CollectionDialog.Delete -> ConfirmDialog(
            title = "Delete collection?",
            message = "Its packs go back to their usual collections. Nothing is removed from the phone.",
            confirm = "Delete",
            onConfirm = viewModel::delete,
            onDismiss = { dialog = null },
        )
    }
}

private sealed interface CollectionDialog {
    data class MovePack(val pack: Pack) : CollectionDialog
    data class RemovePack(val pack: Pack) : CollectionDialog
    data object MoveCollection : CollectionDialog
    data object Rename : CollectionDialog
    data object Delete : CollectionDialog
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PackRow(pack: Pack, onClick: () -> Unit, onLongClick: () -> Unit) {
    val context = LocalContext.current
    val details = listOf(pack.language, pack.date, Formatter.formatShortFileSize(context, pack.sizeBytes))
        .filter { it.isNotBlank() }
        .joinToString(" · ")
    val problem = when (pack.status) {
        PackStatus.OK -> null
        PackStatus.MISSING -> "File not found. Reinsert the SD card or download the pack again."
        PackStatus.CORRUPT -> "This file is damaged and can't be opened."
    }
    ListItem(
        modifier = Modifier.combinedClickable(
            enabled = true,
            onClick = { if (pack.status == PackStatus.OK) onClick() },
            onLongClick = onLongClick,
        ),
        headlineContent = { Text(pack.title) },
        supportingContent = {
            Column {
                Text(details)
                if (problem != null) Text(problem, color = MaterialTheme.colorScheme.error)
            }
        },
    )
}
